package polar;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TerraformProviderCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

/**
 * The terraform-provider generator, grouped by RESOURCE instead of by tag.
 *
 * It is github.com/n-at-han-k/terraform-provider-wso2's generator with the
 * parts RT's API shape breaks replaced -- the same replacements
 * crossplane-provider-rt makes, because both targets ask the same question of
 * the document: which operations are one resource, and which of them is the
 * create, the read, the update and the delete.
 *
 * Upstream keys its operation map on the tag and then asks
 * {@code CodegenOperation.isRestfulCreate()} which operation is the create.
 * Those helpers assume the create and the collection share a path, and RT
 * does not -- it creates with {@code POST /ticket} and searches with
 * {@code POST /tickets}. Every RT resource comes out of upstream's grouping
 * either empty or doubled, so the shape of the path decides instead, and a
 * create is told from a search by what it answers: 201, not 200.
 *
 * Everything else -- the schema, the CRUD bodies, the client, the image -- is
 * the WSO2 generator's.
 */
public class TerraformCodegen extends TerraformProviderCodegen {

    private static final Pattern PARAM = Pattern.compile("\\{([^{}/]+)\\}");

    /**
     * THE CONFIGURATION THIS GENERATOR TAKES, all of it, and all of it written
     * by bin/derive rather than by hand -- derive has read the document and
     * knows which positions can be a typed block and which cannot, so it says
     * so instead of leaving the generator to guess.
     *
     * nestedAttributes   expand an object, or an array of objects, into a typed
     *                    Terraform block instead of a string holding JSON.
     *                    Without this every nested position is JSON, which is
     *                    where this generator started.
     *
     * nestedMaxDepth     how many levels to expand before falling back to JSON.
     *                    One is enough for Polar and is the default: a product's
     *                    `prices` is a list of flat objects and a benefit's
     *                    `meter_credit_properties` is a flat object, so one
     *                    level types everything that matters. A child deeper
     *                    than this is JSON inside its typed parent, not a
     *                    reason to abandon the parent.
     *
     * jsonAttributes     positions that must stay JSON whatever their shape.
     *                    derive puts two kinds here: freeform objects, where
     *                    there are no properties to make attributes out of
     *                    (`metadata` is a map of anything), and the unions it
     *                    refused to flatten because they are recursive (a
     *                    meter's `filter`, whose clauses contain filters).
     */
    private boolean nestedAttributes = true;
    private int nestedMaxDepth = 1;
    private final Set<String> jsonAttributes = new HashSet<>();

    public TerraformCodegen() {
        super();
    }

    @Override
    public String getName() {
        return "polar-terraform";
    }

    @Override
    public String getHelp() {
        return "Generates a Terraform provider, one resource per collection path rather than per tag.";
    }

    @Override
    public void processOpts() {
        super.processOpts();

        Object nested = additionalProperties().get("nestedAttributes");
        nestedAttributes = nested == null || "true".equalsIgnoreCase(String.valueOf(nested));

        Object depth = additionalProperties().get("nestedMaxDepth");
        if (depth != null) {
            nestedMaxDepth = Integer.parseInt(String.valueOf(depth).trim());
        }

        // THE AUTH SCHEME IS THE DOCUMENT'S, not a guess. Polar declares every
        // token scheme as `type: http, scheme: bearer`, so the header is
        // `Authorization: Bearer <token>`. That is NOT what this generator's
        // ancestor sent: it was written for Request Tracker, whose scheme is the
        // literal word `token`, and a Bearer API answers 401 to that with
        // nothing useful in the body.
        additionalProperties().put("authHeaderPrefix", bearerPrefix());

        Object json = additionalProperties().get("jsonAttributes");
        if (json != null) {
            for (String name : String.valueOf(json).split(",")) {
                if (!name.trim().isEmpty()) {
                    jsonAttributes.add(name.trim());
                }
            }
        }

        // What makes the output DEPLOYABLE rather than merely compilable: the
        // image that carries the binary and the workflow that publishes it.
        // Upstream emits the Go, a go.mod and a GNUmakefile and stops there,
        // because it has no opinion about where a provider with no registry
        // ends up. We do: an initContainer copies the binary out of this image
        // into provider-opentofu's filesystem mirror.
        //
        // These live under the same resource directory name as upstream's
        // templates and resolve off the classpath, our jar first -- so the
        // stock templates still come from the CLI's jar and no -t is needed.
        // The one piece of hand-written Go in the provider: see the file's own
        // comment for why a JSON attribute cannot simply take what the server
        // answered.
        supportingFiles.add(new SupportingFile("json_superset.mustache",
                "internal" + File.separator + "provider", "json_superset.go"));
        supportingFiles.add(new SupportingFile("json_superset_test.mustache",
                "internal" + File.separator + "provider", "json_superset_test.go"));
        supportingFiles.add(new SupportingFile("Dockerfile.mustache", "", "Dockerfile"));
        supportingFiles.add(new SupportingFile("dockerignore.mustache", "", ".dockerignore"));
        supportingFiles.add(new SupportingFile("build_image_workflow.mustache",
                ".github" + File.separator + "workflows", "build-image.yml"));
        // The same binary as that image, as release assets -- for a consumer with
        // no cluster to copy it out of. ~/infra/clan's tofu root resolves this
        // provider from a filesystem mirror, and a released binary is a fetchurl
        // and a sha256 rather than a vendorHash to recompute by hand.
        supportingFiles.add(new SupportingFile("release_workflow.mustache",
                ".github" + File.separator + "workflows", "release.yml"));

    }

    /**
     * The group key is the collection path, so the member operations land
     * with the collection's own -- and what is not part of a resource at all
     * never lands anywhere.
     *
     * RT POSTs both to create and to SEARCH -- {@code POST /ticket} creates
     * one, {@code POST /tickets} searches -- and the two are told apart by
     * what they answer, not by how the path is spelled. A create answers 201,
     * a search answers 200. That reading survives the places where the
     * spelling does not: {@code POST /lifecycles} is a create on a plural
     * path, and {@code POST /customfields} is a search on one.
     *
     * What is kept is everything a resource actually calls:
     *
     * <pre>
     *   a member path                GET read, PUT update, DELETE delete
     *   POST answering 201           the create
     *   PUT on a collection          RT's idempotent "set these" --
     *                                /user/{idOrName}/groups
     *   DELETE on a collection       the inverse of that set
     * </pre>
     *
     * and what is dropped is a GET on a collection (a list, which a resource
     * never calls) and a POST that answers 200 (a search, or an action
     * endpoint like /lifecycle/{name}/validate).
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation,
                                    CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        String collection = collectionOf(resourcePath);

        String method = co.httpMethod.toUpperCase(Locale.ROOT);
        boolean member = isMember(collection, resourcePath);
        boolean set = "PUT".equals(method) || "PATCH".equals(method);
        boolean create = "POST".equals(method) && answers(operation, "201");
        boolean clear = "DELETE".equals(method);

        if (!member && !set && !create && !clear) {
            return;
        }

        if (!describesAResource(collection)) {
            return;
        }

        List<CodegenOperation> group =
                operations.computeIfAbsent(canonicalCollection(collection), key -> new ArrayList<>());

        // An operation carrying two tags is offered once per tag; here both
        // offers name the same group, so the second one is a duplicate.
        if (group.stream().anyMatch(existing -> existing.operationId.equals(co.operationId))) {
            return;
        }

        group.add(co);
        co.baseName = lastSegment(collection);
    }

    /**
     * A group that describes nothing. RT revokes a right with
     * {@code DELETE /queue/{id}/rights/{right}/group/{id}} and removes one
     * member with {@code DELETE /group/{id}/member/{id}}. Each looks like a
     * member path, so each would become a resource that can only be deleted
     * -- never created, never read, nothing for Terraform to own. A
     * collection with neither a create nor a read is a verb spelled as a
     * path.
     */
    private boolean describesAResource(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return true;
        }

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();

