package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.util.List;
import java.util.Locale;
import lombok.NonNull;

/**
 * Offline Draft 2020-12 validation, including date/date-time assertions.
 * The JSON-text boundary supports Networknt 2 (Jackson 2) and 3 (Jackson 3) without sharing their node types.
 * Native errors returned here are for internal translation, not value-free application diagnostics.
 */
public final class SchemaValidation {
    private SchemaValidation() { }

    /**
     * Compiles an offline Draft 2020-12 schema with date and date-time assertions enabled.
     * Compilation takes a snapshot of the tree; later caller mutations do not change the compiled rules.
     * Pair the result with {@link #validate(Schema, JsonNode)} to retain Jackson-family portability.
     *
     * @param schema schema tree, whose references must be locally resolvable
     * @return validator reporting instance locations as JSON Pointers
     * @see #validate(Schema, JsonNode)
     */
    public static Schema compile(@NonNull JsonNode schema) {
        // Networknt 2 and 3 expose different Jackson JsonNode types under the same module coordinates.
        // Their JSON-text API is binary compatible, allowing a host's Spring AI ChatClient to select 3.x.
        return registry(true).getSchema(schema.toString(), InputFormat.JSON);
    }

    /**
     * Validates through Networknt's JSON-text boundary without linking to its Jackson 2/3 node overloads.
     * Callers parse JSON strictly before reaching this boundary; serialization preserves its JSON values.
     * This operation does not repair values or coerce strings into numbers or booleans.
     * Native error messages may quote input values: use {@link ContractResponseValidator} for value-free findings.
     * @param schema compiled offline schema
     * @param value JSON value to validate, including JSON null; not a missing-node sentinel; never mutated
     * @return native validation errors for internal translation into value-free diagnostics
     */
    public static List<com.networknt.schema.Error> validate(@NonNull Schema schema, @NonNull JsonNode value) {
        return schema.validate(value.toString(), InputFormat.JSON);
    }

    /**
     * Creates a registry with remote resource fetching disabled and stable English diagnostics.
     * This is the native configuration API. Its JsonNode overloads depend on the resolved Networknt/Jackson family;
     * use the JSON-text overloads, or {@link #compile(JsonNode)} and {@link #validate(Schema, JsonNode)}.
     *
     * @param assertFormats whether format keywords reject invalid instance values
     * @return a new registry using Draft 2020-12 and JSON Pointer paths
     */
    public static SchemaRegistry registry(boolean assertFormats) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                // Text parsing must retain the precision of Promptjig's already-parsed decimal nodes.
                .nodeReader(NativeSchemaJson::configure)
                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH)
                        .pathType(PathType.JSON_POINTER).formatAssertionsEnabled(assertFormats).build())
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
    }
}
