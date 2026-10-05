package org.openapitools.codegen;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * Upstream's inline-schema lifting, ON DEMAND.
 *
 * {@code InlineModelResolver.flatten} is package-private and DefaultGenerator
 * calls it BEFORE {@code preprocessOpenAPI} -- so every pass in the generator saw
 * a document where Polar's inline schemas had already been lifted into
 * components and named after their titles. A config keyed by where a schema sits
 * in the published document ({@code CustomFieldSelectOption.value}) then found a
 * bare {@code $ref} to {@code Value} and could not say anything about it.
 *
 * The generator declines the automatic pass ({@code getUseInlineModelResolver()}
 * is false) and runs it here instead, last, once the document says what the
 * config says it says.
 */
public final class InlineModels {
    private InlineModels() {
    }

    public static void flatten(OpenAPI openAPI) {
        new InlineModelResolver().flatten(openAPI);
    }
}