            if (!collectionOf(path).equals(collection)) {
                continue;
            }

            for (Map.Entry<PathItem.HttpMethod, Operation> described
                    : entry.getValue().readOperationsMap().entrySet()) {
                String method = described.getKey().name();
                boolean member = isMember(collection, path);

                if (member && "GET".equals(method)) {
                    return true;
                }
                if (!member && ("PUT".equals(method) || "PATCH".equals(method)
                        || ("POST".equals(method) && answers(described.getValue(), "201")))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * The collection every group of this resource is keyed on.
     *
     * RT creates a lifecycle at {@code POST /lifecycles} and addresses it at
     * {@code /lifecycle/{name}} ever after, and both name the same resource.
     * Left alone they are two groups writing one set of files, the second
     * silently overwriting the first with half a resource.
     */
    private String canonicalCollection(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return collection;
        }

        String name = toApiName(collection);
        String canonical = collection;

        for (String path : openAPI.getPaths().keySet()) {
            String other = collectionOf(path);

            if (other.equals(canonical) || !toApiName(other).equals(name)
                    || !describesAResource(other)) {
                continue;
            }

            // The member path is what the resource is addressed by, so its
            // collection is the one to key on; failing that, take the same
            // one every time rather than whichever was seen first.
            boolean canonicalAddresses = addressedByAMember(canonical);
            boolean otherAddresses = addressedByAMember(other);

            if (otherAddresses && !canonicalAddresses) {
                canonical = other;
            } else if (otherAddresses == canonicalAddresses && other.compareTo(canonical) < 0) {
                canonical = other;
            }
        }

        return canonical;
    }

    private boolean addressedByAMember(String collection) {
        return openAPI.getPaths().keySet().stream()
                .anyMatch(path -> collectionOf(path).equals(collection) && isMember(collection, path));
    }

    /**
     * The collection a group is about: the one its member path hangs off,
     * because that is what the resource is addressed by. With no member path
     * -- a set, like /group/{id}/members -- the shortest path will do, since
     * they are all the same one.
     */
    private String collectionOf(List<CodegenOperation> group) {
        String shortest = null;

        for (CodegenOperation op : group) {
            String collection = collectionOf(op.path);

            if (!collection.equals(op.path)) {
                return collection;
            }
            if (shortest == null || collection.length() < shortest.length()) {
                shortest = collection;
            }
        }

        return shortest == null ? "" : shortest;
    }

    /** Whether a described operation documents this response code. */
    private boolean answers(Operation operation, String code) {
        return operation.getResponses() != null && operation.getResponses().containsKey(code);
    }

    /**
     * Which operation is the create, the read, the update, the delete.
     *
     * Upstream asks {@code CodegenOperation.isRestfulCreate()} and friends,
     * and those cannot answer for a NESTED resource: {@code isMemberPath()}
     * opens with {@code if (pathParams.size() != 1) return false}, so
     * {@code /tenants/{tenant-id}/owners/{owner-id}} -- two path params --
     * looks like nothing at all, and the whole resource comes out empty.
     *
     * The shape of the path already says it. This group IS a collection path
     * and its member path, so an operation on the collection is the create or
     * the list, and one on the member is the read, the update or the delete.
     * Marked as vendor extensions, which is upstream's own first-pass hook.
     *
     * Where a member path offers both PUT and PATCH the update is the PUT: a
     * PATCH body is a list of patch operations while the resource sends a
     * whole model.
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        List<CodegenOperation> group = objs.getOperations().getOperation();
        String collection = collectionOf(group);

        CodegenOperation update = null;
        boolean deleteOnCollection = false;

        for (CodegenOperation op : group) {
            boolean member = isMember(collection, op.path);
            String method = op.httpMethod.toUpperCase(Locale.ROOT);

            if (member) {
                if ("GET".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-read", true);
                } else if ("DELETE".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-delete", true);
                } else if ("PUT".equals(method) || ("PATCH".equals(method) && update == null)) {
                    update = "PUT".equals(method) || update == null ? op : update;
                }
            } else if ("DELETE".equals(method)) {
                // The inverse of a set: DELETE /group/{id}/members empties
                // what PUT /group/{id}/members filled. It takes no id of its
                // own.
                op.vendorExtensions.put("x-terraform-is-delete", true);
                deleteOnCollection = true;
            } else if (!"GET".equals(method)) {
                // A POST answering 201, or a set's PUT. Its path need not be
                // the collection: RT creates a lifecycle at /lifecycles and
                // addresses it at /lifecycle/{name} ever after.
                op.vendorExtensions.put("x-terraform-is-create", true);
            }
        }

        if (update != null) {
            update.vendorExtensions.put("x-terraform-is-update", true);
        }

        OperationsMap processed = super.postProcessOperationsWithModels(objs, allModels);

        // Upstream leaves the CREATE path's parameters spelled `{idOrName}`
        // while converting the others to `%v` -- it has never had to
        // interpolate a create, because the only create it recognises is on a
        // top-level collection that takes no parameters. A nested create then
        // goes out to a URL with a literal `{idOrName}` in it, which
        // fmt.Sprintf COMPILES and the server answers 404 for.
        for (String key : new String[] {"createPath", "readPath", "updatePath", "deletePath"}) {
            Object path = processed.getOperations().get(key);
            if (path != null) {
                processed.getOperations().put(key, path.toString().replaceAll("\\{[^}]*\\}", "%v"));
            }
        }

        // A delete on the collection empties a set and takes no id of its own.
        processed.getOperations().put("deleteIsSet", deleteOnCollection);

        // THE UPDATE HAS ITS OWN BODY. Upstream has one requestModel, the create's,
        // and sends it to the update too -- so PATCH /applications/{id} received
        // an ApplicationModel where it wanted an ApplicationPatchModel, and WSO2
        // answered UE-10000, "provided request body content is not in the
        // expected format". They really are different: the patch model has no
        // inboundProtocolConfiguration and no id.
        CodegenOperation updateOp = operationFlagged(group, "x-terraform-is-update");
        if (updateOp != null && updateOp.bodyParam != null) {
            processed.getOperations().put("updateRequestModel", updateOp.bodyParam.dataType);
        }

        // Upstream takes the request body from the CREATE operation only, so a
        // resource you can update but not create -- a tenant's owner is PUT,
        // never POSTed -- had no request model, and ToClientModel came out as
        // `*client.` with no type. The update body is the write shape there.
        if (processed.getOperations().get("requestModel") == null) {
            CodegenOperation writes = operationFlagged(group, "x-terraform-is-update");

            if (writes != null && writes.bodyParam != null) {
                processed.getOperations().put("requestModel", writes.bodyParam.dataType);
            }
        }

        // A free-form or list body is not a model. GET /applications/{id}/export
        // answers an unconstrained object -- returnType `interface{}` -- and
        // PATCH /organizations/self takes `[]OrganizationPatchRequestItem`, so
        // the templates spelled `client.interface{}` and `client.[]Organization...`.
        // Where the name is not a generated model, there is no model. AFTER the
        // fallback above, or the fallback puts one straight back.
        for (String key : new String[] { "responseModel", "requestModel", "updateRequestModel" }) {
            Object name = processed.getOperations().get(key);

            if (name != null && modelNamed(allModels, String.valueOf(name)) == null) {
                processed.getOperations().put(key, null);
            }
        }

        // Upstream strips a trailing "api" off the resource name, which is right
        // for a tag called PetApi and wrong for a PATH segment: /authorized-apis
        // became wso2_application_authorized -- a name that says nothing, and
        // one the file beside it (application_authorized_api_resource.go) does
        // not even agree with. The path already named this resource.
        processed.getOperations().put("resourceClassName", toApiName(collection));
        processed.getOperations().put("resourceName",
                underscore(toApiName(collection)).toLowerCase(Locale.ROOT));

        reshapeAttributes(processed.getOperations(), allModels);
        wirePathParams(processed.getOperations(), group);

        // Whether the identifier is a string, which decides how a template can
        // ask whether it is empty.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tf =
                (List<Map<String, Object>>) processed.getOperations().get("tfAttributes");
        Object idName = processed.getOperations().get("idFieldExported");
        processed.getOperations().put("hasIdAttribute", tf != null
                && tf.stream().anyMatch(a -> String.valueOf(idName).equals(a.get("goName"))));

        // Exactly which imports the model file needs. Upstream puts its import
        // block inside {{#responseModel}}, so a resource with no response
        // model got a struct and no imports at all -- and an import Go does
        // not need is as fatal as one it does.
        boolean usesTypes = tf != null && tf.stream()
                .anyMatch(a -> String.valueOf(a.get("terraformType")).startsWith("types."));
        // A nested block's CHILDREN can be JSON too -- a price's seat_tiers is
        // deeper than nestedMaxDepth -- and the parent attribute itself is not
        // flagged isJson, so the child has to be looked at or jsontypes is
        // imported nowhere and the nested struct will not compile.
        boolean usesJson = tf != null && (tf.stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.get("isJson")))
                || tf.stream().flatMap(a -> nestedOf(a).stream())
                        .anyMatch(child -> Boolean.TRUE.equals(child.get("isJson"))));
        // Every nested conversion wraps its error, so fmt is needed wherever one
        // is emitted at all -- and types, because every child is a types.*.
        boolean anyNested = tf != null && tf.stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.get("isNested")));
        boolean request = processed.getOperations().get("requestModel") != null;
        boolean response = processed.getOperations().get("responseModel") != null;

        // json and fmt are needed only where a conversion actually uses them:
        // ToClientModel parses the JSON attributes the request carries,
        // FromClientModel renders the ones the response answers. An import Go
        // does not need is as fatal as one it does.
        boolean toJson = tf != null && tf.stream().anyMatch(a ->
                Boolean.TRUE.equals(a.get("isJson"))
                        && ((request && Boolean.TRUE.equals(a.get("inRequest")))
                            // The UPDATE body's conversion parses JSON too,
                            // and a resource can have one where the create
                            // takes no JSON at all.
                            || (updateModelPresent(processed) && Boolean.TRUE.equals(a.get("inUpdateRequest")))));
        boolean fromJson = response && tf != null && tf.stream().anyMatch(a ->
                Boolean.TRUE.equals(a.get("isJson")) && Boolean.TRUE.equals(a.get("readBack")));

        processed.getOperations().put("usesTypes", usesTypes || anyNested);
        processed.getOperations().put("usesJsontypes", usesJson);
        processed.getOperations().put("usesEncodingJson", toJson || fromJson || usesJson);
        processed.getOperations().put("usesFmt", toJson || anyNested);
        processed.getOperations().put("hasClientModel", request || response);

        Object createModel = processed.getOperations().get("requestModel");
        Object updateModel = processed.getOperations().get("updateRequestModel");
        // Only worth a second conversion when the shapes actually differ.
        processed.getOperations().put("hasSeparateUpdateModel",
                updateModel != null && !updateModel.equals(createModel));

        processed.getOperations().put("idIsString",
                ".ValueString()".equals(processed.getOperations().get("idFieldValueAccessor")));

        return processed;
    }

    /**
     * Upstream builds the resource schema out of the READ response model, and
     * then sends that same model back as the create body. Three things go
     * wrong, and all three are fatal to actually declaring a resource:
     *
     * The response model says every property the server always answers is
     * `required` and nothing is readOnly unless the document says so -- and
     * the WSO2 documents never say so. `wso2_organization` came out demanding
     * `id`, `status`, `version`, `created` and `last_modified` in
     * configuration, values the server assigns.
     *
     * A property the create body takes and the response model does not have is
     * missing from the schema entirely. A tenant's owner carries a `password`,
     * which no response ever echoes back, so there was no way to spell the one
     * field a tenant cannot be created without.
     *
     * And anything that is not a scalar is declared -- an object as a string,
     * a list as `ListAttribute{ElementType: types.StringType}` -- and then
     * converted NEITHER way. The field reached the schema and the model struct
     * and was silently never sent or read, which is where an organization's
     * `attributes` and a tenant's `owners` went.
     *
     * So the create request body is what decides what a person may write, the
     * response decides what is computed, and the schema is the union. Anything
     * not a scalar becomes a JSON string, which the templates do convert.
     */
    private void reshapeAttributes(OperationMap operations, List<ModelMap> allModels) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) operations.get("tfAttributes");

