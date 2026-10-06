package polar;

import io.swagger.v3.oas.models.OpenAPI;
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
 * WHAT IT HAS TO PRODUCE is the provider that already exists by hand
 * (sjkchang/terraform-provider-polar), resource for resource and attribute for
 * attribute -- its own test suite is copied into test/ unchanged and is the
 * definition of correct. Nothing here is a judgement about how a Polar provider
 * ought to look; every question of shape is answered by that suite.
 *
 * Upstream keys its operation map on the tag and asks
 * {@code CodegenOperation.isRestfulCreate()} which operation is the create.
 * That cannot answer for Polar's document: the operations of one resource are
 * spread across tags, and the helpers assume a create on a path with no
 * parameters. So the shape of the PATH decides instead -- a collection and its
 * member path are one resource -- and a create is told from a list by what it
 * answers: 201, not 200.
 */
public class TerraformCodegen extends TerraformProviderCodegen {

    private static final Pattern PARAM = Pattern.compile("\\{([^{}/]+)\\}");

    /**
     * THE CONFIGURATION THIS GENERATOR TAKES, all of it, and all of it written
     * by bin/generate-config rather than by hand -- derive has read the document and
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
    /** Named schema -> the scalar it is, from the config's `scalars`. */
    private final Map<String, String> namedScalars = new LinkedHashMap<>();

    /** Named schema -> the schema it is only another name for. */
    private final Map<String, String> aliases = new LinkedHashMap<>();

    private boolean nestedAttributes = true;
    private int nestedMaxDepth = 3;
    private final Set<String> jsonAttributes = new HashSet<>();

    /**
     * Property names no resource exposes, at the top level or inside a block.
     *
     * The document marks nothing readOnly, so every response-only field would
     * otherwise reach the schema -- and an attribute nobody can write is noise in
     * `tofu plan`, not a fact about the resource.
     */
    private final Set<String> droppedAttributes = new HashSet<>();

    /**
     * Property names that are Optional AND Computed wherever they appear.
     *
     * The one combination the document never asks for and a server default does:
     * a configuration may leave it out, and the server then fills it in. Optional
     * alone plans null and the read answers a value, which is "Provider produced
     * inconsistent result after apply" on every apply.
     */
    private final Set<String> computedAttributes = new HashSet<>();

    /** Property names that are Sensitive wherever they appear. */
    private final Set<String> sensitiveAttributes = new HashSet<>();

    /**
     * Terraform resource name -> what this one does with each of its attributes.
     *
     * The hand-written provider's schema, resource by resource. Everything here
     * is a judgement the document cannot settle: which attribute a resource does
     * not expose, which one it derives, which one is only its update body that
     * can write, and what a configuration is to call it.
     */
    private final Map<String, Map<String, Object>> byResource = new LinkedHashMap<>();

    private static final String REF_PREFIX = "#/components/schemas/";

    public TerraformCodegen() {
        super();
    }

    /**
     * NOT BEFORE THE CONFIG IS APPLIED. DefaultGenerator lifts inline schemas
     * into components before it calls preprocessOpenAPI, which left every pass
     * here looking at `$ref: Value` where the config -- and Polar's document --
     * say `CustomFieldSelectOption.value`. The generator runs the same pass
     * itself, at the end of preprocessOpenAPI.
     */
    @Override
    public boolean getUseInlineModelResolver() {
        return false;
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

        // nestedMaxDepth and the json-only positions arrive with the rest of the
        // configuration in preprocessOpenAPI: there is one file that says what
        // this provider is, not a config file and a command line.

        // THE AUTH SCHEME IS THE DOCUMENT'S, not a guess. Polar declares every
        // token scheme as `type: http, scheme: bearer`, so the header is
        // `Authorization: Bearer <token>` and not the literal word `token`, which
        // a Bearer API answers 401 to with nothing useful in the body.
        additionalProperties().put("authHeaderPrefix", bearerPrefix());


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

    // ================= THE DOCUMENT, AS THE CONFIG SAYS IT SHOULD BE =========
    //
    // Everything below happens before a single model or operation is built, and
    // every decision in it comes out of reference/generator-config.json rather
    // than out of this file. bin/generate-config computes that from the vendored
    // description; generators/polar/config.schema.json is the contract between
    // the two. The generator is TOLD; it does not infer.

    /** The config, read once in preprocessOpenAPI. */
    private Map<String, Object> config = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) {
        Object value = config.get(name);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Map<String, Object> from, String key) {
        Object value = from.get(key);
        return value instanceof List ? (List<String>) value : new ArrayList<>();
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);
        loadConfig();

        if (config.isEmpty()) {
            return;
        }

        liftVersionPrefix(openAPI);
        dropPathsNotConfigured(openAPI);
        dropOperationsNoResourceCalls(openAPI);
        dropNullTypedProperties(openAPI);
        collapseNullableUnions(openAPI);
        applyUnions(openAPI);
        inlineAliases(openAPI);
        inlineNamedScalars(openAPI);
        pruneUnreachableSchemas(openAPI);

