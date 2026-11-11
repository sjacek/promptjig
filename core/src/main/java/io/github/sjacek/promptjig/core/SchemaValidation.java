package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.util.Locale;

/** Offline Draft 2020-12 validation, including date/date-time assertions. */
public final class SchemaValidation {
    private SchemaValidation() { }

    /**
     * Compiles an offline Draft 2020-12 schema with date and date-time assertions enabled.
     *
     * @param schema schema tree, whose references must be locally resolvable
     * @return validator reporting instance locations as JSON Pointers
     */
    public static Schema compile(JsonNode schema) {
        return registry(true).getSchema(schema);
    }

    /**
     * Creates a registry with remote resource fetching disabled and stable English diagnostics.
     *
     * @param assertFormats whether format keywords reject invalid instance values
     * @return a new registry using Draft 2020-12 and JSON Pointer paths
     */
    public static SchemaRegistry registry(boolean assertFormats) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH)
                        .pathType(PathType.JSON_POINTER).formatAssertionsEnabled(assertFormats).build())
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
    }
}