        // Upstream builds this list only when there is a response model, so a
        // create-only endpoint -- POST /channel-verified-tenants answers 201
        // and nothing -- got no schema at all, and an empty model struct. The
        // create body is a schema.
        if (attributes == null) {
            attributes = new ArrayList<>();
            operations.put("tfAttributes", attributes);
        }

        CodegenModel request = modelNamed(allModels, (String) operations.get("requestModel"));
        Map<String, CodegenProperty> writable = new LinkedHashMap<>();

        if (request != null) {
            for (CodegenProperty property : request.vars) {
                writable.put(property.baseName.toLowerCase(Locale.ROOT), property);
            }
        }

        // The UPDATE body is its own shape. ApplicationPatchModel declares
        // neither inboundProtocolConfiguration nor id, so an attribute absent
        // here must not be put in a patch -- the whole body is refused if it is.
        CodegenModel updateRequest =
                modelNamed(allModels, (String) operations.get("updateRequestModel"));
        Set<String> patchable = new HashSet<>();

        if (updateRequest != null) {
            for (CodegenProperty property : updateRequest.vars) {
                patchable.add(property.baseName.toLowerCase(Locale.ROOT));
            }
        }

        Set<String> answered = new HashSet<>();

        for (Map<String, Object> attribute : attributes) {
            String name = String.valueOf(attribute.get("name")).toLowerCase(Locale.ROOT);
            answered.add(name);
            CodegenProperty writes = writable.get(name);

            // With no create operation there is nothing to infer from, and
            // upstream's answer stands.
            if (request != null) {
                // Optional AND Computed where the create body takes it without
                // insisting and the server answers it anyway -- a tenant's
                // `name` is not required and comes back as the domain. Optional
                // alone plans null and then the read answers a value, which is
                // "Provider produced inconsistent result after apply".
                attribute.put("isRequired", writes != null && writes.required);
                attribute.put("isOptional", writes != null && !writes.required);
                attribute.put("isComputed", writes == null || !writes.required);
            }

            // The server answers this one, so state can be refreshed from it --
            // unless the create body spells it differently, as a tenant's
            // owners are `Owner` going out (with a password) and `OwnerResponse`
            // coming back (without). Reading that back would drop the password
            // out of state and diff forever.
            boolean sameShape = writes == null
                    || writes.dataType.equals(String.valueOf(attribute.get("goType")));
            attribute.put("inRequest", writes != null);
            attribute.put("inUpdateRequest", patchable.contains(name));
            attribute.put("readBack", sameShape);

            unpoint(attribute);
            retype(attribute);
        }