        // AND NOW UPSTREAM'S LIFTING, with the document saying what the config
        // says it says. See InlineModels.
        org.openapitools.codegen.InlineModels.flatten(openAPI);
    }

    /**
     * reference/generator-config.json, named by `--additional-properties
     * polarConfig=...`. A missing or unreadable config is fatal: generating from
     * the raw document would silently produce a different provider, and a
     * provider that is silently different is worse than a build that stops.
     */
    private void loadConfig() {
        Object path = additionalProperties().get("polarConfig");
        if (path == null) {
            throw new RuntimeException(
                    "polarConfig is not set -- pass --additional-properties polarConfig=<file>");
        }

        File file = new File(String.valueOf(path));
        try {
            config = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(file, LinkedHashMap.class);
        } catch (Exception e) {
            throw new RuntimeException("cannot read " + file + ": " + e.getMessage(), e);
        }

        Object version = config.get("version");
        if (!Integer.valueOf(1).equals(version)) {
            throw new RuntimeException("generator-config version " + version
                    + " is not one this generator understands (expects 1)");
        }

        // The provider's own identity comes from the config too, so there is one
        // place that says what this provider is called and where it is served.
        // `token` and `environment` are objects and are flattened below, one
        // key at a time -- stringifying them here would leave a `providerToken`
        // behind holding a Java map's toString.
        Map<String, Object> provider = section("provider");
        provider.forEach((key, value) -> {
            if ("token".equals(key) || "environment".equals(key)) {
                return;
            }
            additionalProperties().put(
                    "provider" + key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1),
                    String.valueOf(value));
        });
        additionalProperties().put("providerName", String.valueOf(provider.get("name")));

        // NAMED SCHEMAS THAT ARE NOT OBJECTS, stated rather than worked out. Where
        // the generator had to guess this it fell back to interface{}, which is
        // how a list of a string enum became a string holding JSON.
        section("scalars").forEach((name, type) -> namedScalars.put(name, String.valueOf(type)));
        section("aliases").forEach((name, target) -> aliases.put(name, String.valueOf(target)));

        Map<String, Object> attributes = section("attributes");
        if (attributes.get("nestedMaxDepth") != null) {
            nestedMaxDepth = Integer.parseInt(String.valueOf(attributes.get("nestedMaxDepth")));
        }
        jsonAttributes.addAll(strings(attributes, "json"));
        droppedAttributes.addAll(strings(attributes, "drop"));
        computedAttributes.addAll(strings(attributes, "computed"));
        sensitiveAttributes.addAll(strings(attributes, "sensitive"));

        // The provider's CREDENTIAL and the ENVIRONMENTS it can point at, which
        // the templates render rather than the templates deciding. The
        // environments are the document's own servers, each carrying the id a
        // configuration spells; the attribute name and the variable are ours.
        for (String key : new String[] {"token", "environment"}) {
            Map<String, Object> part = map(provider.get(key));
            part.forEach((name, value) -> additionalProperties().put(
                    "provider" + key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1)
                            + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1),
                    value));
            // AND THE GO FIELD NAME. terraform-plugin-framework reflects over the
            // provider model, and an unexported field is not a field it can see:
            // "Object defines fields not found in struct".
            additionalProperties().put(
                    "provider" + key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1)
                            + "Go",
                    camelize(underscore(String.valueOf(part.get("attribute")))
                            .toLowerCase(Locale.ROOT)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> perResource = (Map<String, Object>) attributes.get("byResource");
        if (perResource != null) {
            perResource.forEach((resource, value) ->
                    byResource.put(resource, map(value)));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /**
     * The version segment comes off every path and goes onto the servers, where
     * it is true of the whole API rather than of each route. Without this a
     * resource is `polar_v1_product`, and the version is in a type name that a
     * v2 would have to break.
     */
    private void liftVersionPrefix(OpenAPI openAPI) {
        String prefix = String.valueOf(section("document").getOrDefault("versionPrefix", ""));
        if (prefix.isEmpty() || openAPI.getPaths() == null) {
            return;
        }

        io.swagger.v3.oas.models.Paths lifted = new io.swagger.v3.oas.models.Paths();
        openAPI.getPaths().forEach((path, item) -> {
            String stripped = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
            lifted.addPathItem(stripped.isEmpty() ? "/" : stripped, item);
        });
        openAPI.setPaths(lifted);

        if (openAPI.getServers() != null) {
            openAPI.getServers().forEach(server -> {
                String url = server.getUrl() == null ? "" : server.getUrl();
                server.setUrl(url.replaceAll("/$", "") + prefix);
            });
        }
    }

    /**
     * Only the collections the config names are resources. Everything else the
     * document describes is a report (nothing creates it) or a verb (nothing
     * reads it back), and a Terraform resource for either is one tofu offers to
     * create and then fails to.
     */
    private void dropPathsNotConfigured(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) {
            return;
        }

        String prefix = String.valueOf(section("document").getOrDefault("versionPrefix", ""));

        Set<String> keep = new HashSet<>();
        for (String path : strings(section("resources"), "include")) {
            String stripped = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
            keep.add(collectionOf(stripped.isEmpty() ? "/" : stripped));
        }

        io.swagger.v3.oas.models.Paths kept = new io.swagger.v3.oas.models.Paths();
        openAPI.getPaths().forEach((path, item) -> {
            if (keep.contains(collectionOf(path))) {
                kept.addPathItem(path, item);
            }
        });
        openAPI.setPaths(kept);

        // Incoming webhook descriptions are what the API sends US. A provider
        // never calls one, and their payload schemas are a third of the document.
        openAPI.setWebhooks(null);
    }

    /**
     * AN OPERATION NO RESOURCE CALLS IS NOT IN THE DOCUMENT, so nothing is built
     * for it.
     *
     * The same rule {@link #addOperationToGroup} applies, applied to the document
     * instead of to the grouping: a list and an action endpoint were dropped from
     * the resource but their schemas were still here, and a list's QUERY
     * PARAMETERS are where `anyOf` filters live -- `status`, `organization_id`,
     * `exclude_ids`. Each is a titled inline union, so each got a model of its
     * own, {@code type StatusFilter struct{}}, for an operation the provider
     * never calls.
     */
    private void dropOperationsNoResourceCalls(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) {
            return;
        }

        io.swagger.v3.oas.models.Paths kept = new io.swagger.v3.oas.models.Paths();

        openAPI.getPaths().forEach((path, item) -> {
            item.readOperationsMap().forEach((method, operation) -> {
                if (!calls(path, method.name(), operation)) {
                    item.operation(method, null);
                }
            });

            if (!item.readOperations().isEmpty()) {
                kept.addPathItem(path, item);
            }
        });

        openAPI.setPaths(kept);
    }

    /** Whether a resource calls this operation at all. */
    private boolean calls(String path, String method, Operation operation) {
        boolean member = isMember(collectionOf(path), path);

        return member
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "DELETE".equals(method)
                || ("POST".equals(method) && answers(operation, "201"));
    }

    /**
     * OpenAPI 3.1 lets a property be pinned to {@code null}, and Go has no such
     * type. Polar says "this variant has no such field" that way: a one-time
     * product's `recurring_interval` is `{"type": "null"}`, and the generated
     * field came out `*nil`, which does not compile. A field that can only ever
     * be null carries no value, so it goes -- and "one-time" is then spelled by
     * omitting it, which is the right shape anyway.
     */
    private void dropNullTypedProperties(OpenAPI openAPI) {
        eachSchema(openAPI, schema -> {
            Map<String, io.swagger.v3.oas.models.media.Schema> properties = schema.getProperties();
            if (properties == null) {
                return;
            }

            List<String> pinned = new ArrayList<>();
            properties.forEach((name, property) -> {
                if (pinnedToNull(property)) {
                    pinned.add(name);
                }
            });

            for (String name : pinned) {
                properties.remove(name);
                if (schema.getRequired() != null) {
                    schema.getRequired().remove(name);
                }
            }
        });
    }

    /**
     * Whether a schema can only ever be null.
     *
     * OPENAPI 3.1 CARRIES TYPE AS A SET, not a string: `{"type": "null"}` lands in
     * getTypes() and leaves getType() empty, so asking only the singular one found
     * nothing and every pinned-null property survived -- `*nil` in the Go, which
     * does not compile.
     */
    private boolean pinnedToNull(io.swagger.v3.oas.models.media.Schema schema) {
        if (schema == null) {
            return false;
        }

        if ("null".equals(schema.getType())) {
            return true;
        }

        Set<String> types = schema.getTypes();

        return types != null && types.size() == 1 && types.contains("null");
    }

    /**
     * A UNION WITH ONE BRANCH LEFT IS THAT BRANCH.
     *
     * Polar spells "optional" as a union with null: a subscription's `trial_end`
     * is {@code anyOf: [date-time, "now", null]}. Null is nullability rather than
     * a branch, so where one branch survives there is nothing for the config to
     * decide -- but it is still a composition, and openapi-generator lifts a
     * composition into a model of its own, which came out {@code type
     * SubscriptionUpdateTrialEnd struct{}}. Nullability is carried by the pointer
     * the field already is.
     *
     * Only where ONE branch survives. A union of several scalars is a judgement
     * -- string or number -- and that is the config's `unions`.
     */
    private void collapseNullableUnions(OpenAPI openAPI) {
        eachSchema(openAPI, schema -> {
            List<io.swagger.v3.oas.models.media.Schema> branches = schema.getAnyOf() != null
                    ? schema.getAnyOf()
                    : schema.getOneOf();

            if (branches == null) {
                return;
            }

            List<io.swagger.v3.oas.models.media.Schema> typed = new ArrayList<>();
            for (io.swagger.v3.oas.models.media.Schema branch : branches) {
                if (!pinnedToNull(branch)) {
                    typed.add(branch);
                }
            }

            if (typed.size() != 1) {
                return;
            }

            io.swagger.v3.oas.models.media.Schema only = typed.get(0);

            schema.setAnyOf(null);
            schema.setOneOf(null);

            if (only.get$ref() != null) {
                schema.set$ref(only.get$ref());
                return;
            }

            schema.setType(only.getType());
            schema.setTypes(only.getTypes());
            schema.setFormat(only.getFormat());
            schema.setEnum(only.getEnum());
            schema.setItems(only.getItems());
            schema.setProperties(only.getProperties());
            schema.setAdditionalProperties(only.getAdditionalProperties());
        });
    }

    /**
     * A NAMED SCHEMA THAT IS ONLY ANOTHER NAME IS NOT A SCHEMA.
     *
     * {@code CheckoutCreate} is {@code {"$ref": "CheckoutProductsCreate"}} and
     * nothing else. openapi-generator mints a model for it all the same, with
     * none of the target's properties -- {@code type CheckoutCreate struct{}},
     * the create body of the checkout resource. The config says what each one
     * stands for; every reference goes to the thing itself.
     */
    private void inlineAliases(OpenAPI openAPI) {
        if (aliases.isEmpty()) {
            return;
        }

        eachSchema(openAPI, schema -> {
            String ref = schema.get$ref();
            if (ref == null || !ref.startsWith(REF_PREFIX)) {
                return;
            }

            String target = aliases.get(ref.substring(REF_PREFIX.length()));
            if (target != null) {
                schema.set$ref(REF_PREFIX + target);
            }
        });
    }

    /**
     * A NAMED SCALAR IS WRITTEN WHERE IT IS USED, so no model is ever minted for
     * it.
     *
     * `WebhookEventType` is `type: string` with forty enum values, and
     * openapi-generator mints a model for every named schema while the templates
     * render a model as a struct -- `type WebhookEventType struct{}`. A webhook
     * endpoint's `events` was then a list of that, which no attribute type
     * matches, and the position fell back to a string holding JSON: "attribute
     * events: string required, but have tuple".
     *
     * Patching the Go afterwards cannot win, because the element's own model file
     * is written too and then the collection and the element disagree. Replacing
     * the reference with the scalar the config states leaves nothing to patch:
     * `events` is `[]string` because the document says so.
     *
     * The config says which schemas these are -- bin/generate-config reads
     * `type` off each one -- so there is nothing here to work out.
     */
    private void inlineNamedScalars(OpenAPI openAPI) {
        if (namedScalars.isEmpty() || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null) {
            return;
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                openAPI.getComponents().getSchemas();

        eachSchema(openAPI, schema -> {
            String ref = schema.get$ref();
            if (ref == null || !ref.startsWith(REF_PREFIX)) {
                return;
            }

            String name = ref.substring(REF_PREFIX.length());
            String type = namedScalars.get(name);
            if (type == null) {
                return;
            }

            io.swagger.v3.oas.models.media.Schema target = schemas.get(name);

            schema.set$ref(null);
            schema.setType(type);
            // OPENAPI 3.1 CARRIES TYPE AS A SET and this generator reads the set
            // first, so setting only the singular leaves the position typeless.
            schema.setTypes(new LinkedHashSet<>(java.util.List.of(type)));

            if (target != null) {
                // The enum comes along: a configuration naming a value Polar does
                // not have is then refused by the schema rather than by the API.
                schema.setEnum(target.getEnum());
                schema.setFormat(target.getFormat());
            }
        });
    }

    /**
     * Every schema in the document: components, request bodies, responses and
     * parameters alike.
     *
     * COMPONENTS ALONE IS NOT THE DOCUMENT. A named scalar reached only from a
     * query parameter -- `BenefitSortProperty`, off `sorting` -- kept its
     * reference, stayed reachable, and got a model of its own: `type
     * BenefitSortProperty struct{}` again.
     */
    private void eachSchema(OpenAPI openAPI, java.util.function.Consumer<io.swagger.v3.oas.models.media.Schema> visit) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        if (openAPI.getComponents() != null) {
            walk(openAPI.getComponents().getSchemas(), visit, seen);
        }
        walk(openAPI.getPaths(), visit, seen);
    }

    /** Every schema under any node of the document, visited once. */
    private void walk(Object node, java.util.function.Consumer<io.swagger.v3.oas.models.media.Schema> visit,
                      Set<Object> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.media.Schema) {
            io.swagger.v3.oas.models.media.Schema schema = (io.swagger.v3.oas.models.media.Schema) node;

            visit.accept(schema);

            walk(schema.getProperties(), visit, seen);
            walk(schema.getItems(), visit, seen);
            walk(schema.getOneOf(), visit, seen);
            walk(schema.getAnyOf(), visit, seen);
            walk(schema.getAllOf(), visit, seen);
            // A map's VALUE TYPE hangs off additionalProperties and nowhere else,
            // which is where a product's `metadata` keeps its named scalar.
            walk(schema.getAdditionalProperties(), visit, seen);
            return;
        }

        if (node instanceof Map) {
            // A COPY: a visit may remove what it was shown -- a property pinned
            // to null goes out of the map it was found in.
            new ArrayList<>(((Map<?, ?>) node).values()).forEach(value -> walk(value, visit, seen));
            return;
        }

        if (node instanceof Iterable) {
            new ArrayList<>((java.util.Collection<?>) node).forEach(value -> walk(value, visit, seen));
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.PathItem) {
            ((io.swagger.v3.oas.models.PathItem) node).readOperations()
                    .forEach(operation -> walk(operation, visit, seen));
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.Operation) {
            io.swagger.v3.oas.models.Operation operation = (io.swagger.v3.oas.models.Operation) node;

            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values()
                        .forEach(media -> walk(media.getSchema(), visit, seen));
            }
            if (operation.getResponses() != null) {
                operation.getResponses().values().forEach(response -> {
                    if (response.getContent() != null) {
                        response.getContent().values().forEach(media -> walk(media.getSchema(), visit, seen));
                    }
                });
            }
            if (operation.getParameters() != null) {
                operation.getParameters().forEach(parameter -> walk(parameter.getSchema(), visit, seen));
            }
        }
    }

    /**
     * HCL HAS NO UNION: one attribute is one type. So every oneOf the config
     * names becomes one concrete shape, exactly as the config says.
     *
     *   widen    -- the branches are all scalars, so the position becomes the one
     *               type that can carry any of them.
     *   drop     -- a branch that reaches back into the union. Terraform cannot
     *               express arbitrary nesting, and inlining it would put a schema
     *               inside itself.
     *   variants -- what each variant's CONFLICTING properties are renamed with.
     *               A property every variant agrees on is emitted once under its
     *               own name; one that appears in a single variant is already
     *               unambiguous and keeps its name too.
     *
     * The discriminator, where there is one, is emitted once as a string carrying
     * the document's own mapping keys as its enum -- so a typo is refused by the
     * schema rather than by the API. It is held out of the merge: every variant
     * pins it to a different constant, so by the conflict rule it would be split
     * into one attribute per variant, none of them the one the API takes.
     */
    private void applyUnions(OpenAPI openAPI) {
        Map<String, Object> unions = section("unions");
        if (unions.isEmpty() || openAPI.getComponents() == null) {
            return;
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                openAPI.getComponents().getSchemas();

        // WIDENINGS FIRST, FLATTENINGS SECOND. A widening settles a leaf -- a
        // clause's `value` becomes a string -- and a flattening builds structure
        // that carries that leaf along by reference. Done the other way round, the
        // order the config file happens to list them in decides whether
        // `filter.clauses[].value` came out a string or a JSON blob.
        for (int pass = 0; pass < 2; pass++) {
            boolean widening = pass == 0;

            for (Map.Entry<String, Object> union : unions.entrySet()) {
                if (!(union.getValue() instanceof Map)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) union.getValue();
                boolean isWiden = entry.get("widen") != null;

                if (isWiden != widening) {
                    continue;
                }

                io.swagger.v3.oas.models.media.Schema node = resolve(schemas, union.getKey());
                if (node == null) {
                    System.err.println("[polar] union not found in document: " + union.getKey());
                    continue;
                }

                if (isWiden) {
                    String widen = String.valueOf(entry.get("widen"));
                    clearComposition(node);
                    node.setType(widen);
                    // OPENAPI 3.1 CARRIES TYPE AS A SET and this generator reads
                    // the set in preference to the string, so setting only the
                    // singular left the property still looking like a composition
                    // -- `Value interface{}` in the client and a JSON attribute in
                    // the schema, exactly as before the widening.
                    node.setTypes(new LinkedHashSet<>(java.util.List.of(widen)));
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, String> labels = (Map<String, String>) entry.get("variants");
                if (labels == null || labels.isEmpty()) {
                    continue;
                }

                flatten(node, schemas, labels, union.getKey(),
                        "merge".equals(String.valueOf(entry.get("conflicts"))));
            }
        }
    }

    /**
     * The schema at a config key: `Name`, `Name.prop`, `Name.prop.items`, and so
     * on down. Null when the document no longer has it -- a key whose path was
     * dropped by the resource filter is not an error.
     */
    private io.swagger.v3.oas.models.media.Schema resolve(
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas, String key) {
        String[] parts = key.split("\\.");
        io.swagger.v3.oas.models.media.Schema node = schemas.get(parts[0]);

        for (int i = 1; i < parts.length && node != null; i++) {
            if ("items".equals(parts[i])) {
                node = node.getItems();
            } else if ("values".equals(parts[i])) {
                // A MAP'S VALUE TYPE: `metadata.values` is the anyOf behind
                // `additionalProperties`, which is a union like any other.
                node = node.getAdditionalProperties() instanceof io.swagger.v3.oas.models.media.Schema
                        ? (io.swagger.v3.oas.models.media.Schema) node.getAdditionalProperties()
                        : null;
            } else if (node.getProperties() != null) {
                node = (io.swagger.v3.oas.models.media.Schema) node.getProperties().get(parts[i]);
            } else {
                return null;
            }
        }

        return node;
    }

    /** The schema a `$ref` names, or the schema itself when it is not one. */
    private io.swagger.v3.oas.models.media.Schema deref(
            io.swagger.v3.oas.models.media.Schema schema,
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas) {
        if (schema == null || schema.get$ref() == null || !schema.get$ref().startsWith(REF_PREFIX)) {
            return schema;
        }

        return schemas.get(schema.get$ref().substring(REF_PREFIX.length()));
    }

    /**
     * `type: null` AND NOTHING ELSE, which is how one arm of a union says that
     * the position is the thing it does NOT have. Written two ways: a 3.0
     * document carries one `type`, and a 3.1 one carries a set of them.
     */
    private boolean nullOnly(io.swagger.v3.oas.models.media.Schema schema) {
        if ("null".equals(schema.getType())) {
            return true;
        }
        return schema.getTypes() != null && schema.getTypes().size() == 1
                && schema.getTypes().contains("null");
    }

    /** One property against one label, once. */
    private void own(Map<String, List<String>> owned, String label, String property) {
        List<String> theirs = owned.computeIfAbsent(label, ignored -> new ArrayList<>());
        if (!theirs.contains(property)) {
            theirs.add(property);
        }
    }

    private void clearComposition(io.swagger.v3.oas.models.media.Schema node) {
        node.setOneOf(null);
        node.setAnyOf(null);
        node.setDiscriminator(null);
    }

    /** One union, merged into one object schema. */
    @SuppressWarnings("unchecked")
    private void flatten(io.swagger.v3.oas.models.media.Schema node,
                         Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
                         Map<String, String> labels, String key, boolean mergeConflicts) {
        String discriminator = node.getDiscriminator() == null
                ? null
                : node.getDiscriminator().getPropertyName();
        List<String> values = node.getDiscriminator() == null || node.getDiscriminator().getMapping() == null
                ? new ArrayList<>()
                : new ArrayList<>(node.getDiscriminator().getMapping().keySet());

        // property name -> variant -> that variant's schema for it
        Map<String, Map<String, io.swagger.v3.oas.models.media.Schema>> owners = new LinkedHashMap<>();
        List<List<String>> requiredPerVariant = new ArrayList<>();
        Map<String, List<String>> requiredPerLabel = new LinkedHashMap<>();

        for (String variant : labels.keySet()) {
            io.swagger.v3.oas.models.media.Schema schema = schemas.get(variant);
            if (schema == null || schema.getProperties() == null) {
                return;
            }

            requiredPerVariant.add(schema.getRequired() == null
                    ? new ArrayList<>() : new ArrayList<>(schema.getRequired()));
            requiredPerLabel.put(labels.get(variant), schema.getRequired() == null
                    ? new ArrayList<>() : new ArrayList<>(schema.getRequired()));

            schema.getProperties().forEach((name, property) -> {
                if (name.equals(discriminator)) {
                    return;
                }
                owners.computeIfAbsent(String.valueOf(name), ignored -> new LinkedHashMap<>())
                        .put(variant, (io.swagger.v3.oas.models.media.Schema) property);
            });
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> merged = new LinkedHashMap<>();

        // WHICH ATTRIBUTES BELONG TO WHICH VARIANT, for the plan-time rules that
        // refuse one a discriminator did not ask for and one it does ask for and
        // is missing. Recorded here, where the answer is, rather than worked out
        // again later from names that have been through two renames by then.
        Map<String, List<String>> owned = new LinkedHashMap<>();

        // WHICH FIELDS A MERGED BLOCK CARRIES FOR WHICH VARIANT. Separate from
        // the renamed attributes above because the two answer different
        // questions: a renamed attribute is a block the configuration picks one
        // of, and a merged field is a field inside the one block every variant
        // shares.
        Map<String, List<String>> inside = new LinkedHashMap<>();

        // WHICH PROPERTY BELONGS TO EXACTLY ONE VARIANT. A property only one
        // variant declares trivially "agrees" with itself, so it merges straight
        // through -- and nothing downstream knew it was the thing that PICKS that
        // variant. A union with no discriminator has nothing else to go on: the
        // arms of a checkout link's create body are told apart by `product_id`
        // against `product_price_id` against `products`, and exactly one of them
        // belongs in a configuration.
        Map<String, List<String>> exclusive = new LinkedHashMap<>();

        owners.forEach((property, byVariant) -> {
            boolean agreed = new HashSet<>(byVariant.values()).size() == 1;

            if (byVariant.size() == 1) {
                exclusive.computeIfAbsent(labels.get(byVariant.keySet().iterator().next()),
                        ignored -> new ArrayList<>()).add(property);
            }

            if (agreed) {
                merged.put(property, byVariant.values().iterator().next());
                // AGREED IS NOT THE SAME AS SHARED. A property every arm spells
                // the same way needs no rule; one that only SOME arms declare
                // agrees with itself trivially, and it is still a field the
                // others must not carry -- a fixed discount's `amount` and
                // `currency` against a percentage one.
                if (byVariant.size() < labels.size()) {
                    byVariant.forEach((variant, schema) -> own(inside, labels.get(variant), property));
                }
            } else if (mergeConflicts) {
                merged.put(property, mergeObjects(byVariant.values(), schemas));
                // ONLY WHERE SOME VARIANT LACKS IT. A property every variant
                // carries is shared, so there is nothing for the plan to refuse
                // -- and the block itself is not a field of itself.
                if (byVariant.size() < labels.size()) {
                    byVariant.forEach((variant, schema) -> own(inside, labels.get(variant), property));
                }
            } else {
                byVariant.forEach((variant, schema) -> {
                    String attribute = labels.get(variant) + "_" + property;
                    merged.put(attribute, schema);
                    owned.computeIfAbsent(labels.get(variant), ignored -> new ArrayList<>())
                            .add(attribute);
                });
            }
        });

        // Required of EVERY variant, else a configuration filling one variant is
        // invalid for the fields of the others -- and a name that was renamed is
        // not required under its old spelling.
        List<String> required = requiredPerVariant.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(requiredPerVariant.get(0));
        for (List<String> theirs : requiredPerVariant) {
            required.retainAll(theirs);
        }
        required.retainAll(merged.keySet());

        if (discriminator != null && !values.isEmpty()) {
            io.swagger.v3.oas.models.media.StringSchema choice =
                    new io.swagger.v3.oas.models.media.StringSchema();
            java.util.Collections.sort(values);
            values.forEach(choice::addEnumItem);
            choice.setDescription("Which variant this is. Selects which of the optional blocks above applies.");
            merged.put(discriminator, choice);
            if (!required.contains(discriminator)) {
                required.add(discriminator);
            }
        }

        clearComposition(node);
        node.setType("object");
        node.setProperties(merged);
        node.setRequired(required.isEmpty() ? null : required);

        // THE DISCRIMINATOR'S OWN RULES, carried on the schema so that the model
        // built from it can refuse a block that contradicts it and ask for the
        // one its own variant demands. openapi-generator keeps a schema's
        // extensions, so this survives into CodegenModel.
        // THE ARM THAT IS THE ABSENCE OF THE OTHER. A product is recurring or
        // one-time, and the one-time arm declares nothing of its own: it IS not
        // having `recurring_interval`. So there is no "exactly one of" to write
        // -- the rule is that the recurring arm's own fields may only be set
        // when the field that arm REQUIRES is, and that required field is the
        // only thing naming the arm.
        //
        // The document says it with `type: null` on the other arm, which is
        // pruned long before this runs. Required-and-exclusive is the same fact,
        // and it survives.
        if (discriminator == null && exclusive.size() == 1
                && exclusive.size() < labels.values().stream().distinct().count()) {
            String label = exclusive.keySet().iterator().next();
            List<String> owns = exclusive.get(label);
            List<String> marks = new ArrayList<>(owns);
            marks.retainAll(requiredPerLabel.getOrDefault(label, new ArrayList<>()));

            if (marks.size() == 1) {
                List<String> needs = new ArrayList<>(owns);
                needs.remove(marks.get(0));

                if (!needs.isEmpty()) {
                    if (node.getExtensions() == null) {
                        node.setExtensions(new LinkedHashMap<>());
                    }
                    Map<String, Object> rules = new LinkedHashMap<>();
                    rules.put("x-terraform-marker", marks.get(0));
                    rules.put("x-terraform-needs", needs);
                    node.getExtensions().put("x-terraform-also-requires", rules);
                }
            }
        }

        // NO DISCRIMINATOR TO READ, so the rule is "exactly one of these", one
        // representative per variant. Only where every variant has one: a variant
        // that declares nothing of its own cannot be picked, and an
        // ExactlyOneOf missing an arm would refuse a configuration that is fine.
        if (discriminator == null && !exclusive.isEmpty()
                && exclusive.size() == labels.size()) {
            if (node.getExtensions() == null) {
                node.setExtensions(new LinkedHashMap<>());
            }
            List<String> picks = new ArrayList<>();
            exclusive.values().forEach(theirs -> picks.add(theirs.get(0)));
            node.getExtensions().put("x-terraform-exclusive", picks);
        }

        if (discriminator != null) {
            if (node.getExtensions() == null) {
                node.setExtensions(new LinkedHashMap<>());
            }

            Map<String, Object> rules = new LinkedHashMap<>();
            rules.put("x-terraform-discriminator", discriminator);
            owned.forEach((label, attributes) -> rules.put(label, attributes));
            node.getExtensions().put("x-terraform-variants", rules);

            if (!inside.isEmpty()) {
                node.getExtensions().put("x-terraform-merged", new LinkedHashMap<>(inside));
            }
        }

        // AN INLINE UNION NEEDS A NAME. openapi-generator names an inline schema
        // after the position it first met that shape in and reuses one model for
        // every position that matches -- so two unrelated flattened unions became
        // one model, carrying whichever one's required fields it saw first. Named
        // after the config key, they stay apart.
        if (key.contains(".")) {
            String name = Arrays.stream(key.split("\\."))
                    .filter(part -> !part.equals("items"))
                    .map(part -> Arrays.stream(part.split("_"))
                            .map(word -> word.isEmpty() ? word
                                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                            .collect(java.util.stream.Collectors.joining()))
                    .collect(java.util.stream.Collectors.joining());

            if (!schemas.containsKey(name)) {
                io.swagger.v3.oas.models.media.ObjectSchema hoisted =
                        new io.swagger.v3.oas.models.media.ObjectSchema();
                hoisted.setProperties(merged);
                hoisted.setRequired(node.getRequired());
                hoisted.setDescription(node.getDescription());
                schemas.put(name, hoisted);

                node.setProperties(null);
                node.setRequired(null);
                node.setType(null);
                node.set$ref(REF_PREFIX + name);
            }
        }
    }

    /**
     * Several variants' versions of one property, as a single object carrying the
     * union of their fields. Required of EVERY variant stays required; anything
     * else is optional, because which variant applies is not known until the
     * discriminator is read and a schema cannot wait for that.
     *
     * Where the versions are not all objects there is nothing to merge, and the
     * first wins -- the alternative is inventing a type the document does not
     * describe.
     *
     * A VERSION IS USUALLY A `$ref`, and a reference has no properties of its
     * own: asking it for them found none, so the merge bailed on the first
     * variant and kept only ITS fields. A custom field's `properties` came out
     * with the text variant's six and without `ge`, `le` or `options` -- a select
     * field with no options.
     */
    @SuppressWarnings("unchecked")
    private io.swagger.v3.oas.models.media.Schema mergeObjects(
            java.util.Collection<io.swagger.v3.oas.models.media.Schema> versions,
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas) {
        io.swagger.v3.oas.models.media.ObjectSchema merged =
                new io.swagger.v3.oas.models.media.ObjectSchema();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = new LinkedHashMap<>();
        List<String> required = null;

        for (io.swagger.v3.oas.models.media.Schema named : versions) {
            io.swagger.v3.oas.models.media.Schema version = deref(named, schemas);

            if (version == null || version.getProperties() == null) {
                return versions.iterator().next();
            }

            version.getProperties().forEach((name, property) -> properties.putIfAbsent(
                    String.valueOf(name), (io.swagger.v3.oas.models.media.Schema) property));

            List<String> theirs = version.getRequired() == null
                    ? new ArrayList<>() : new ArrayList<>(version.getRequired());

            if (required == null) {
                required = theirs;
            } else {
                required.retainAll(theirs);
            }
        }

        merged.setProperties(properties);
        merged.setRequired(required == null || required.isEmpty() ? null : required);

        return merged;
    }

    /**
     * What the kept paths actually reach, transitively. Without this the client
     * carries a Go file per schema in the document -- over a thousand of them,
     * almost all for paths that are no longer here, which is a build to wait on
     * and a diff nobody can read.
     */
    private void pruneUnreachableSchemas(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                openAPI.getComponents().getSchemas();

        Set<String> reached = new HashSet<>();
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>(refsUnder(openAPI.getPaths()));

        while (!frontier.isEmpty()) {
            String name = frontier.pop();
            if (!reached.add(name)) {
                continue;
            }
            frontier.addAll(refsUnder(schemas.get(name)));
        }

        schemas.keySet().retainAll(reached);
    }

    /** Every component schema name a node references, at any depth. */
    private List<String> refsUnder(Object node) {
        List<String> found = new ArrayList<>();

        walk(node, schema -> {
            if (schema.get$ref() != null && schema.get$ref().startsWith(REF_PREFIX)) {
                found.add(schema.get$ref().substring(REF_PREFIX.length()));
            }
        }, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

        return found;
    }

    /**
     * The group key is the collection path, so the member operations land
     * with the collection's own -- and what is not part of a resource at all
     * never lands anywhere.
     *
     * A POST is told from a list by WHAT IT ANSWERS, not by how its path is
     * spelled: a create answers 201 and a list answers 200. Reading the response
     * code rather than the path also drops the action endpoints for free --
     * {@code POST /events/ingest} and {@code POST /checkouts/client/{secret}/confirm}
     * answer 200, so neither is mistaken for a create.
     *
     * What is kept is everything a resource actually calls:
     *
     * <pre>
     *   a member path                GET read, PUT update, DELETE delete
     *   POST answering 201           the create
     *   PUT on a collection          an idempotent "set these" 
     *   DELETE on a collection       the inverse of that set
     * </pre>
     *
     * and what is dropped is a GET on a collection (a list, which a resource
     * never calls) and a POST that answers 200 (an action endpoint, such as
     * {@code /events/ingest}).
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
     * A group that describes nothing.
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
     * Where a document creates a thing on one path and addresses it on another,
     * both name the same resource. Left alone they are two groups writing one set
     * of files, the second silently overwriting the first with half a resource.
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
     * Upstream asks {@code CodegenOperation.isRestfulCreate()} and friends, and
     * those cannot answer for a NESTED resource: {@code isMemberPath()} opens
     * with {@code if (pathParams.size() != 1) return false}, so
     * {@code /customers/{id}/members/{member_id}} -- two path params -- looks
     * like nothing at all, and the whole resource comes out empty.
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
        // CAPTURED HERE, NOT LOOKED UP LATER. super() rebuilds the operation list
        // it was handed, so asking it for the create afterwards found nothing and
        // the fallback below used the UPDATE body as the write shape -- which is
        // how every resource's ToClientModel came out taking a *Update.
        String createBody = null;

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
                // the collection: a document may create a thing on one path and
                // address it on another.
                op.vendorExtensions.put("x-terraform-is-create", true);
                if (op.bodyParam != null) {
                    createBody = op.bodyParam.dataType;
                }
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
        // and sends it to the update too, which an API that declares a separate
        // patch body refuses. Polar declares one for every resource here:
        // BenefitUpdate against BenefitCreate, MeterUpdate against MeterCreate.
        CodegenOperation updateOp = operationFlagged(group, "x-terraform-is-update");
        if (updateOp != null && updateOp.bodyParam != null) {
            processed.getOperations().put("updateRequestModel", updateOp.bodyParam.dataType);
        }

        // THE CREATE'S BODY IS THE WRITE SHAPE, taken from the operation THIS
        // generator flagged rather than from upstream's own guess. Upstream only
        // recognises a create on a top-level collection by its own rules, and
        // where it finds none it leaves requestModel null -- at which point the
        // fallback below used the UPDATE body instead.
        //
        // WHAT THAT COST: polar_benefit's writable attributes became
        // BenefitUpdate's flattened variants -- benefit_meter_credit_update_type
        // and fifteen more -- while `type`, which BenefitCreate requires, matched
        // nothing and came out Computed. A benefit resource that cannot say which
        // kind of benefit it is cannot create one, and the whole catalogue was
        // unexpressible.
        if (createBody != null) {
            processed.getOperations().put("requestModel", createBody);
        }

        // Upstream takes the request body from the CREATE operation only, so a
        // resource you can update but not create -- one whose write is a PUT and
        // never a POST -- had no request model, and ToClientModel came out as
        // `*client.` with no type. The update body is the write shape there.
        if (processed.getOperations().get("requestModel") == null) {
            CodegenOperation writes = operationFlagged(group, "x-terraform-is-update");

            if (writes != null && writes.bodyParam != null) {
                processed.getOperations().put("requestModel", writes.bodyParam.dataType);
            }
        }

        // A free-form or list body is not a model: an unconstrained object comes
        // through as returnType `interface{}` and a list body as a Go slice, so the
        // templates spelled `client.interface{}` and `client.[]Something`.
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
        // strips a meaningful segment off the end. The path already named this
        // resource.
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

        // WHICH ATTRIBUTE ADDRESSES THE RESOURCE, which is what a DATA SOURCE is
        // given rather than what it answers: `data "polar_meter" "test" { id =
        // ... }`, and everything else Computed. The resource reads the same list
        // and does not look at this.
        if (tf != null) {
            tf.forEach(a -> a.put("isSelector",
                    String.valueOf(idName).equals(a.get("goName"))));
        }

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
                || nestedJson(tf));
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

        // attr, for the []attr.Value a types.List is built from. Only where one
        // is actually built -- an import Go does not need is as fatal as one it
        // does.
        boolean usesAttr = tf != null && tf.stream().anyMatch(a ->
                Boolean.TRUE.equals(a.get("isScalarList")) || Boolean.TRUE.equals(a.get("isScalarMap")));

        processed.getOperations().put("usesAttr", usesAttr);
        processed.getOperations().put("usesTypes", usesTypes || anyNested);
        processed.getOperations().put("usesJsontypes", usesJson);
        processed.getOperations().put("usesEncodingJson", toJson || fromJson || usesJson);
        processed.getOperations().put("usesFmt", toJson || anyNested);
        processed.getOperations().put("hasClientModel", request || response);

        Object createModel = processed.getOperations().get("requestModel");
        Object updateModel = processed.getOperations().get("updateRequestModel");

        // A SEPARATE UPDATE MODEL HAS TO SHARE SOME FIELD NAMES WITH THE CREATE,
        // or it is not a different shape -- it is a differently NAMED one, and
        // nothing a configuration set will ever reach it.
        //
        // Polar's PATCH /benefits/{id} takes an inline, non-discriminated anyOf
        // of BenefitCustomUpdate and seven siblings, so the flattened names come
        // off the schema names (`benefit_meter_credit_update_properties`) while
        // the create's come off its discriminator (`meter_credit_properties`).
        // Not one name overlaps, so every attribute's inUpdateRequest was false
        // and ToUpdateModel sent an empty body. Sending the create's shape to the
        // patch is the better wrong answer, and for Polar it is the right one:
        // the update variants carry the same fields the create variants do.
        if (!sharesFieldNames(allModels, createModel, updateModel)) {
            processed.getOperations().put("updateRequestModel", createModel);
            updateModel = createModel;
        }

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
     * `required`, and nothing is readOnly unless the document marks it --
     * which Polar's does not. So a resource came out demanding `id`,
     * `created_at` and `modified_at` in configuration, values the server
     * assigns.
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

        // WHAT THIS ONE RESOURCE DOES WITH EACH OF ITS ATTRIBUTES. Keyed by the
        // name a configuration spells, which is the only name both sides agree
        // on -- the collection path is this generator's business, and a resource
        // it renamed would silently stop finding its own answers.
        String resourceKey = String.valueOf(additionalProperties().get("providerName"))
                + "_" + operations.get("resourceName");
        Map<String, Object> per = byResource.getOrDefault(resourceKey, new LinkedHashMap<>());

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
        Map<String, CodegenProperty> patchable = new LinkedHashMap<>();

        if (updateRequest != null) {
            for (CodegenProperty property : updateRequest.vars) {
                patchable.put(property.baseName.toLowerCase(Locale.ROOT), property);
            }
        }

        // What the read answer carries, for the positions this resource offers
        // that the create body does not take.
        CodegenModel response = modelNamed(allModels, (String) operations.get("responseModel"));
        Map<String, CodegenProperty> answerable = new LinkedHashMap<>();

        if (response != null) {
            for (CodegenProperty property : response.vars) {
                answerable.put(property.baseName.toLowerCase(Locale.ROOT), property);
            }
        }

        // THE SCHEMA IS THE CREATE BODY, PLUS IDENTITY -- which is how the
        // hand-written provider this one replaces is shaped, and the only shape a
        // configuration can actually write.
        //
        // Upstream seeds the attribute list from the RESPONSE model and overlays
        // the create on top, so every field the server merely ANSWERS became an
        // attribute: polar_benefit carried `selectable`, `deletable`,
        // `is_deleted` and `visibility_configurable`, none of which a
        // configuration may set, and the create's own `type` lost its name to the
        // response's read-only one and came out Computed. A benefit that cannot
        // say which kind it is cannot be created.
        //
        // So the list is rebuilt: the identifier and the timestamps from the
        // response, Computed, and then everything the create body takes, in the
        // order the document declares it. A response-derived entry is REUSED
        // where one exists, because that is what carries the Go type the read
        // converts back from.
        if (request != null) {
            Map<String, Map<String, Object>> answeredBy = new LinkedHashMap<>();
            for (Map<String, Object> attribute : attributes) {
                answeredBy.put(String.valueOf(attribute.get("name")).toLowerCase(Locale.ROOT), attribute);
            }

            List<Map<String, Object>> rebuilt = new ArrayList<>();

            for (String identity : IDENTITY) {
                Map<String, Object> attribute = answeredBy.get(identity);
                if (attribute != null && !writable.containsKey(identity)) {
                    rebuilt.add(attribute);
                }
            }

            // POSITIONS THE CREATE BODY DOES NOT TAKE, WHICH THE RESOURCE STILL
            // OFFERS. Taken from the update body where there is one -- Polar
            // archives a product through `is_archived` and has no DELETE for it
            // at all, so a field only the patch declares still has to be
            // reachable -- and from the read answer where there is not, which is
            // the only way a webhook endpoint's secret can be offered: no
            // endpoint hands one out at create.
            for (String name : strings(per, "include")) {
                if (writable.containsKey(name)) {
                    continue;
                }
                CodegenProperty patch = patchable.get(name);
                CodegenProperty answer = answerable.get(name);
                if (patch == null && answer == null) {
                    System.err.println("[polar] " + resourceKey + ": include '" + name
                            + "' is in neither the update body nor the read answer -- ignored");
                    continue;
                }
                rebuilt.add(includedAttribute(
                        patch != null ? patch : answer, patch != null));
            }

            for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
                Map<String, Object> attribute = answeredBy.get(entry.getKey());
                rebuilt.add(attribute != null ? attribute : writeOnlyAttribute(entry.getValue()));
            }

            attributes.clear();
            attributes.addAll(rebuilt);
        }

        // THE NAME A CONFIGURATION SPELLS, moved before anything that matches on
        // it: an attribute is a Set, is dropped, or is required under the name a
        // person writes, not the one the document happened to use.
        rename(attributes, map(per.get("rename")));

        Set<String> answered = new HashSet<>();

        for (Map<String, Object> attribute : attributes) {
            String name = String.valueOf(attribute.get("name")).toLowerCase(Locale.ROOT);
            answered.add(name);
            CodegenProperty writes = writable.get(name);

            // With no create operation there is nothing to infer from, and
            // upstream's answer stands.
            if (request != null) {
                // THE CREATE BODY DECIDES, AND A SERVER DEFAULT ALONE DOES NOT
                // MAKE IT COMPUTED.
                //
                // This used to read "Optional AND Computed wherever the create
                // body takes it without insisting", which made half of every
                // resource Optional-and-Computed for a reason no configuration
                // could observe. Optional alone is what the hand-written
                // provider does and on the same grounds: a value the
                // configuration set is read back to check it has not changed,
                // and a value it did not set is not this provider's to fill in.
                //
                // Only the positions the config names are Optional AND
                // Computed, because that pair says two different things at once
                // -- a configuration MAY leave it out and the server MAY fill it
                // in -- and no document ever asks for it.
                boolean written = writes != null;
                boolean required = written && writes.required;
                attribute.put("isRequired", required);
                attribute.put("isOptional", written && !required);
                attribute.put("isComputed", !written || computedAttributes.contains(name));
            }

            // The server answers this one, so state can be refreshed from it --
            // unless the create body spells it differently, as a tenant's
            // owners are `Owner` going out (with a password) and `OwnerResponse`
            // coming back (without). Reading that back would drop the password
            // out of state and diff forever.
            // POINTER-NESS IS NOT A DIFFERENT SHAPE. This asks whether the create
            // body and the read answer carry the same type, because a position
            // they spell differently cannot be refreshed from the answer. Once
            // optional scalars became pointers the two models stopped agreeing
            // about fields that are optional on the way in and always answered on
            // the way back -- a product's `recurring_interval` is `*string` in
            // ProductCreate and `string` in Product -- so read-back switched off
            // and an imported product planned `+ recurring_interval = "month"`
            // against a product that already had one. The conversions handle
            // pointers on each side independently, so only the underlying type
            // matters here.
            // EXACT, POINTER AND ALL, and that is deliberate after trying it the
            // other way. Ignoring the pointer switched read-back ON for fields the
            // create body has as *int32 and the answer has as a plain int64 --
            // a discount's `max_redemptions` -- and the API omits those, so Go's
            // zero went into state and every plan proposed `- max_redemptions =
            // 0 -> null` again. A position the two models spell differently is one
            // this cannot safely refresh, which is what the strict test says.
            //
            // The cost is a position that COULD be refreshed and is not: a
            // product's `prices` (ProductCreateRecurringPrices going out,
            // something else coming back) and its `recurring_interval`. Those
            // import as additions and drift in them is not detected. Fixing it
            // properly means reading the answer's own type, not relaxing this.
            boolean sameShape = writes == null
                    || writes.dataType.equals(String.valueOf(attribute.get("goType")));
            attribute.put("inRequest", writes != null);
            attribute.put("inUpdateRequest", patchable.containsKey(name));

            // THE UPDATE BODY HAS ITS OWN POINTER-NESS. A property the create body
            // REQUIRES is a plain value there, and the same property may still be
            // optional -- and so a pointer -- in the patch body. The conversion was
            // branching on the create field's shape while assigning into the update
            // field, which is "cannot use m.Name.ValueString() (value of type
            // string) as *string value in assignment", eleven times over.
            // AND THE CREATE BODY HAS ITS OWN. `isPointer` is the RESPONSE field's
            // shape, because that is what the read converts from; the write has to
            // follow the create field. A benefit's `visibility` is `string` in the
            // answer and `*string` in BenefitCreate, which is "cannot use
            // m.Visibility.ValueString() (value of type string) as *string value in
            // assignment" -- the same mistake as the update side, one model over.
            boolean createPointer = writes != null && writes.dataType != null
                    && writes.dataType.startsWith("*");
            attribute.put("isCreatePointer", createPointer);
            attribute.put("createScalarType",
                    createPointer ? writes.dataType.substring(1) : "");

            CodegenProperty patches = patchable.get(name);
            boolean updatePointer = patches != null && patches.dataType != null
                    && patches.dataType.startsWith("*");
            attribute.put("isUpdatePointer", updatePointer);
            attribute.put("updateScalarType",
                    updatePointer ? patches.dataType.substring(1) : "");
            // AND THE RESPONSE HAS TO ANSWER IT. A property the create body takes
            // and no response carries is write-only, and reading it back emits
            // `c.Whatever` for a field the response model does not have -- which
            // is a compile error, not a drift bug. writeOnlyAttribute already
            // says so; this loop was overwriting it.
            attribute.put("readBack", sameShape && !Boolean.TRUE.equals(attribute.get("isWriteOnly")));

            // A POSITION WHOSE TWO SPELLINGS DIFFER IS TYPED BY THE REQUEST, since
            // the request is what a configuration writes. A product's `medias`
            // goes out as a list of file ids and comes back as a list of file
            // objects; taken from the answer it is a string holding JSON, and the
            // schema refuses `medias = ["..."]` with "string required, but have
            // tuple". A checkout link's `products` is the same: ids out, objects
            // back.
            if (writes != null && !sameShape) {
                // The RESPONSE's own type is kept, because there are two answers
                // to give for this position and they are not the same one: the
                // one a configuration writes, and the one a block typed from the
                // answer would be built out of.
                attribute.put("responseGoType", attribute.get("goType"));
                attribute.put("goType", writes.dataType);
            }

            unpoint(attribute);
            retype(attribute);

            // The document's own enum is a constraint the schema can check: a
            // `recurring_interval` may only be one of four, and leaving that as
            // documentation meant `recurring_interval = "fortnightly"` planned
            // cleanly and was refused by the API.
            applyValidators(attribute, new LinkedHashMap<>(), writes);
        }

        // Only reachable when there is no create body to rebuild from; the
        // rebuild above already carries every writable property.
        if (request == null) {
            for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
                if (answered.contains(entry.getKey())) {
                    continue;
                }
                attributes.add(writeOnlyAttribute(entry.getValue()));
            }
        }

        nest(attributes, allModels, writable, patchable,
                String.valueOf(operations.get("resourceClassName")), map(per.get("unwrap")),
                strings(per, "responseBlocks"));

        // WHAT THIS RESOURCE DOES NOT EXPOSE, applied to the whole tree: a
        // product's `tax_behavior` lives inside its prices, and the document
        // declares it on every price variant.
        drop(attributes, droppedAttributes);
        drop(attributes, strings(per, "drop"));

        // AND WHAT IT SAYS ABOUT WHAT IS LEFT, over and above the create body.
        // Also the whole tree: a Discord bot token is a child of
        // `discord_properties`, and so is every one of an organization's
        // notification toggles.
        declare(attributes, per);

        applyUnionRules(operations, attributes, request, per);

        // EVERY ATTRIBUTE SAYS WHETHER IT IS NESTED, even the ones that plainly
        // are not. Mustache resolves a missing key by walking UP the context
        // stack, so a scalar child with no `isNestedList` of its own found its
        // PARENT's -- and nested_schema.mustache rendered the parent's body
        // again, for ever. The generator died in a StackOverflowError inside
        // jmustache with no file written.
        denestDefaults(attributes);

        for (Map<String, Object> attribute : attributes) {
            String terraformName = String.valueOf(attribute.get("terraformName"));

            // A tfsdk name must START WITH A LETTER: "invalid tfsdk tag, must
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

        boolean anyJson = anyJsonAttribute(attributes);

        // The nested blocks, collected so the model file can declare a struct
        // for each one. Mustache cannot gather them itself.
        List<Map<String, Object>> nestedModels = new ArrayList<>();
        collectNested(attributes, nestedModels);
        operations.put("nestedModels", nestedModels);
        operations.put("hasNestedModels", !nestedModels.isEmpty());

        // Nothing is a types.List any more, so the import that served them is
        // not needed and the JSON one is.
        operations.put("hasListAttributes", attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isList"))));
        operations.put("hasJsonAttributes", anyJson);

        // THE DATA SOURCE RENDERS NO NESTED BLOCKS, so a JSON attribute that only
        // exists as a nested child must not make it import jsontypes -- an import
        // Go does not need is as fatal as one it does.
        operations.put("hasFlatJsonAttributes", attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isJson"))));

        operations.put("hasStringValidators", anyStringValidator(attributes));
        operations.put("hasPatternValidators", anyPatternValidator(attributes));
        operations.put("hasNestedStringValidators", anyStringValidator(
                nestedModels.stream()
                        .flatMap(model -> nestedOf(model).stream())
                        .collect(java.util.stream.Collectors.toList())));
    }

    /**
     * WHAT THIS RESOURCE SAYS ABOUT WHAT IS LEFT, over and above the create body.
     *
     * Each of the lists is one decision, and the config is where the decisions
     * that are decisions live. The order matters and is the order the config
     * states them in: the first list that names an attribute wins, so `required`
     * beats `optional` beats `computed`.
     *
     * RECURSIVE, because a block's children are attributes too. A Discord bot
     * token is a child of `discord_properties`; every one of an organization's
     * customer-email toggles is a child of `customer_email_settings`; and
     * `seat_tiers` is a child of a price, three levels down.
     */
    private void declare(List<Map<String, Object>> attributes, Map<String, Object> per) {
        List<String> asRequired = strings(per, "required");
        List<String> asOptional = strings(per, "optional");
        List<String> asComputed = strings(per, "computed");
        List<String> asBoth = strings(per, "optionalComputed");
        List<String> asSensitive = strings(per, "sensitive");
        List<String> asSet = strings(per, "set");
        Map<String, Object> validators = map(per.get("validators"));

        for (Map<String, Object> attribute : attributes) {
            String spelled = String.valueOf(attribute.get("terraformName"));

            if (asRequired.contains(spelled)) {
                attribute.put("isRequired", true);
                attribute.put("isOptional", false);
                attribute.put("isComputed", false);
            } else if (asOptional.contains(spelled)) {
                attribute.put("isRequired", false);
                attribute.put("isOptional", true);
                attribute.put("isComputed", false);
            } else if (asBoth.contains(spelled)) {
                attribute.put("isRequired", false);
                attribute.put("isOptional", true);
                attribute.put("isComputed", true);
            } else if (asComputed.contains(spelled)) {
                attribute.put("isRequired", false);
                attribute.put("isOptional", false);
                attribute.put("isComputed", true);
            }

            if (asSensitive.contains(spelled) || sensitiveAttributes.contains(spelled)) {
                attribute.put("isSensitive", true);
            }

            // WHICH ONES MATTER, NOT WHAT ORDER. A webhook endpoint's `events`
            // is a set of event types, and a server that answers them in another
            // order has not done anything a configuration could tell -- but a
            // List compares element by element, so it is a permanent diff.
            if (Boolean.TRUE.equals(attribute.get("isScalarList")) && asSet.contains(spelled)) {
                attribute.put("isScalarSet", true);
                attribute.put("terraformType", "types.Set");
                attribute.put("terraformAttrType", "schema.SetAttribute");
            }

            Object forThis = validators.get(spelled);
            if (forThis != null) {
                applyValidators(attribute, map(forThis), null);
            }

            declare(nestedOf(attribute), per);
        }
    }

    /**
     * WHETHER ANY ATTRIBUTE IN THE TREE CARRIES A PATTERN VALIDATOR, which is
     * what decides whether the resource file imports `regexp` at all. An import
     * Go does not need is as fatal as one it does.
     */
    private boolean anyPatternValidator(List<Map<String, Object>> attributes) {
        return attributes.stream().anyMatch(attribute ->
                attribute.get("validatorPattern") != null
                        || anyPatternValidator(nestedOf(attribute)));
    }

    /**
     * WHETHER ANY ATTRIBUTE IN THE TREE STILL CARRIES JSON.
     *
     * RECURSIVELY, NOT ONE LEVEL. A price's seat_tiers holds a `tiers` that is
     * JSON because it is deeper than nestedMaxDepth, and that is three levels
     * down; one level is what this used to look at, so the file imported no
     * jsontypes and the schema declared one -- "undefined: jsontypes".
     */
    private boolean anyJsonAttribute(List<Map<String, Object>> attributes) {
        return attributes.stream().anyMatch(attribute ->
                Boolean.TRUE.equals(attribute.get("isJson"))
                        || anyJsonAttribute(nestedOf(attribute)));
    }

    /**
     * WHETHER ANY ATTRIBUTE IN THE TREE CARRIES A STRING VALIDATOR, which is what
     * decides whether the resource file imports the validators packages at all.
     * An import Go does not need is as fatal as one it does.
     */
    private boolean anyStringValidator(List<Map<String, Object>> attributes) {
        return attributes.stream().anyMatch(attribute ->
                Boolean.TRUE.equals(attribute.get("hasStringValidator"))
                        || anyStringValidator(nestedOf(attribute)));
    }

    /**
     * THE NAME A CONFIGURATION SPELLS, moved off the document's own.
     *
     * The Go field and the JSON key are untouched -- only the tfsdk tag and the
     * attribute in the schema. A checkout link's create body is a union of "one
     * product", "one product price" and "these products", and a configuration
     * says which by spelling one attribute, so all three spellings become
     * `product_ids`.
     */
    private void rename(List<Map<String, Object>> attributes, Map<String, Object> renames) {
        if (renames.isEmpty()) {
            return;
        }

        for (Map<String, Object> attribute : attributes) {
            String from = String.valueOf(attribute.get("terraformName"));
            Object to = renames.get(from);

            if (to != null) {
                attribute.put("terraformName", String.valueOf(to));
            }
        }
    }

    /**
     * WHAT A RESOURCE DOES NOT EXPOSE, applied to the whole tree.
     *
     * THE TREE, NOT THE TOP LEVEL: a price's `tax_behavior` is declared on every
     * one of Polar's four price variants and is not exposed inside any of them,
     * and there is no way to say that with a top-level name.
     */
    private void drop(List<Map<String, Object>> attributes, java.util.Collection<String> names) {
        if (names.isEmpty()) {
            return;
        }

        attributes.removeIf(attribute ->
                names.contains(String.valueOf(attribute.get("terraformName"))));

        for (Map<String, Object> attribute : attributes) {
            drop(nestedOf(attribute), names);
        }
    }

    /**
     * THE CONSTRAINTS A SCHEMA CHECKS, from the document's own enum where there is
     * one and from the config where there is not.
     *
     * The document's enum is the honest answer for a string that may only be one
     * of a set -- `recurring_interval`, a permission, a discount duration -- and
     * leaving it as documentation meant `recurring_interval = "fortnightly"`
     * planned cleanly and was refused by the API. The config's `oneOf` is for the
     * cases the document cannot answer: a benefit kind Polar has shipped since
     * this was written, or one it has retired.
     *
     * `lengthAtMost` and `lengthAtLeast` are only ever the config's. Polar's own
     * application limits a benefit's description to 42 characters while its
     * description says `type: string`, and there is no reading of the document
     * that finds that.
     */
    private void applyValidators(Map<String, Object> attribute, Map<String, Object> validators,
                                 CodegenProperty writes) {
        List<String> allowed = new ArrayList<>();

        if (validators.get("oneOf") instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> configured = (List<String>) validators.get("oneOf");
            allowed.addAll(configured);
        } else if (writes != null && writes.isString && writes._enum != null) {
            for (Object value : writes._enum) {
                // An enum the document spells as a number is not a string a
                // configuration writes, and the validator would refuse it.
                if (value != null) {
                    allowed.add(String.valueOf(value));
                }
            }
        }

        if (validators.get("lengthAtMost") != null) {
            attribute.put("validatorLengthAtMost",
                    Integer.parseInt(String.valueOf(validators.get("lengthAtMost"))));
        }
        if (validators.get("lengthAtLeast") != null) {
            attribute.put("validatorLengthAtLeast",
                    Integer.parseInt(String.valueOf(validators.get("lengthAtLeast"))));
        }
        if (validators.get("pattern") != null) {
            attribute.put("validatorPattern", String.valueOf(validators.get("pattern")));
            attribute.put("validatorMessage", validators.get("message") != null
                    ? String.valueOf(validators.get("message"))
                    : String.valueOf(validators.get("pattern")));
        }
        if (!allowed.isEmpty()) {
            attribute.put("validatorOneOf", allowed);
            attribute.put("hasOneOf", true);
        }

        attribute.put("hasStringValidator", attribute.containsKey("validatorLengthAtMost")
                || attribute.containsKey("validatorLengthAtLeast")
                || attribute.containsKey("validatorPattern")
                || attribute.containsKey("validatorOneOf"));
    }

    /**
     * ONE POSITION THE CREATE BODY DOES NOT TAKE, offered anyway.
     *
     * Typed by whichever body declared it -- the update body's, because that is
     * what a configuration writing it sends -- and refreshed only where it came
     * from the read answer, because a position the update body alone has has
     * nothing to convert one into.
     */
    private Map<String, Object> includedAttribute(CodegenProperty property, boolean fromPatch) {
        Map<String, Object> attribute = writeOnlyAttribute(property);

        attribute.put("isRequired", false);
        attribute.put("isOptional", true);
        attribute.put("isComputed", false);
        attribute.put("isWriteOnly", false);
        attribute.put("inRequest", false);
        attribute.put("inUpdateRequest", fromPatch);
        attribute.put("readBack", !fromPatch);

        return attribute;
    }

    /**
     * WHAT THE DISCRIMINATOR DECIDES, REFUSED BY THE PLAN RATHER THAN BY THE API.
     *
     * A discriminated union of objects becomes one attribute per variant, and
     * nothing stops a configuration from setting two of them -- or from setting
     * the one its discriminator did not ask for. Polar answers 422; the
     * hand-written provider refuses it in the plan, where the message can name
     * both. The same applies to the other half of the question: an attribute the
     * chosen variant demands and nobody set, which Polar also answers 422 to.
     *
     * Most of this comes out of the flattening itself: the discriminator, and
     * which attributes belong to which variant. What the document cannot say is
     * recorded beside it -- see `conflictWhen` and `requiredWhen` in the
     * configuration. A MERGED conflict rule has one attribute for every variant,
     * so there is nothing to choose between between them and the config says
     * what belongs to what.
     */
    private void applyUnionRules(OperationMap operations, List<Map<String, Object>> attributes,
                                 CodegenModel request, Map<String, Object> per) {
        String discriminator = null;
        Map<String, Object> blocks = new LinkedHashMap<>();
        Map<String, Object> fields = new LinkedHashMap<>();

        // READ OFF THE FLATTENED SCHEMA, not off a name worked out here again:
        // the flattening is the only thing that knows which attributes belong to
        // which variant, and it wrote the answer down when it did it.
        if (request != null && openAPI != null && openAPI.getComponents() != null
                && openAPI.getComponents().getSchemas() != null) {
            io.swagger.v3.oas.models.media.Schema schema =
                    openAPI.getComponents().getSchemas().get(request.classname);
            if (schema != null && schema.getExtensions() != null) {
                if (schema.getExtensions().get("x-terraform-variants") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> recorded =
                            new LinkedHashMap<>((Map<String, Object>) schema.getExtensions()
                                    .get("x-terraform-variants"));
                    Object named = recorded.remove("x-terraform-discriminator");
                    discriminator = named == null ? null : String.valueOf(named);
                    blocks.putAll(recorded);
                }
                if (schema.getExtensions().get("x-terraform-also-requires") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rules = (Map<String, Object>) schema.getExtensions()
                            .get("x-terraform-also-requires");
                    Map<String, Object> marker = attributeNamed(attributes,
                            String.valueOf(rules.get("x-terraform-marker")), true, "", "");
                    List<Map<String, Object>> needs = new ArrayList<>();
                    if (marker != null && rules.get("x-terraform-needs") instanceof List) {
                        for (Object owned : (List<?>) rules.get("x-terraform-needs")) {
                            Map<String, Object> found = attributeNamed(attributes,
                                    String.valueOf(owned), true, "", "");
                            if (found == null) {
                                continue;
                            }
                            Map<String, Object> rule = new LinkedHashMap<>();
                            describeSet(rule, found);
                            rule.put("marker", marker.get("terraformName"));
                            rule.put("markerGo", marker.get("goName"));
                            needs.add(rule);
                        }
                    }
                    if (!needs.isEmpty()) {
                        operations.put("alsoRequires", needs);
                        operations.put("hasVariantRules", true);
                    }
                }
                if (schema.getExtensions().get("x-terraform-exclusive") instanceof List) {
                    // THE NAME A CONFIGURATION SPELLS, not the document's: the
                    // flattening recorded the property, and `rename` may have
                    // moved it since -- a checkout link's `products` is
                    // `product_ids`. An arm looked up under the old spelling is
                    // not found, and an ExactlyOneOf missing an arm refuses a
                    // configuration that is correct.
                    Map<String, Object> renames = map(per.get("rename"));
                    List<Map<String, Object>> picks = new ArrayList<>();
                    boolean whole = true;
                    for (Object owned : (List<?>) schema.getExtensions()
                            .get("x-terraform-exclusive")) {
                        String named = String.valueOf(renames.getOrDefault(
                                String.valueOf(owned), owned));
                        Map<String, Object> found = attributeNamed(attributes,
                                named, true, "", "");
                        if (found == null) {
                            whole = false;
                            continue;
                        }
                        Map<String, Object> pick = new LinkedHashMap<>();
                        pick.put("attribute", found.get("terraformName"));
                        picks.add(pick);
                    }
                    // ALL OF THEM OR NONE. An arm this resource does not expose
                    // leaves a rule that refuses the arms it does.
                    if (whole && picks.size() > 1) {
                        operations.put("exactlyOneOf", picks);
                        operations.put("hasExactlyOneOf", true);
                    }
                }
                if (schema.getExtensions().get("x-terraform-merged") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> recorded =
                            new LinkedHashMap<>((Map<String, Object>) schema.getExtensions()
                                    .get("x-terraform-merged"));
                    fields.putAll(recorded);
                }
            }
        }

        // A DISCRIMINATOR MAY BE NAMED IN THE CONFIG even where the flattening
        // found none to say: a custom field's five variants merge into one
        // `properties` block, so nothing was renamed and nothing was recorded,
        // and which fields belong to which kind is the one thing the plan has to
        // be told.
        for (String key : new String[] {"conflictWhen", "requiredWhen"}) {
            for (Map.Entry<String, Object> entry : map(per.get(key)).entrySet()) {
                if (discriminator == null && !String.valueOf(entry.getKey()).contains(".")) {
                    discriminator = String.valueOf(entry.getKey());
                }
                // ONLY `conflictWhen` SAYS WHAT BELONGS TO WHAT. `requiredWhen`
                // says what a variant DEMANDS, and it is read again below for
                // that -- adding it here as well gave every demanded attribute
                // a second, identical conflict rule.
                if (!"conflictWhen".equals(key) || !(entry.getValue() instanceof List)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, List<String>> owned = (Map<String, List<String>>) (Map<?, ?>) fields;
                for (Object name : (List<?>) entry.getValue()) {
                    own(owned, String.valueOf(entry.getKey()), String.valueOf(name));
                }
            }
        }

        final String decides = discriminator;

        if (discriminator == null || !attributes.stream()
                .anyMatch(a -> decides.equals(a.get("terraformName")))) {
            return;
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Map<String, Object>> demands = new ArrayList<>();

        // A BLOCK PER VARIANT: renamed, so the configuration picks exactly one,
        // and the message can name the one it wanted.
        blocks.forEach((label, value) -> {
            if (!(value instanceof List)) {
                return;
            }
            for (Object owned : (List<?>) value) {
                Map<String, Object> found = attributeNamed(attributes,
                        String.valueOf(owned), false, "", "");
                if (found == null) {
                    continue;
                }
                Map<String, Object> rule = rule(decides, label, found, true);
                conflicts.add(rule);
            }
        });

        // A FIELD INSIDE THE ONE BLOCK EVERY VARIANT SHARES: the same attribute
        // name, so the message is about the field and not about the block.
        fields.forEach((label, value) -> {
            if (!(value instanceof List)) {
                return;
            }
            for (Object owned : (List<?>) value) {
                Map<String, Object> found = attributeNamed(attributes,
                        String.valueOf(owned), false, "", "");
                if (found == null) {
                    continue;
                }
                conflicts.add(rule(decides, label, found, false));
            }
        });

        // AND THE OTHER HALF: an attribute the chosen variant demands. Only where
        // the config says so -- the document's own required list is the union of
        // every variant's, and Polar's application asks for more than its
        // description admits to.
        for (Map.Entry<String, Object> entry : map(per.get("requiredWhen")).entrySet()) {
            if (!(entry.getValue() instanceof List)) {
                continue;
            }
            for (Object demanded : (List<?>) entry.getValue()) {
                Map<String, Object> found = attributeNamed(attributes,
                        String.valueOf(demanded), true, "", "");
                if (found == null) {
                    continue;
                }
                demands.add(rule(decides, String.valueOf(entry.getKey()), found, true));
            }
        }

        List<Map<String, Object>> blocksOnly = new ArrayList<>();
        for (Map<String, Object> rule : conflicts) {
            if (Boolean.TRUE.equals(rule.get("wholeBlock"))) {
                blocksOnly.add(rule);
            }
        }

        if (!conflicts.isEmpty() || !demands.isEmpty()) {
            operations.put("variantRules", conflicts);
            operations.put("variantBlocks", blocksOnly);
            operations.put("variantDemands", demands);
            operations.put("hasVariantRules", true);
            operations.put("hasVariantBlocks", !blocksOnly.isEmpty());
            operations.put("discriminator", discriminator);
            operations.put("discriminatorGo", camelize(underscore(discriminator)
                    .toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * One attribute by the name a configuration spells, at the top level or
     * inside a block -- which is how a merged union's fields are found: a custom
     * field's `textarea` is a child of the one `properties` block all five kinds
     * share.
     *
     * Deep for a conflict and shallow for a demand: a demanded attribute is one
     * the chosen variant requires, so it is in every one of them -- `name`, say,
     * rather than a block's inner field.
     */
    private Map<String, Object> attributeNamed(List<Map<String, Object>> attributes,
                                                String name, boolean topLevelOnly,
                                                String prefix, String guard) {
        for (Map<String, Object> attribute : attributes) {
            if (name.equals(attribute.get("terraformName"))) {
                String here = String.valueOf(attribute.get("goName"));
                attribute.put("goPath", prefix.isEmpty() ? here : prefix + "." + here);
                attribute.put("goGuard", guard);
                return attribute;
            }
        }
        if (topLevelOnly) {
            return null;
        }
        for (Map<String, Object> attribute : attributes) {
            // A LIST BLOCK HAS NO ONE FIELD TO NAME: `prices[*].amount` is not an
            // expression, so a rule about a field inside a list is not emitted.
            if (Boolean.TRUE.equals(attribute.get("isNestedList"))) {
                continue;
            }
            String here = String.valueOf(attribute.get("goName"));
            String path = prefix.isEmpty() ? here : prefix + "." + here;
            Map<String, Object> found = attributeNamed(nestedOf(attribute), name, false,
                    path, guard + "config." + path + " != nil && ");
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * One rule the plan refuses with. `goPath` rather than `goName`, because a
     * field of a merged block is reached through it.
     */
    private Map<String, Object> rule(String discriminator, String label,
                                     Map<String, Object> attribute, boolean wholeBlock) {
        Map<String, Object> rule = new LinkedHashMap<>();

        // `duration.repeating` rather than `repeating`: WHICH DISCRIMINATOR
        // DECIDES THIS ONE. A discount has two -- `type` and `duration` -- and
        // the flattening only knows the one it flattened.
        String decides = discriminator;
        int dot = label.indexOf('.');
        if (dot > 0) {
            decides = label.substring(0, dot);
            label = label.substring(dot + 1);
        }

        rule.put("discriminator", decides);
        rule.put("label", label);
        rule.put("chosenGo", camelize(underscore(decides).toLowerCase(Locale.ROOT)));
        rule.put("wholeBlock", wholeBlock);
        describeSet(rule, attribute);

        return rule;
    }

    /**
     * HOW ONE ATTRIBUTE IS TESTED FOR BEING SET. A block is a pointer, a list
     * block is a slice and everything else is a framework value, so there is no
     * one expression -- and `IsNull` on the first two does not compile.
     */
    private void describeSet(Map<String, Object> rule, Map<String, Object> attribute) {
        String path = String.valueOf(attribute.containsKey("goPath")
                ? attribute.get("goPath") : attribute.get("goName"));
        rule.put("attribute", String.valueOf(attribute.get("terraformName")));
        rule.put("goName", path);

        String set;
        String unset;
        if (Boolean.TRUE.equals(attribute.get("isNestedList"))) {
            set = "len(config." + path + ") > 0";
            unset = "len(config." + path + ") == 0";
        } else if (Boolean.TRUE.equals(attribute.get("isNested"))) {
            set = "config." + path + " != nil";
            unset = "config." + path + " == nil";
        } else {
            set = "!config." + path + ".IsNull()";
            unset = "config." + path + ".IsNull()";
        }
        String guard = attribute.containsKey("goGuard")
                ? String.valueOf(attribute.get("goGuard")) : "";
        rule.put("isSet", guard + set);
        rule.put("notSet", guard + unset);
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
     * CHILDREN FOLLOW THE SAME RULE AS THE TOP LEVEL: required where the document
     * requires them, Optional alone where it does not, and Optional AND Computed
     * only for the handful of positions the config names. Polar fills in a
     * price's `price_currency` when the configuration omits it, and that one
     * position is why `computed` exists; every other optional child used to be
     * Optional-and-Computed too, which no configuration can observe.
     */
    private void nest(List<Map<String, Object>> attributes, List<ModelMap> allModels,
                      Map<String, CodegenProperty> writable,
                      Map<String, CodegenProperty> patchable, String resourceClassName,
                      Map<String, Object> unwraps, List<String> responseBlocks) {
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
            String responseGo = attribute.containsKey("responseGoType")
                    ? String.valueOf(attribute.get("responseGoType"))
                    : String.valueOf(attribute.get("goType"));
            CodegenProperty writes = writable.get(String.valueOf(attribute.get("name"))
                    .toLowerCase(Locale.ROOT));
            String go = writes != null ? writes.dataType : responseGo;

            // A WRAPPER OBJECT THAT EXISTS ONLY TO CARRY A SERVER DEFAULT. A
            // seat-based price's `seat_tiers` is an object whose one required
            // field is the list of tiers and whose other field defaults to
            // `volume`; as the wrapper it is a block holding a block, and as the
            // list it is what a configuration means. The client field is still the
            // wrapper, so the conversion builds it around the list.
            Object unwrapped = unwraps.get(terraformName);
            if (unwrapped != null) {
                CodegenModel wrapper = modelNamed(allModels, bare(go));
                CodegenProperty inner = wrapper == null ? null : propertyNamed(wrapper.vars,
                        String.valueOf(unwrapped));
                if (inner == null) {
                    System.err.println("[polar] unwrap '" + terraformName + "' has no "
                            + unwrapped + " inside " + go + " -- left alone");
                } else {
                    attribute.put("unwrappedGo", camelize(String.valueOf(unwrapped)));
                    attribute.put("nestedWrapperType", bare(go));
                    go = inner.dataType;
                }
            }

            // A BLOCK WHOSE CHILDREN DESCRIBE WHAT THE SERVER ANSWERS. Polar
            // declares two schemas for an organization's feature settings -- an
            // Update to send and a Settings to read back -- and they are not the
            // same fields, so typed from the request the block would offer
            // `checkout_localization_enabled` and hide `wallets_enabled`. The
            // struct is the same either way; only the two client types differ,
            // which is what nestedResponseType is for.
            boolean fromResponse = responseBlocks.contains(terraformName);
            String responseShape = responseGo;
            String requestShape = go;

            if (fromResponse && !go.equals(responseGo)) {
                go = responseGo;
            }

            // A BLOCK TYPED FROM THE ANSWER MAY HAVE FIELDS THE REQUEST CANNOT
            // CARRY -- the whole point of it -- and assigning one into a struct
            // that has no such field is a compile error rather than a wrong
            // request. Those children are answered and not sent.
            Set<String> sendable = null;
            if (fromResponse && !requestShape.equals(responseShape)) {
                CodegenModel sending = modelNamed(allModels, bare(requestShape));
                if (sending != null && sending.vars != null) {
                    sendable = new HashSet<>();
                    for (CodegenProperty property : sending.vars) {
                        sendable.add(property.baseName);
                    }
                }
            }

            if (!nestInto(attribute, allModels, go, resourceClassName, 1, unwraps, sendable)) {
                continue;
            }

            // THE TWO DIRECTIONS TAKE TWO DIFFERENT CLIENT TYPES, and the model
            // is one struct: ToClientModel sends the request's, FromClientModel
            // is handed the answer's.
            if (fromResponse && !requestShape.equals(responseShape)) {
                attribute.put("nestedClientType", bare(requestShape));
                attribute.put("nestedResponseType", bare(responseShape));
            }

            // READ BACK WHEREVER THE ANSWER CAN BE CONVERTED INTO THIS STRUCT,
            // which is true even where the two shapes differ, as long as the
            // block was typed from the answer. Where they differ and the block
            // was typed from the request there is nothing to convert an answer
            // into, so the attribute is not refreshed -- exactly as a scalar
            // whose request and response spellings diverge is not.
            boolean readBack = Boolean.TRUE.equals(attribute.get("readBack"))
                    && (go.equals(responseShape) || fromResponse);
            attribute.put("readBack", readBack);

            // AND THE PATCH HAS TO TAKE THE SAME TYPE. A nested block is
            // converted through the CREATE body's client type, and Polar names
            // the update's differently for the same shape --
            // MeterCreateAggregation against MeterUpdateAggregation,
            // ProductCreatePricesItem against ProductUpdatePricesInner. Emitting
            // the update arm anyway assigns one into the other, which is a
            // compile error rather than a wrong request.
            CodegenProperty patch = patchable.get(String.valueOf(attribute.get("name"))
                    .toLowerCase(Locale.ROOT));
            if (patch == null || !go.equals(patch.dataType)) {
                attribute.put("inUpdateRequest", false);
            }

            // REQUIRED IF THE CREATE BODY REQUIRES IT. A meter cannot be created
            // without a `filter` or an `aggregation`, and the hand-written
            // provider marks both Required; forcing every block Optional made a
            // meter appliable with nothing in it. Unless this resource has
            // already said otherwise, which is the whole point of saying.
            if (!Boolean.TRUE.equals(attribute.get("isDeclared"))) {
                boolean required = writes != null && writes.required;
                attribute.put("isRequired", required);
                attribute.put("isOptional", !required);
                attribute.put("isComputed", required ? false : readBack);
            }
        }
    }

    /**
     * One attribute inside a nested block. The same shaping the top level gets
     * -- a pointer to a scalar is that scalar -- and
     * then anything still composite is JSON, because this is depth one and
     * there is nowhere further to go.
     */
    private Map<String, Object> childAttribute(CodegenProperty property, List<ModelMap> allModels,
                                              String modelPrefix, int depth,
                                              Map<String, Object> unwraps, Set<String> sendable) {
        Map<String, Object> attribute = new HashMap<>();

        attribute.put("name", property.baseName);
        attribute.put("terraformName", underscore(property.baseName).toLowerCase(Locale.ROOT));
        attribute.put("goName", camelize(property.baseName));
        attribute.put("goType", property.dataType);
        attribute.put("description", property.description != null
                ? property.description.replace("\"", "'").replace("\n", " ")
                : "");
        attribute.put("isRequired", property.required);
        attribute.put("isOptional", !property.required);
        // THE SAME RULE AS THE TOP LEVEL: Optional alone where the document does
        // not insist, and Optional AND Computed only for the positions the config
        // names -- a price's `price_currency` is filled in by the server, and
        // nothing else is.
        boolean computed = !property.required && computedAttributes.contains(
                underscore(property.baseName).toLowerCase(Locale.ROOT));
        attribute.put("isComputed", computed);
        attribute.put("isString", "string".equals(property.dataType));
        attribute.put("isInt64", "int64".equals(property.dataType) || "int32".equals(property.dataType));
        attribute.put("isFloat64", "float64".equals(property.dataType) || "float32".equals(property.dataType));
        attribute.put("isBool", "bool".equals(property.dataType));
        attribute.put("isList", false);
        // A CHILD THAT NAMES A MODEL IS A BLOCK CANDIDATE. retype() only looks at
        // an attribute flagged a list or an object, and a pointer to a model is
        // neither -- so a license key benefit's `expires` was never even
        // considered for expanding and went out as a string holding JSON, while
        // its sibling `clauses` (a slice, which retype does look at) became a
        // block.
        attribute.put("isObject", namesAModel(property.dataType));
        attribute.put("isSensitive", false);
        attribute.put("terraformType", goType(property.dataType));
        attribute.put("terraformAttrType", goAttrType(property.dataType));
        // null means every level below this one is described by the type that
        // carries it, so there is nothing to withhold.
        attribute.put("inChildRequest", sendable == null || sendable.contains(property.baseName));

        unpoint(attribute);
        retype(attribute);

        // The document's own enum is a constraint the schema can check.
        applyValidators(attribute, new LinkedHashMap<>(), property);

        // A CHILD CAN BE A BLOCK TOO, AND A LIST OF BLOCKS. A meter's
        // `filter.clauses` is an array of objects inside an object, and expanding
        // only the outer one left it a string: "Inappropriate value for attribute
        // filter: attribute clauses: string required, but have tuple".
        if (Boolean.TRUE.equals(attribute.get("isJson"))
                && depth < nestedMaxDepth
                && !jsonAttributes.contains(String.valueOf(attribute.get("terraformName")))) {
            // A WRAPPER OBJECT THAT EXISTS ONLY TO CARRY A SERVER DEFAULT, and is
            // a CHILD this time: a seat-based price's `seat_tiers` is three
            // levels down, and the list inside the wrapper is what a
            // configuration means.
            String childGo = String.valueOf(attribute.get("goType"));
            Object unwrapped = unwraps.get(String.valueOf(attribute.get("terraformName")));

            if (unwrapped != null) {
                CodegenModel wrapper = modelNamed(allModels, bare(childGo));
                CodegenProperty inner = wrapper == null ? null
                        : propertyNamed(wrapper.vars, String.valueOf(unwrapped));
                if (inner == null) {
                    System.err.println("[polar] unwrap '" + attribute.get("terraformName")
                            + "' has no " + unwrapped + " inside " + childGo + " -- left alone");
                } else {
                    attribute.put("unwrappedGo", camelize(String.valueOf(unwrapped)));
                    attribute.put("nestedWrapperType", bare(childGo));
                    childGo = inner.dataType;
                }
            }

            nestInto(attribute, allModels, childGo, modelPrefix, depth, unwraps, null);
        }

        return attribute;
    }

    /**
     * Turn one JSON-carrying attribute into a typed block, and its children into
     * blocks in turn until {@code nestedMaxDepth} runs out. False when there is no
     * model behind it to expand.
     *
     * WHETHER THE CLIENT FIELD IS A POINTER decides how the conversion assigns
     * it: a pointer field takes the converted pointer, a plain struct field takes
     * what it points at. Reading that off the shape rather than guessing is the
     * difference between compiling and "cannot use *converted (variable of struct
     * type X) as *X value in assignment".
     */
    private boolean nestInto(Map<String, Object> attribute, List<ModelMap> allModels,
                             String go, String modelPrefix, int depth,
                             Map<String, Object> unwraps, Set<String> sendable) {
        boolean list = go.startsWith("[]");
        boolean pointer = go.replace("[]", "").startsWith("*");
        String bare = go.replace("[]", "").replace("*", "");

        CodegenModel model = modelNamed(allModels, bare);
        if (model == null || model.vars == null || model.vars.isEmpty()) {
            return false;
        }

        String nestedModel = modelPrefix + camelize(String.valueOf(attribute.get("goName"))) + "Model";

        List<Map<String, Object>> children = new ArrayList<>();
        for (CodegenProperty property : model.vars) {
            children.add(childAttribute(property, allModels, nestedModel, depth + 1, unwraps,
                    sendable));
        }

        if (children.isEmpty()) {
            return false;
        }

        attribute.put("isJson", false);
        attribute.put("isNested", true);
        attribute.put("isNestedList", list);
        attribute.put("isNestedObject", !list);
        attribute.put("nested", children);
        attribute.put("nestedModel", nestedModel);
        attribute.put("nestedClientType", bare);
        attribute.put("nestedPointer", pointer);
        attribute.put("terraformType", list ? "[]" + nestedModel : "*" + nestedModel);
        attribute.put("terraformAttrType",
                list ? "schema.ListNestedAttribute" : "schema.SingleNestedAttribute");

        return true;
    }

    /**
     * Every nested block in the tree, so the model file can declare a struct for
     * each one. Mustache cannot gather them itself.
     */
    private void collectNested(List<Map<String, Object>> attributes, List<Map<String, Object>> into) {
        for (Map<String, Object> attribute : attributes) {
            if (!Boolean.TRUE.equals(attribute.get("isNested"))) {
                continue;
            }

            into.add(attribute);
            collectNested(nestedOf(attribute), into);
        }
    }

    /**
     * Give every attribute in the tree an explicit answer for the four keys the
     * nested templates branch on, so no lookup can fall through to a parent.
     */
    private void denestDefaults(List<Map<String, Object>> attributes) {
        for (Map<String, Object> attribute : attributes) {
            attribute.putIfAbsent("isNested", false);
            attribute.putIfAbsent("isNestedList", false);
            attribute.putIfAbsent("isNestedObject", false);
            attribute.putIfAbsent("nested", new ArrayList<Map<String, Object>>());

            denestDefaults(nestedOf(attribute));
        }
    }

    /** Whether any block anywhere in the tree still carries a JSON attribute. */
    private boolean nestedJson(List<Map<String, Object>> attributes) {
        for (Map<String, Object> attribute : attributes) {
            if (Boolean.TRUE.equals(attribute.get("isJson"))) {
                return true;
            }
            if (nestedJson(nestedOf(attribute))) {
                return true;
            }
        }
        return false;
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
     * Whether two models have any property name in common. Used to tell a
     * genuinely different update body from one that is only differently named --
     * see the note at hasSeparateUpdateModel.
     */
    private boolean sharesFieldNames(List<ModelMap> allModels, Object create, Object update) {
        if (create == null || update == null || create.equals(update)) {
            return true;
        }

        CodegenModel createModel = modelNamed(allModels, String.valueOf(create));
        CodegenModel updateModel = modelNamed(allModels, String.valueOf(update));

        if (createModel == null || updateModel == null) {
            return true;
        }

        Set<String> names = new HashSet<>();
        for (CodegenProperty property : createModel.vars) {
            names.add(property.baseName.toLowerCase(Locale.ROOT));
        }

        return updateModel.vars.stream()
                .anyMatch(property -> names.contains(property.baseName.toLowerCase(Locale.ROOT)));
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
        // THE SCHEMA IS UNCHANGED BY THIS -- a pointer to a scalar is still that
        // scalar to Terraform -- but the CONVERSIONS have to take its address on
        // the way out and check it for nil on the way back, so they need to know.
        attribute.put("isPointer", true);
        attribute.put("scalarType", pointed);
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

        // A DECLARED SCALAR is a scalar whatever the composition around it said.
        // A `type: string` carrying an anyOf that only narrows it is flagged a
        // model while its Go field is a string, and declaring that one
        // jsontypes.Normalized gives a model struct the conversions cannot assign
        // to.
        String scalar = go.startsWith("*") ? go.substring(1) : go;
        if (Arrays.asList("string", "bool", "int", "int32", "int64", "float32", "float64")
                .contains(scalar)) {
            attribute.put("isList", false);
            attribute.put("isObject", false);
            // AND ITS HCL TYPE, not only the flags. This is reached when the two
            // spellings of one position differ and the REQUEST's is the scalar:
            // a customer's `tax_id` goes out as a string and comes back as a list
            // of the values the server parsed out of it. The flags said "not a
            // list" and the type still said types.List, which is a schema with
            // no ElementType -- "tax_id is missing the CustomType or ElementType
            // field on a collection Attribute" -- and a model field no
            // conversion touched.
            attribute.put("terraformType", goType(scalar));
            attribute.put("terraformAttrType", goAttrType(scalar));
            return;
        }

        // A LIST OR MAP OF SCALARS IS A LIST OR MAP, not a string holding JSON.
        // A webhook endpoint's `events` is an array of enum strings and a
        // product's `metadata` is a map of them; as JSON the suite refuses both --
        // "Inappropriate value for attribute events: string required, but have
        // tuple".
        //
        // types.List AND types.Map, not a plain Go slice or map.
        // terraform-plugin-framework does reflect over the plain ones, which made
        // the conversions a direct assignment -- but a Computed attribute is
        // UNKNOWN in the plan and a Go map cannot hold that: "Received unknown
        // value, however the target type cannot handle unknown values ... Path:
        // metadata". The framework types can, and are built element by element so
        // no conversion needs a context to call ElementsAs with.
        String element = elementOf(go);
        if (element != null) {
            boolean isMap = go.startsWith("map[");
            attribute.put("isJson", false);
            attribute.put("isScalarList", !isMap);
            attribute.put("isScalarMap", isMap);
            attribute.put("isList", true);
            attribute.put("listElementType", frameworkType(element));
            attribute.put("elementGoType", element);
            attribute.put("elementFromTf", fromFrameworkValue(element));
            attribute.put("elementToTf", toFrameworkValue(element));
            attribute.put("terraformType", isMap ? "types.Map" : "types.List");
            attribute.put("terraformAttrType",
                    isMap ? "schema.MapAttribute" : "schema.ListAttribute");
            return;
        }

        attribute.put("isList", false);
        attribute.put("isObject", false);
        attribute.put("isJson", true);
        attribute.put("terraformType", "jsontypes.Normalized");
        attribute.put("terraformAttrType", "schema.StringAttribute");
    }

    private static final List<String> SCALARS =
            Arrays.asList("string", "bool", "int32", "int64", "float32", "float64");

    /** Whether a Go type is a generated model rather than a scalar or a collection. */
    private boolean namesAModel(String go) {
        String bare = bare(go);

        return !bare.isEmpty()
                && !SCALARS.contains(bare)
                && !"int".equals(bare)
                && !"string".equals(bare)
                && !bare.startsWith("map[")
                && !bare.startsWith("interface{");
    }

    /** A Go type with its slice and pointer markers taken off. */
    private String bare(String go) {
        return go == null ? "" : go.replace("[]", "").replace("*", "");
    }

    /** One property of a model, by the name it is declared under. */
    private CodegenProperty propertyNamed(List<CodegenProperty> properties, String name) {
        if (properties == null) {
            return null;
        }
        for (CodegenProperty property : properties) {
            if (property.baseName.equalsIgnoreCase(name)) {
                return property;
            }
        }
        return null;
    }

    /** The scalar a slice or a string-keyed map is of, or null when it is neither. */
    private String elementOf(String go) {
        String element = null;

        if (go.startsWith("[]")) {
            element = go.substring(2);
        } else if (go.startsWith("map[string]")) {
            element = go.substring("map[string]".length());
        }

        return element != null && SCALARS.contains(element) ? element : null;
    }

    /** One element out of a types.List or types.Map, as the client's own Go type. */
    private String fromFrameworkValue(String element) {
        switch (element) {
            case "bool": return "element.(types.Bool).ValueBool()";
            case "int32": return "int32(element.(types.Int64).ValueInt64())";
            case "int64": return "element.(types.Int64).ValueInt64()";
            case "float32": return "float32(element.(types.Float64).ValueFloat64())";
            case "float64": return "element.(types.Float64).ValueFloat64()";
            default: return "element.(types.String).ValueString()";
        }
    }

    /** One element of the client's answer, as the framework value for it. */
    private String toFrameworkValue(String element) {
        switch (element) {
            case "bool": return "types.BoolValue(element)";
            case "int32": return "types.Int64Value(int64(element))";
            case "int64": return "types.Int64Value(element)";
            case "float32": return "types.Float64Value(float64(element))";
            case "float64": return "types.Float64Value(element)";
            default: return "types.StringValue(element)";
        }
    }

    /** The framework type for an element, which the schema names rather than Go. */
    private String frameworkType(String element) {
        switch (element) {
            case "bool": return "types.BoolType";
            case "int32": case "int64": return "types.Int64Type";
            case "float32": case "float64": return "types.Float64Type";
            default: return "types.StringType";
        }
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
        // Nothing answers it, so there is nothing to read back -- and the loop in
        // reshapeAttributes must not decide otherwise.
        attribute.put("readBack", false);
        attribute.put("isWriteOnly", true);
        attribute.put("isString", "string".equals(property.dataType));
        attribute.put("isInt64", "int64".equals(property.dataType) || "int32".equals(property.dataType));
        attribute.put("isFloat64", "float64".equals(property.dataType) || "float32".equals(property.dataType));
        attribute.put("isBool", "bool".equals(property.dataType));
        attribute.put("isList", property.isArray);
        attribute.put("isObject", property.isModel && !property.isArray);
        // ponytail: a name-based guess at what is secret. A document that says
        // writeOnly or x-terraform-sensitive is believed first; this catches the
        // ones that say neither.
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

        // A UNION OF SCALARS IS A STRING, because that is the only one of its
        // branches a configuration can write for all of them. A filter clause's
        // `value` is `anyOf: [string, integer, boolean]`, and as `interface{}` it
        // became a JSON attribute -- so the suite's `value = "api_call"` was
        // refused with "A string value was provided that is not valid JSON string
        // format (RFC 7159)". The hand-written provider takes a string here and
        // lets the server coerce, and so does this.
        if (allBranchesScalar(property)) {
            return "string";
        }

        return "interface{}";
    }

    /** Whether every branch of a composition is a primitive rather than a shape. */
    private boolean allBranchesScalar(CodegenProperty property) {
        if (property.getComposedSchemas() == null) {
            return false;
        }

        List<CodegenProperty> branches = new ArrayList<>();
        if (property.getComposedSchemas().getAnyOf() != null) {
            branches.addAll(property.getComposedSchemas().getAnyOf());
        }
        if (property.getComposedSchemas().getOneOf() != null) {
            branches.addAll(property.getComposedSchemas().getOneOf());
        }

        if (branches.isEmpty()) {
            return false;
        }

        return branches.stream().allMatch(branch ->
                branch.isString || branch.isInteger || branch.isLong || branch.isNumber
                        || branch.isFloat || branch.isDouble || branch.isBoolean);
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
                // only narrows it -- a `type: string` with an anyOf of
                // {format: email, maxLength: 0}, meaning "an address, or
                // empty". openapi-generator names that composition `AnyOf` and
                // emits it as the Go type, which is not a type and does not
                // compile. The declared type is still on the property, so it is
                // used; a genuine union -- one that
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
                    // is whatever the document says it is -- no bespoke id type
                    // and no unmarshaller tolerant of numbers.
                    property.dataType = "interface{}";
                }

                // A nested object is a POINTER, because Go's `omitempty` does
                // nothing for a struct: an unset one still goes out as `{}` with
                // every field at its zero value, and an API that validates an
                // enum refuses the empty string inside it. A nil pointer is
                // simply absent.
                // complexType, not isModel: a property written as
                // `allOf: [$ref: Something]` -- which is how a document
                // attaches a description to a ref -- is not flagged a model,
                // and went out as "fapiProfile":{} regardless.
                // A BOOL IS A POINTER TOO, for the same reason and a worse
                // consequence: `omitempty` cannot tell false from unset, so an
                // explicit `false` is dropped from the body entirely and the
                // server sees a missing field rather than the value the
                // configuration set. `rollover = false` on a meter credit benefit
                // is exactly that case.
                // AND SO IS EVERY OPTIONAL SCALAR, for the read side rather than the
                // write side. `omitempty` cannot tell absent from zero either way:
                // a discount the server answers without `amount` leaves Go's 0,
                // the read writes 0 into state, the configuration says nothing,
                // and every plan then proposes `- amount = 0 -> null` forever.
                // A nil pointer is absent and reads back as null.
                //
                // NOT FOR A REQUIRED ONE: the configuration always supplies it, so
                // there is no absent case to represent, and a pointer would only
                // add a dereference everywhere it is used.
                //
                // AND NOT BY SKIPPING ZEROES INSTEAD, which was the cheaper fix and
                // the wrong one: Polar documents `price_amount = 0` as a free
                // price, so a zero is a value and has to survive.
                if (!property.required
                        && Arrays.asList("string", "int32", "int64", "float32", "float64")
                                .contains(property.dataType)) {
                    property.dataType = "*" + property.dataType;
                }

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
     * NOTHING IS LEFT TO COLLAPSE, and this says so out loud.
     *
     * A named schema that is not an object used to arrive here as a model with no
     * properties -- the templates render a model as a struct, so a string enum
     * came out {@code type Something struct{}} and a property of that type was a
     * field nothing could convert. The generator then worked out what the model
     * really was, and where it could not it fell back to {@code interface{}}:
     * the attribute became a string holding JSON.
     *
     * Those schemas are inlined in the document now, from the config's
     * `scalars`, so no such model is minted at all. If one turns up anyway the
     * document has a shape the config does not describe, and the generator stops
     * rather than guess at it -- a provider that is silently different is worse
     * than a build that stops.
     */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> models) {
        Map<String, ModelsMap> processed = super.postProcessAllModels(models);

        List<String> empty = new ArrayList<>();

        for (ModelsMap entry : processed.values()) {
            for (ModelMap map : entry.getModels()) {
                CodegenModel model = map.getModel();

                if ((model.vars == null || model.vars.isEmpty())
                        && (model.allVars == null || model.allVars.isEmpty())) {
                    empty.add(model.classname);
                }
            }
        }

        if (!empty.isEmpty()) {
            throw new RuntimeException("models with no properties, which the templates would"
                    + " render as empty structs: " + empty
                    + " -- say what each one is in the config (`scalars` for a named scalar,"
                    + " `aliases` for another name for a schema, `unions` for a composition)"
                    + " and regenerate");
        }

        return processed;
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

            // EVERY LITERAL SEGMENT IS SINGULAR, because a resource manages one
            // of a thing however the collection is spelled. /webhooks/endpoints
            // is `polar_webhook_endpoint`, which is what the hand-written
            // provider calls it and what a configuration already spells --
            // singularising only the last segment gave `polar_webhooks_endpoint`
            // and nothing could be migrated to it without an edit.
            String spelled = singular(segment);

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

    /**
     * The member of THIS collection: its path plus one trailing {param}.
     *
     * THE TRAILING SLASH IS THE WHOLE OF THIS. Polar spells its collections with
     * one -- `/benefits/`, `/products/`, `/meters/` -- and collectionOf() strips
     * it, so `POST /benefits/` compared unequal to its own collection
     * `/benefits` and was classified a MEMBER operation. A member POST matches
     * none of the member branches, so the create was never flagged: no
     * createPath, no createArgs, and requestModel fell through to the UPDATE
     * body. That is why every ToClientModel took a *XUpdate and polar_benefit's
     * `type` was Computed -- one slash, three resources' worth of damage.
     */
    private boolean isMember(String collection, String path) {
        String trimmed = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;

        return collectionOf(path).equals(collection) && !trimmed.equals(collection);
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
     * name".
     */
    private static final List<String> RESERVED =
            Arrays.asList("count", "for_each", "depends_on", "provider", "lifecycle", "id_");

    /**
     * The only response-only fields that belong in a schema: what the resource is
     * addressed by, and when the server last touched it. Everything else a
     * response carries and a create body does not is state the server owns, and
     * an attribute a configuration cannot write is noise in `tofu plan`.
     */
    private static final List<String> IDENTITY =
            Arrays.asList("id", "created_at", "modified_at");

    private static final List<String> KEEP = Arrays.asList("ss", "us", "is", "os", "sts");

    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (!lower.endsWith("s") || KEEP.stream().anyMatch(lower::endsWith)) {
            return name;
        }
        return name.substring(0, name.length() - 1);
    }
}
