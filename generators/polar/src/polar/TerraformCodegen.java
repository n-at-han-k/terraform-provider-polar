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
    private boolean nestedAttributes = true;
    private int nestedMaxDepth = 3;
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
        dropNullTypedProperties(openAPI);
        applyUnions(openAPI);
        pruneUnreachableSchemas(openAPI);
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
        Map<String, Object> provider = section("provider");
        provider.forEach((key, value) -> additionalProperties().put(
                "provider" + key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1),
                String.valueOf(value)));
        additionalProperties().put("providerName", String.valueOf(provider.get("name")));

        Map<String, Object> attributes = section("attributes");
        if (attributes.get("nestedMaxDepth") != null) {
            nestedMaxDepth = Integer.parseInt(String.valueOf(attributes.get("nestedMaxDepth")));
        }
        jsonAttributes.addAll(strings(attributes, "json"));
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

    /** Every schema in the document, components and inline alike. */
    private void eachSchema(OpenAPI openAPI, java.util.function.Consumer<io.swagger.v3.oas.models.media.Schema> visit) {
        Set<io.swagger.v3.oas.models.media.Schema> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        java.util.function.Consumer<io.swagger.v3.oas.models.media.Schema>[] walk =
                new java.util.function.Consumer[1];

        walk[0] = schema -> {
            if (schema == null || !seen.add(schema)) {
                return;
            }

            visit.accept(schema);

            if (schema.getProperties() != null) {
                new ArrayList<Object>(schema.getProperties().values())
                        .forEach(child -> walk[0].accept((io.swagger.v3.oas.models.media.Schema) child));
            }
            walk[0].accept(schema.getItems());
            if (schema.getOneOf() != null) {
                new ArrayList<Object>(schema.getOneOf())
                        .forEach(child -> walk[0].accept((io.swagger.v3.oas.models.media.Schema) child));
            }
            if (schema.getAnyOf() != null) {
                new ArrayList<Object>(schema.getAnyOf())
                        .forEach(child -> walk[0].accept((io.swagger.v3.oas.models.media.Schema) child));
            }
            if (schema.getAllOf() != null) {
                new ArrayList<Object>(schema.getAllOf())
                        .forEach(child -> walk[0].accept((io.swagger.v3.oas.models.media.Schema) child));
            }
        };

        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            new ArrayList<>(openAPI.getComponents().getSchemas().values()).forEach(walk[0]);
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
                    // AND THE TITLE HAS TO GO. openapi-generator mints a MODEL for
                    // any inline schema carrying a title, so a widened
                    // `title: "Value"` came back as `dataType=Value`, a named
                    // model of a scalar -- which the client then rendered
                    // `Value interface{}`, exactly the JSON blob the widening
                    // existed to remove.
                    node.setTitle(null);
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
            } else if (node.getProperties() != null) {
                node = (io.swagger.v3.oas.models.media.Schema) node.getProperties().get(parts[i]);
            } else {
                return null;
            }
        }

        return node;
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

        for (String variant : labels.keySet()) {
            io.swagger.v3.oas.models.media.Schema schema = schemas.get(variant);
            if (schema == null || schema.getProperties() == null) {
                return;
            }

            requiredPerVariant.add(schema.getRequired() == null
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

        owners.forEach((property, byVariant) -> {
            boolean agreed = new HashSet<>(byVariant.values()).size() == 1;

            if (agreed) {
                merged.put(property, byVariant.values().iterator().next());
            } else if (mergeConflicts) {
                merged.put(property, mergeObjects(byVariant.values()));
            } else {
                byVariant.forEach((variant, schema) ->
                        merged.put(labels.get(variant) + "_" + property, schema));
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
                node.set$ref("#/components/schemas/" + name);
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
     */
    @SuppressWarnings("unchecked")
    private io.swagger.v3.oas.models.media.Schema mergeObjects(
            java.util.Collection<io.swagger.v3.oas.models.media.Schema> versions) {
        io.swagger.v3.oas.models.media.ObjectSchema merged =
                new io.swagger.v3.oas.models.media.ObjectSchema();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = new LinkedHashMap<>();
        List<String> required = null;

        for (io.swagger.v3.oas.models.media.Schema version : versions) {
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
        collectRefs(node, found, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return found;
    }

    private void collectRefs(Object node, List<String> found, Set<Object> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.media.Schema) {
            io.swagger.v3.oas.models.media.Schema schema = (io.swagger.v3.oas.models.media.Schema) node;
            if (schema.get$ref() != null && schema.get$ref().startsWith("#/components/schemas/")) {
                found.add(schema.get$ref().substring("#/components/schemas/".length()));
            }
            collectRefs(schema.getProperties(), found, seen);
            collectRefs(schema.getItems(), found, seen);
            collectRefs(schema.getOneOf(), found, seen);
            collectRefs(schema.getAnyOf(), found, seen);
            collectRefs(schema.getAllOf(), found, seen);
            collectRefs(schema.getAdditionalProperties(), found, seen);
            return;
        }

        if (node instanceof Map) {
            ((Map<?, ?>) node).values().forEach(value -> collectRefs(value, found, seen));
            return;
        }

        if (node instanceof Iterable) {
            ((Iterable<?>) node).forEach(value -> collectRefs(value, found, seen));
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.PathItem) {
            ((io.swagger.v3.oas.models.PathItem) node).readOperations()
                    .forEach(operation -> collectRefs(operation, found, seen));
            return;
        }

        if (node instanceof io.swagger.v3.oas.models.Operation) {
            io.swagger.v3.oas.models.Operation operation = (io.swagger.v3.oas.models.Operation) node;
            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values()
                        .forEach(media -> collectRefs(media.getSchema(), found, seen));
            }
            if (operation.getResponses() != null) {
                operation.getResponses().values().forEach(response -> {
                    if (response.getContent() != null) {
                        response.getContent().values()
                                .forEach(media -> collectRefs(media.getSchema(), found, seen));
                    }
                });
            }
            if (operation.getParameters() != null) {
                operation.getParameters().forEach(parameter -> collectRefs(parameter.getSchema(), found, seen));
            }
        }
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

            for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
                Map<String, Object> attribute = answeredBy.get(entry.getKey());
                rebuilt.add(attribute != null ? attribute : writeOnlyAttribute(entry.getValue()));
            }

            attributes.clear();
            attributes.addAll(rebuilt);
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
            attribute.put("inUpdateRequest", patchable.containsKey(name));
            // AND THE RESPONSE HAS TO ANSWER IT. A property the create body takes
            // and no response carries is write-only, and reading it back emits
            // `c.Whatever` for a field the response model does not have -- which
            // is a compile error, not a drift bug. writeOnlyAttribute already
            // says so; this loop was overwriting it.
            attribute.put("readBack", sameShape && !Boolean.TRUE.equals(attribute.get("isWriteOnly")));

            unpoint(attribute);
            retype(attribute);
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

        nest(attributes, allModels, writable, patchable, String.valueOf(operations.get("resourceClassName")));

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

        boolean anyJson = attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isJson")))
                || attributes.stream()
                        .filter(attribute -> attribute.get("nested") != null)
                        .flatMap(attribute -> nestedOf(attribute).stream())
                        .anyMatch(child -> Boolean.TRUE.equals(child.get("isJson")));

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
                      Map<String, CodegenProperty> writable,
                      Map<String, CodegenProperty> patchable, String resourceClassName) {
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

            if (!nestInto(attribute, allModels, go, resourceClassName, 1)) {
                continue;
            }

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
            // meter appliable with nothing in it.
            boolean required = writes != null && writes.required;
            attribute.put("isRequired", required);
            attribute.put("isOptional", !required);
            if (required) {
                attribute.put("isComputed", false);
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
                                              String modelPrefix, int depth) {
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

        // A CHILD CAN BE A BLOCK TOO, AND A LIST OF BLOCKS. A meter's
        // `filter.clauses` is an array of objects inside an object, and expanding
        // only the outer one left it a string: "Inappropriate value for attribute
        // filter: attribute clauses: string required, but have tuple".
        if (Boolean.TRUE.equals(attribute.get("isJson"))
                && depth < nestedMaxDepth
                && !jsonAttributes.contains(String.valueOf(attribute.get("terraformName")))) {
            nestInto(attribute, allModels, String.valueOf(attribute.get("goType")), modelPrefix, depth);
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
                             String go, String modelPrefix, int depth) {
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
            children.add(childAttribute(property, allModels, nestedModel, depth + 1));
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
            return;
        }

        // A LIST OR MAP OF SCALARS IS A LIST OR MAP, not a string holding JSON.
        // A webhook endpoint's `events` is an array of enum strings and a
        // product's `metadata` is a map of them; as JSON the suite refuses both --
        // "Inappropriate value for attribute events: string required, but have
        // tuple".
        //
        // A PLAIN Go SLICE OR MAP, not types.List: terraform-plugin-framework
        // reflects over those, so the conversions are a direct assignment and need
        // no context to call ElementsAs with.
        String element = elementOf(go);
        if (element != null) {
            boolean isMap = go.startsWith("map[");
            attribute.put("isJson", false);
            attribute.put("isScalarList", !isMap);
            attribute.put("isScalarMap", isMap);
            attribute.put("isList", true);
            attribute.put("listElementType", frameworkType(element));
            attribute.put("terraformType", isMap ? "map[string]" + element : "[]" + element);
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
     * A named string enum, or an anyOf of an integer and an array, gets a model
     * of its own from openapi-generator, and the template renders a model as a
     * struct -- so each came out as {@code type Something struct{}}. A property
     * of that type was then a field nothing could convert
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

                    // AND THROUGH A SLICE OR A MAP, not only a pointer. A webhook
                    // endpoint's `events` is `[]WebhookEventType` and a product's
                    // `metadata` is `map[string]MetadataValue1` -- both are
                    // collections of a named model that is really an enum, and
                    // looking only at the bare name left them collections of a
                    // struct with no fields. The attribute then fell back to JSON
                    // and the suite refused it: "attribute events: string
                    // required, but have tuple".
                    // ponytail: through a POINTER only. Reaching through `[]` and
                    // `map[string]` as well is what a list of a collapsed enum
                    // needs -- `events` is `[]WebhookEventType` and wants to be
                    // `[]string` -- but the enum's own model file is still
                    // rendered `type WebhookEventType struct{}`, so the collection
                    // and the element disagree and the client will not compile.
                    // The fix is to render an empty model as a type ALIAS to its
                    // scalar, which is an override of upstream's model template.
                    String wrapper = named.startsWith("*") ? "*" : "";
                    String bare = wrapper.isEmpty() ? named : named.substring(1);

                    String scalar = notStructs.get(bare);

                    if (scalar != null) {
                        // THE PROPERTY KNOWS ITS OWN TYPE even when the model
                        // minted for it does not. A widened union is a plain
                        // string, but openapi-generator still mints a model for
                        // the inline schema and that model carries none of the
                        // scalar flags -- so scalarOf fell back to interface{} and
                        // the attribute went back to being a JSON blob. Where the
                        // model cannot say, the property can.
                        String resolved = "interface{}".equals(scalar)
                                ? unionType(property)
                                : scalar;

                        // A pointer to a collapsed scalar is that scalar; a slice
                        // or map OF one keeps its wrapper.
                        property.dataType = resolved;
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