        // What the create body takes and no response ever answers.
        for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
            if (answered.contains(entry.getKey())) {
                continue;
            }
            attributes.add(writeOnlyAttribute(entry.getValue()));
        }

        nest(attributes, allModels, writable, String.valueOf(operations.get("resourceClassName")));

        for (Map<String, Object> attribute : attributes) {
            String terraformName = String.valueOf(attribute.get("terraformName"));

            // A tfsdk name must START WITH A LETTER, and RT answers every
            // object's links under `_hyperlinks`: "invalid tfsdk tag, must
            // only use lowercase letters, underscores, and numbers, and must
            // start with a letter", on every apply. The Go field and the JSON
            // key are untouched -- only the name a configuration spells.
            if (!terraformName.isEmpty() && !Character.isLetter(terraformName.charAt(0))) {
                terraformName = terraformName.replaceAll("^[^a-z]+", "");
                attribute.put("terraformName",
                        terraformName.isEmpty() ? "api_field" : terraformName);
            }

            if (RESERVED.contains(terraformName)) {
                // The Go field and the JSON key are untouched; only the name
                // configuration spells it moves out of Terraform's way.
                attribute.put("terraformName", "api_" + terraformName);
            }
        }

        boolean anyJson = attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isJson")))
                || attributes.stream()
                        .filter(attribute -> attribute.get("nested") != null)
                        .flatMap(attribute -> nestedOf(attribute).stream())
                        .anyMatch(child -> Boolean.TRUE.equals(child.get("isJson")));

        // The nested blocks, collected so the model file can declare a struct
        // for each one. Mustache cannot gather them itself.
        List<Map<String, Object>> nestedModels = attributes.stream()
                .filter(attribute -> Boolean.TRUE.equals(attribute.get("isNested")))
                .collect(java.util.stream.Collectors.toList());
        operations.put("nestedModels", nestedModels);
        operations.put("hasNestedModels", !nestedModels.isEmpty());

        // Nothing is a types.List any more, so the import that served them is
        // not needed and the JSON one is.
        operations.put("hasListAttributes", false);
        operations.put("hasJsonAttributes", anyJson);
    }


    /**
     * A nested resource hangs off its parents, and their identifiers are in
     * the path, not in any response body.
     *
     * {@code /tenants/{tenant-id}/owners/{owner-id}} needs the tenant id to
     * address an owner at all, so it becomes a Required attribute of the
     * resource -- nothing else can supply it. Upstream's templates interpolate
     * exactly ONE argument into the path format, which is why a nested path
     * came out as {@code %!v(MISSING)}; here each operation carries the whole
     * ordered argument list, receiver included, so the template just spreads
     * it.
     */
    private void wirePathParams(OperationMap operations, List<CodegenOperation> group) {
        // Whichever operation spells the longest path addresses the resource:
        // the member path in a collection, or the single path of a singleton.
        // Asking only the read (or the delete) left
        // /tenants/{tenant-id}/lifecycle-status -- a PUT and nothing else --
        // with no path arguments at all.
        CodegenOperation addressing = group.stream()
                .max((a, b) -> Integer.compare(a.path.length(), b.path.length()))
                .orElse(null);

        if (addressing == null) {
            return;
        }

        List<String> names = paramsOf(addressing.path);

        if (names.isEmpty()) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) operations.get("tfAttributes");

        // No response model means no attribute list to add parents to -- but
        // the path arguments below are still what addresses the resource.
        if (attributes == null) {
            attributes = new ArrayList<>();
            operations.put("tfAttributes", attributes);
        }

        // Every param but the last: the last one IS this resource's own id,
        // which upstream already resolved against the response model.
        for (String name : names.subList(0, names.size() - 1)) {
            String terraformName = underscore(name.replace('-', '_')).toLowerCase(Locale.ROOT);

            if (attributes.stream().anyMatch(a -> terraformName.equals(a.get("terraformName")))) {
                continue;
            }

            Map<String, Object> parent = new HashMap<>();
            parent.put("name", name);
            parent.put("terraformName", terraformName);
            parent.put("goName", camelize(terraformName));
            parent.put("goType", "string");
            parent.put("terraformType", "types.String");
            parent.put("terraformAttrType", "schema.StringAttribute");
            parent.put("isString", true);
            parent.put("isRequired", true);
            parent.put("isOptional", false);
            parent.put("isComputed", false);
            parent.put("isSensitive", false);
            // It addresses the resource; it is not part of any body, and no
            // response answers it.
            parent.put("inRequest", false);
            parent.put("readBack", false);
            parent.put("description", "Identifier of the parent " + name.replace("-id", "") + ".");
            attributes.add(parent);
        }

        // The resource's OWN identifier, when no response body carries it:
        // /applications/loginflow/status/{operation_id} is addressed by a value
        // that only the caller knows, and upstream looked for it among the
        // response model's properties and found nothing.
        final String guessedId = String.valueOf(operations.get("idFieldExported"));
        boolean creatable = operationFlagged(group, "x-terraform-is-create") != null;

        if (attributes.stream().noneMatch(a -> guessedId.equals(a.get("goName")))) {
            Map<String, Object> id = new HashMap<>();
            String last = names.get(names.size() - 1);
            String terraformName = underscore(last.replace('-', '_')).toLowerCase(Locale.ROOT);

            // Upstream guessed "id" off the read response and there is none, so
            // the identifier is what the path actually calls it.
            String ownId = camelize(terraformName);
            operations.put("idFieldExported", ownId);
            operations.put("idFieldTerraformName", terraformName);
            operations.put("idFieldValueAccessor", ".ValueString()");

            id.put("name", last);
            id.put("terraformName", terraformName);
            id.put("goName", ownId);
            id.put("goType", "string");
            id.put("terraformType", "types.String");
            id.put("terraformAttrType", "schema.StringAttribute");
            id.put("isString", true);
            // With no create there is nobody to assign it, so it is asked for.
            id.put("isRequired", !creatable);
            id.put("isOptional", creatable);
            id.put("isComputed", creatable);
            id.put("isSensitive", false);
            id.put("inRequest", false);
            id.put("readBack", false);
            id.put("description", "Identifier of the " + last.replace("-id", "") + ".");
            attributes.add(id);
        }

        // One accessor per param name, then each operation spreads the params
        // ITS OWN path spells. Slicing the last one off as "the id" was wrong
        // for a singleton, whose path is the whole address --
        // .../oidc/revoke got zero arguments for one %v.
        Map<String, String> accessors = new LinkedHashMap<>();

        for (String name : names) {
            boolean own = name.equals(names.get(names.size() - 1));
            String goName = own
                    ? String.valueOf(operations.get("idFieldExported"))
                    : camelize(underscore(name.replace('-', '_')).toLowerCase(Locale.ROOT));
            String accessor = own
                    ? String.valueOf(operations.get("idFieldValueAccessor"))
                    : ".ValueString()";
            accessors.put(name, goName + accessor);
        }

        argsOf(operations, group, "x-terraform-is-create", "createArgs", "plan", accessors);
        argsOf(operations, group, "x-terraform-is-read", "readArgs", "state", accessors);
        argsOf(operations, group, "x-terraform-is-read", "configArgs", "config", accessors);
        // The create reads the new resource back, and there it is `plan` that
        // holds the identifier the Location header just supplied.
        argsOf(operations, group, "x-terraform-is-read", "readArgsPlan", "plan", accessors);
        // From STATE, not the plan: an identifier cannot change on an update,
        // and the plan's copy of a Computed one is unknown.
        argsOf(operations, group, "x-terraform-is-update", "updateArgs", "state", accessors);
        argsOf(operations, group, "x-terraform-is-delete", "deleteArgs", "state", accessors);

        // A nested resource cannot be imported by its own id alone: nothing
        // else tells Terraform which tenant an owner belongs to, and the
        // update would then PUT to /tenants//owners/<id>.
        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder hint = new StringBuilder();

        for (int i = 0; i < names.size(); i++) {
            // The last one is this resource's OWN identifier, and the schema
            // may well call it something other than the path does: the owner
            // of /tenants/{tenant-id}/owners/{owner-id} has an `id` property,
            // so the attribute is `id` and setting `owner_id` on import fails.
            String terraformName = i == names.size() - 1
                    ? String.valueOf(operations.get("idFieldTerraformName"))
                    : underscore(names.get(i).replace('-', '_')).toLowerCase(Locale.ROOT);
            Map<String, Object> part = new HashMap<>();

            part.put("terraformName", terraformName);
            part.put("index", i);
            parts.add(part);

            if (hint.length() > 0) {
                hint.append('/');
            }
            hint.append('<').append(terraformName).append('>');
        }

        if (names.size() > 1) {
            operations.put("importParts", parts);
            operations.put("importPartCount", names.size());
            operations.put("importHint", hint.toString());
        }

        CodegenOperation create = operationFlagged(group, "x-terraform-is-create");
        if (create != null) {
            operations.put("createPath", String
                    .valueOf(create.vendorExtensions.getOrDefault("x-terraform-path-fmt", create.path))
                    .replaceAll("\\{[^}]*\\}", "%v"));
            operations.put("createHasPathParams", !paramsOf(create.path).isEmpty());
        }
    }

    /** The arguments one operation's own path needs, receiver baked in. */
    private void argsOf(OperationMap operations, List<CodegenOperation> group, String flag,
                        String key, String receiver, Map<String, String> accessors) {
        CodegenOperation op = operationFlagged(group, flag);

        if (op == null) {
            return;
        }

        List<Map<String, Object>> args = new ArrayList<>();

        for (String name : paramsOf(op.path)) {
            String accessor = accessors.get(name);

            if (accessor == null) {
                continue;
            }
            Map<String, Object> arg = new HashMap<>();
            arg.put("expr", receiver + "." + accessor);
            args.add(arg);
        }

        operations.put(key, args);
    }

    private CodegenOperation operationFlagged(List<CodegenOperation> group, String flag) {
        return group.stream()
                .filter(op -> Boolean.TRUE.equals(op.vendorExtensions.get(flag)))
                .findFirst()
                .orElse(null);
    }

    /** The {param} names of a path, in the order the path spells them. */
    private List<String> paramsOf(String path) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PARAM.matcher(path);

        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        return names;
    }

    /**
     * A NESTED OBJECT IS A TERRAFORM BLOCK, not a string holding JSON.
     *
     * This is what `nestedAttributes` buys. Upstream renders any object or
     * array of objects as one opaque attribute, so a product's prices were
     * written as
     *
     *   prices = jsonencode([{ amount_type = "fixed", price_amount = 9795 }])
     *
     * and nothing checked a field name, a type or a missing required value
     * until Polar answered 422. Expanded, the same thing is
     *
     *   prices = [{ amount_type = "fixed", price_amount = 9795 }]
     *
     * and the schema refuses a typo before a request is made.
     *
     * RUN AFTER the attributes are built, not during: the Go type the client
     * carries -- `[]ProductPriceFixedCreate` -- is what names the model whose
     * properties become the block's attributes, and that is only set by then.
     *
     * A CHILD TOO DEEP IS JSON INSIDE A TYPED PARENT. A price's `seat_tiers` is
     * an object of its own, and at depth one it stays JSON -- but it does not
     * disqualify `prices`, which was the first version of this and threw away
     * the whole block for one field in a variant nobody here uses.
     *
     * CHILDREN ARE Optional AND Computed, except where the document requires
     * them. Polar fills in a price's `price_currency` and `tax_behavior` when
     * the configuration omits them, and an Optional-only attribute that the
     * server answers is "Provider produced inconsistent result after apply" on
     * every single apply. Optional means a configuration may say it; Computed
     * means the server may.
     */
    private void nest(List<Map<String, Object>> attributes, List<ModelMap> allModels,
                      Map<String, CodegenProperty> writable, String resourceClassName) {
        if (!nestedAttributes || nestedMaxDepth < 1) {
            return;
        }

        for (Map<String, Object> attribute : attributes) {
            if (!Boolean.TRUE.equals(attribute.get("isJson"))) {
                continue;
            }

            String terraformName = String.valueOf(attribute.get("terraformName"));
            if (jsonAttributes.contains(terraformName)) {
                continue;
            }

            // THE SHAPE A CONFIGURATION WRITES IS THE REQUEST'S, not the
            // response's, and Polar's two often differ: a customer's
            // `billing_address` goes out as an AddressInput and comes back as
            // an Address, and a checkout link's `products` goes out as a list
            // of ids and comes back as a list of objects. The attribute's own
            // goType is the RESPONSE type, because that is what the schema was
            // built from -- so nesting on it produced a block whose
            // ToClientModel assigned an Address into an AddressInput field.
            String responseGo = String.valueOf(attribute.get("goType"));
            CodegenProperty writes = writable.get(String.valueOf(attribute.get("name"))
                    .toLowerCase(Locale.ROOT));
            String go = writes != null ? writes.dataType : responseGo;

            boolean list = go.startsWith("[]");
            // WHETHER THE CLIENT FIELD IS A POINTER, which decides how the
            // conversion assigns it. `*BenefitMeterCreditCreateProperties`
            // takes the converted pointer; a plain struct field takes what it
            // points at. Getting this from the shape rather than guessing is
            // the difference between compiling and "cannot use *converted
            // (variable of struct type X) as *X value in assignment".
            boolean pointer = go.replace("[]", "").startsWith("*");
            String bare = go.replace("[]", "").replace("*", "");

            CodegenModel model = modelNamed(allModels, bare);
            if (model == null || model.vars == null || model.vars.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> children = new ArrayList<>();
            for (CodegenProperty property : model.vars) {
                children.add(childAttribute(property));
            }

            // Mustache cannot ask whether it is on the last item, and a Go
            // composite literal tolerates a trailing comma, so nothing needs a
            // separator here -- but the struct does need to know it has any.
            if (children.isEmpty()) {
                continue;
            }

            String nestedModel = resourceClassName + camelize(attribute.get("goName").toString()) + "Model";

            attribute.put("isJson", false);
            attribute.put("isNested", true);
            attribute.put("isNestedList", list);
            attribute.put("isNestedObject", !list);
            attribute.put("nested", children);
            attribute.put("nestedModel", nestedModel);
            attribute.put("nestedClientType", bare);
            attribute.put("nestedPointer", pointer);

            // READ BACK ONLY WHERE THE TWO SHAPES ARE THE SAME ONE. Where they
            // differ there is nothing to convert the answer into -- the nested
            // model is the request's -- so the attribute is not refreshed,
            // exactly as a scalar whose request and response spellings diverge
            // is not. And then it must not be Computed either: a Computed
            // attribute nothing assigns stays unknown past the apply, which is
            // "provider returned invalid result object after apply".
            boolean readBack = go.equals(responseGo) && Boolean.TRUE.equals(attribute.get("readBack"));
            attribute.put("readBack", readBack);
            attribute.put("isComputed", readBack);
            attribute.put("terraformType", list ? "[]" + nestedModel : "*" + nestedModel);
            attribute.put("terraformAttrType",
                    list ? "schema.ListNestedAttribute" : "schema.SingleNestedAttribute");
            // A nested block is never Required as a whole here: which half of a
            // flattened union applies depends on the discriminator, and a
            // Required block on a product with no prices cannot be satisfied.
            attribute.put("isRequired", false);
            attribute.put("isOptional", true);
        }
    }

    /**
     * One attribute inside a nested block. The same shaping the top level gets
     * -- a pointer to a scalar is that scalar -- and
     * then anything still composite is JSON, because this is depth one and
     * there is nowhere further to go.
     */
    private Map<String, Object> childAttribute(CodegenProperty property) {
        Map<String, Object> attribute = new HashMap<>();

        attribute.put("name", property.baseName);
        attribute.put("terraformName", underscore(property.baseName).toLowerCase(Locale.ROOT));
        attribute.put("goName", camelize(property.baseName));
        attribute.put("goType", property.dataType);
        attribute.put("description", property.description != null
                ? property.description.replace("\"", "'").replace("\n", " ")
                : "");
        attribute.put("isRequired", property.required);
        // Everything the document does not require, the server may still
        // answer -- see the note on nest().
        attribute.put("isOptional", !property.required);
        attribute.put("isComputed", !property.required);
        attribute.put("isString", "string".equals(property.dataType));
        attribute.put("isInt64", "int64".equals(property.dataType) || "int32".equals(property.dataType));
        attribute.put("isFloat64", "float64".equals(property.dataType) || "float32".equals(property.dataType));
        attribute.put("isBool", "bool".equals(property.dataType));
        attribute.put("isList", false);
        attribute.put("isObject", false);
        attribute.put("isSensitive", false);
        attribute.put("terraformType", goType(property.dataType));
        attribute.put("terraformAttrType", goAttrType(property.dataType));

        unpoint(attribute);
        retype(attribute);

        return attribute;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nestedOf(Map<String, Object> attribute) {
        Object nested = attribute.get("nested");
        return nested == null ? new ArrayList<>() : (List<Map<String, Object>>) nested;
    }

    /**
     * The word that precedes the token in an Authorization header, read from
     * {@code components.securitySchemes}. An HTTP security scheme names it
     * (`bearer`, `basic`); for anything else the token is sent as the whole
     * header value verbatim, which is the only honest answer for a scheme this
     * client does not spell.
     */
    private String bearerPrefix() {
        if (openAPI == null || openAPI.getComponents() == null
                || openAPI.getComponents().getSecuritySchemes() == null) {
            return "Bearer";
        }

        return openAPI.getComponents().getSecuritySchemes().values().stream()
                .filter(scheme -> scheme != null && scheme.getScheme() != null)
                .map(scheme -> scheme.getScheme().toLowerCase(Locale.ROOT))
                .filter(scheme -> scheme.equals("bearer") || scheme.equals("basic"))
                .findFirst()
                .map(scheme -> scheme.substring(0, 1).toUpperCase(Locale.ROOT) + scheme.substring(1))
                .orElse("Bearer");
    }

    /**
     * A pointer to a scalar is still that scalar.
     *
     * Upstream decides an attribute's Terraform type by matching the Go type
     * name, so making bools pointers -- which is what sends an explicit false
     * -- turned every one of them into a types.String that no conversion
     * branch touched, and the attribute was simply never assigned: "provider
     * still indicated an unknown value for application_enabled".
     */
    private void unpoint(Map<String, Object> attribute) {
        String goType = String.valueOf(attribute.get("goType"));

        if (!goType.startsWith("*")) {
            return;
        }

        String pointed = goType.substring(1);
        boolean isBool = "bool".equals(pointed);
        boolean isInt = "int".equals(pointed) || "int32".equals(pointed) || "int64".equals(pointed);
        boolean isFloat = "float32".equals(pointed) || "float64".equals(pointed);
        boolean isString = "string".equals(pointed);

        if (!isBool && !isInt && !isFloat && !isString) {
            return;
        }

        attribute.put("isBool", isBool);
        attribute.put("isInt64", isInt);
        attribute.put("isFloat64", isFloat);
        attribute.put("isString", isString);
        attribute.put("terraformType", goType(pointed));
        attribute.put("terraformAttrType", goAttrType(pointed));
    }

    /** A list or an object travels as JSON; the templates convert those. */
    private void retype(Map<String, Object> attribute) {
        String go = String.valueOf(attribute.get("goType"));

        // A Go type that is not a named one either: an `interface{}` -- which
        // is what a union of shapes comes out as -- or a bare map or slice.
        // Left alone, none of the attribute's type flags is set and the
        // templates emit nothing at all for it, including the assignment that
        // has to resolve a Computed attribute's UNKNOWN: "syntax error,
        // unexpected }".
        boolean freeform = go.startsWith("interface{") || go.startsWith("map[")
                || go.startsWith("[]");

        if (!freeform && !Boolean.TRUE.equals(attribute.get("isList"))
                && !Boolean.TRUE.equals(attribute.get("isObject"))) {
            return;
        }

        // A DECLARED SCALAR is a scalar whatever the composition around it
        // said: RT's EmailAddress is `type: string` with an anyOf that only
        // narrows it, so the property is flagged a model and the Go field is
        // a string. Declaring that one jsontypes.Normalized gave a model
        // struct the conversions could not assign to.
        String scalar = go.startsWith("*") ? go.substring(1) : go;
        if (Arrays.asList("string", "bool", "int", "int32", "int64", "float32", "float64")
                .contains(scalar)) {
            attribute.put("isList", false);
            attribute.put("isObject", false);
            return;
        }

        attribute.put("isList", false);
        attribute.put("isObject", false);
        attribute.put("isJson", true);
        attribute.put("terraformType", "jsontypes.Normalized");
        attribute.put("terraformAttrType", "schema.StringAttribute");
    }

    private Map<String, Object> writeOnlyAttribute(CodegenProperty property) {
        Map<String, Object> attribute = new HashMap<>();

        attribute.put("name", property.baseName);
        attribute.put("terraformName", underscore(property.baseName).toLowerCase(Locale.ROOT));
        attribute.put("goName", camelize(property.baseName));
        attribute.put("goType", property.dataType);
        attribute.put("description", property.description != null ? property.description : "");
        attribute.put("isRequired", property.required);
        attribute.put("isOptional", !property.required);
        attribute.put("isComputed", false);
        attribute.put("inRequest", true);
        attribute.put("inUpdateRequest", false);
        // Nothing answers it, so there is nothing to read back.
        attribute.put("readBack", false);
        attribute.put("isString", "string".equals(property.dataType));
        attribute.put("isInt64", "int64".equals(property.dataType) || "int32".equals(property.dataType));
        attribute.put("isFloat64", "float64".equals(property.dataType) || "float32".equals(property.dataType));
        attribute.put("isBool", "bool".equals(property.dataType));
        attribute.put("isList", property.isArray);
        attribute.put("isObject", property.isModel && !property.isArray);
        // ponytail: a name-based guess at what is secret. A document that says
        // writeOnly or x-terraform-sensitive is believed first; this catches the
        // ones that say neither, and WSO2's tenant owner password says neither.
        attribute.put("isSensitive", property.isWriteOnly
                || property.baseName.toLowerCase(Locale.ROOT).contains("password")
                || property.baseName.toLowerCase(Locale.ROOT).contains("secret"));
        attribute.put("terraformType", goType(property.dataType));
        attribute.put("terraformAttrType", goAttrType(property.dataType));

        unpoint(attribute);
        retype(attribute);

        return attribute;
    }

    /** A composition with branches that disagree, not one that only narrows. */
    private boolean isGenuineUnion(CodegenProperty property) {
        if (property.getComposedSchemas() == null) {
            return false;
        }

        List<CodegenProperty> anyOf = property.getComposedSchemas().getAnyOf();
        List<CodegenProperty> oneOf = property.getComposedSchemas().getOneOf();

        return (anyOf != null && anyOf.size() > 1) || (oneOf != null && oneOf.size() > 1);
    }

    /** The declared type behind a composition, or {@code interface{}}. */
    private String unionType(CodegenProperty property) {
        if (property.isString) {
            return "string";
        }
        if (property.isInteger || property.isLong) {
            return "int64";
        }
        if (property.isNumber || property.isFloat || property.isDouble) {
            return "float64";
        }
        if (property.isBoolean) {
            return "bool";
        }
        return "interface{}";
    }

    private String goType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int32": case "int64": case "int": return "types.Int64";
            case "float32": case "float64": return "types.Float64";
            case "bool": return "types.Bool";
            default: return "types.String";
        }
    }

    private String goAttrType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int32": case "int64": case "int": return "schema.Int64Attribute";
            case "float32": case "float64": return "schema.Float64Attribute";
            case "bool": return "schema.BoolAttribute";
            default: return "schema.StringAttribute";
        }
    }

    /** Whether a separate update body will be converted at all. */
    private boolean updateModelPresent(OperationsMap processed) {
        Object update = processed.getOperations().get("updateRequestModel");
        Object create = processed.getOperations().get("requestModel");
        return update != null && !update.equals(create);
    }

    private CodegenModel modelNamed(List<ModelMap> allModels, String classname) {
        if (classname == null) {
            return null;
        }
        for (ModelMap map : allModels) {
            if (classname.equals(map.getModel().classname)) {
                return map.getModel();
            }
        }
        return null;
    }

    /**
     * Every field omitempty, because the resource sends the RESPONSE model as a
     * create body. A property the server assigns is Computed and therefore null
     * in the plan, which reaches Go as a zero value -- and without omitempty
     * that goes out as `"status": ""`, a field the create endpoint never asked
     * for and can refuse over.
     */
    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        ModelsMap processed = super.postProcessModels(objs);

        for (ModelMap map : processed.getModels()) {
            for (CodegenProperty property : map.getModel().vars) {
                // OpenAPI 3.1 lets a schema carry a type AND an `anyOf` that
                // only narrows it: RT's EmailAddress is `type: string` with
                // an anyOf of {format: email, maxLength: 0}, meaning "an
                // address, or empty". openapi-generator names that
                // composition `AnyOf` and emits it as the Go type, which is
                // not a type and does not compile. The declared type is still
                // on the property, so it is used; a genuine union -- one that
                // declares no type of its own -- travels as JSON.
                if (property.dataType != null
                        && (property.dataType.startsWith("AnyOf") || property.dataType.startsWith("OneOf"))) {
                    property.dataType = unionType(property);
                } else if (isGenuineUnion(property)) {
                    // A composition with BRANCHES, as opposed to the
                    // validation-only kind above. openapi-generator collapses
                    // one to whichever branch it saw last, and the client then
                    // fails to parse half the responses the API sends, so a
                    // genuine union travels as JSON instead.
                    //
                    // NOTHING COERCES AN IDENTIFIER HERE. Polar types every id
                    // as a string with `format: uuid4`, consistently, so an id
                    // is whatever the document says it is. The generator this
                    // one descends from forced every `id` to a bespoke type
                    // because Request Tracker answered a number in one response
                    // and a quoted string in the next; that is a fact about RT
                    // and has no business in a Polar client.
                    property.dataType = "interface{}";
                }

                // A nested object is a POINTER, because Go's `omitempty` does
                // nothing for a struct: an unset one still goes out as
                // "associatedRoles":{"allowedAudience":""}, and WSO2 answers
                // UE-10000, "provided request body content is not in the
                // expected format" -- the empty string is not one of the
                // audiences its enum allows. A nil pointer is simply absent.
                // complexType, not isModel: a property written as
                // `allOf: [$ref: FapiProfile]` -- which is how this document
                // attaches a description to a ref -- is not flagged a model,
                // and went out as "fapiProfile":{} regardless.
                // A BOOL IS A POINTER TOO, for the same reason and a worse
                // consequence: `omitempty` cannot tell false from unset, so
                // `supportPlainTransformAlgorithm = false` was dropped from
                // the body -- and WSO2 answers a MISSING one with a 500,
                // APP-65006 "server encountered an unexpected error". Sending
                // it explicitly is a 201. Verified against the server both
                // ways.
                if ("bool".equals(property.dataType)) {
                    property.dataType = "*bool";
                }

                if ((property.isModel || property.complexType != null)
                        && !property.isArray && !property.isMap
                        && !"interface{}".equals(property.dataType)
                        && !"string".equals(property.dataType)
                        && !"int64".equals(property.dataType)
                        && !property.dataType.startsWith("*")) {
                    property.dataType = "*" + property.dataType;
                }

                property.vendorExtensions.put("x-go-datatag",
                        " `json:\"" + property.baseName + ",omitempty\"`");
            }
        }

        return processed;
    }

    /**
     * A named schema that is not an object is not a struct.
     *
     * RT's {@code perlBoolean} is a string enum -- "1" or "0", because RT
     * does not translate perl's booleans -- and {@code ticketLink} is an
     * anyOf of an integer and an array. openapi-generator gives each of them
     * a model of its own, and the template renders a model as a struct, so
     * both came out as {@code type PerlBoolean struct{}}. A queue's
     * {@code Disabled} was then a field of that type: nothing could convert
     * it, so the attribute stayed UNKNOWN through an apply -- "provider
     * returned invalid result object after apply".
     *
     * A model with no properties at all is whatever it actually is: the
     * scalar an enum enumerates, or {@code interface{}} for a union of
     * shapes, which travels as JSON.
     *
     * This runs over ALL models, because a property cannot see the model its
     * type names.
     */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> models) {
        Map<String, ModelsMap> processed = super.postProcessAllModels(models);

        Map<String, String> notStructs = new LinkedHashMap<>();

        for (ModelsMap entry : processed.values()) {
            for (ModelMap map : entry.getModels()) {
                CodegenModel model = map.getModel();
                boolean empty = (model.vars == null || model.vars.isEmpty())
                        && (model.allVars == null || model.allVars.isEmpty());

                if (!empty) {
                    continue;
                }

                notStructs.put(model.classname, scalarOf(model));
            }
        }

        for (ModelsMap entry : processed.values()) {
            for (ModelMap map : entry.getModels()) {
                CodegenModel model = map.getModel();
                List<CodegenProperty> properties = new ArrayList<>(model.vars);

                if (model.allVars != null) {
                    properties.addAll(model.allVars);
                }

                for (CodegenProperty property : properties) {
                    String named = property.dataType == null ? "" : property.dataType;
                    boolean pointed = named.startsWith("*");
                    String bare = pointed ? named.substring(1) : named;
                    String scalar = notStructs.get(bare);

                    if (scalar != null) {
                        property.dataType = scalar;
                        property.isModel = false;
                    }
                }
            }
        }

        return processed;
    }

    /** What a model with no properties of its own actually is. */
    private String scalarOf(CodegenModel model) {
        if (model.isString || "string".equals(model.dataType)) {
            return "string";
        }
        if (model.isInteger || model.isLong) {
            return "int64";
        }
        if (model.isNumber || model.isFloat || model.isDouble) {
            return "float64";
        }
        if (model.isBoolean) {
            return "bool";
        }
        // A union of shapes -- ticketLink is an integer or an array -- has
        // nothing better, and travels as JSON.
        return "interface{}";
    }

    /**
     * The whole path names the resource, not just its last segment.
     *
     * {@code /organizations/{organization-id}/applications/{application-id}/share}
     * and {@code /applications/{applicationId}/share} both end in "share", so
     * naming by the last segment gave both the same file and the second one
     * SILENTLY overwrote the first -- a resource that vanished with no error
     * anywhere. Every literal segment, singularised, is unambiguous:
     * organization_application_share and application_share.
     *
     * {@code /tenants/{tenant-id}/owners} -> {@code TenantOwner}.
     */
    @Override
    public String toApiName(String name) {
        StringBuilder parts = new StringBuilder();

        String[] segments = trim(name).split("/");

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }

            // Singular means "one of these". A literal followed by a parameter
            // names one -- /applications/{applicationId}/share is a share of ONE
            // application -- while /applications/share acts on the collection.
            // Both end in "share", and without this they were one name, one
            // file, and one of the two resources silently gone.
            boolean names1 = i == segments.length - 1
                    || (i + 1 < segments.length && segments[i + 1].startsWith("{"));
            String spelled = names1 ? singular(segment) : segment;

            if (parts.length() > 0) {
                parts.append('_');
            }
            parts.append(underscore(spelled.replace('-', '_')).toLowerCase(Locale.ROOT));
        }

        return camelize(parts.toString());
    }

    private String trim(String path) {
        String trimmed = path;

        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    @Override
    public String toApiFilename(String name) {
        return underscore(toApiName(name));
    }

    /** A trailing {@code /{param}} is the member of a collection, not a collection. */
    private String collectionOf(String raw) {
        // /applications/{applicationId}/inbound-protocols/ and
        // /applications/{applicationId}/inbound-protocols are the same
        // collection, and the document spells both.
        String path = raw.length() > 1 && raw.endsWith("/")
                ? raw.substring(0, raw.length() - 1)
                : raw;
        int cut = path.lastIndexOf('/');

        if (cut > 0 && path.endsWith("}") && path.startsWith("{", cut + 1)) {
            return path.substring(0, cut);
        }
        return path;
    }

    /** The member of THIS collection: its path plus one trailing {param}. */
    private boolean isMember(String collection, String path) {
        return collectionOf(path).equals(collection) && !path.equals(collection);
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /**
     * A trailing "s" is not always a plural: "lifecycle-status" became
     * "lifecycle_statu" and "passive-sts" became "passive_st". Latin endings
     * and that one acronym are left alone; "tenants" and "secrets" are still
     * plurals and still lose it.
     *
     * ponytail: not an inflector. A document saying "addresses" or "people"
     * wants a real one.
     */
    /**
     * Terraform reserves these at the root of a resource block, and a schema
     * using one is refused outright: "count is a reserved root attribute/block
     * name". WSO2's list responses carry a `count`.
     */
    private static final List<String> RESERVED =
            Arrays.asList("count", "for_each", "depends_on", "provider", "lifecycle", "id_");

    private static final List<String> KEEP = Arrays.asList("ss", "us", "is", "os", "sts");

    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (!lower.endsWith("s") || KEEP.stream().anyMatch(lower::endsWith)) {
            return name;
        }
        return name.substring(0, name.length() - 1);
    }
}
