package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Both core test tasks execute these checks against their resolved validator family. */
class SchemaValidationTest {
    @Test
    void booleanBoundaryAcceptsJsonNullOnlyWhenAllowedByTheSchema() throws Exception {
        // JSON null is an actual value, distinct from an absent Java argument or a missing-node sentinel.
        var jsonNull = PromptContract.JSON.nullNode();
        var nullable = PromptContract.JSON.readTree("{\"type\":[\"integer\",\"null\"]}");
        var requiredInteger = PromptContract.JSON.readTree("{\"type\":\"integer\"}");
        assertTrue(SchemaValidation.isValid(nullable, jsonNull));
        assertFalse(SchemaValidation.isValid(requiredInteger, jsonNull));
        assertTrue(SchemaValidation.isValid(nullable, PromptContract.JSON.getNodeFactory().numberNode(1)));
        assertFalse(SchemaValidation.isValid(nullable, PromptContract.JSON.getNodeFactory().textNode("1")));
    }

    @Test
    void booleanBoundaryRejectsViolationsWithoutRepairsAndLeavesBothTreesUnchanged() throws Exception {
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["count","codes"],"properties":{
                  "count":{"type":"integer"},"date":{"type":"string","format":"date"},
                  "label":{"type":"string","pattern":"^[A-Z]+$"},
                  "codes":{"type":"array","items":{"$ref":"#/$defs/code"}}},
                 "$defs":{"code":{"type":"string","enum":["A","B"]}}}
                """);
        var value = PromptContract.JSON.readTree("{\"count\":1.00,\"date\":\"2024-02-29\",\"label\":\"OK\",\"codes\":[\"A\",\"B\"]}");
        var schemaBefore = schema.deepCopy();
        var valueBefore = value.deepCopy();
        assertTrue(SchemaValidation.isValid(schema, value));
        assertEquals(schemaBefore, schema);
        assertEquals(valueBefore, value);
        for (String invalid : java.util.List.of("{}", "null", "[]",
                "{\"count\":\"1\",\"codes\":[]}", "{\"count\":1.5,\"codes\":[]}",
                "{\"count\":1,\"codes\":[\"INVALID\"]}", "{\"count\":1,\"codes\":[],\"date\":\"2025-02-30\"}",
                "{\"count\":1,\"codes\":[],\"label\":\"lower\"}", "{\"count\":1,\"codes\":[],\"secret-key-testdata\":true}")) {
            var rejected = PromptContract.JSON.readTree(invalid);
            var before = rejected.deepCopy();
            assertFalse(SchemaValidation.isValid(schema, rejected));
            assertEquals(before, rejected);
            assertEquals(schemaBefore, schema);
        }
        assertTrue(SchemaValidation.isValid(schema, value), "A rejected call must not change the next validation");
    }

    @Test
    void dateTimeRequiresOffsetForGeneratedOffsetDateTime() throws Exception {
        var schema = SchemaValidation.compile(PromptContract.JSON.readTree("{\"type\": \"string\", \"format\": \"date-time\"}"));
        assertTrue(SchemaValidation.validate(schema, PromptContract.JSON.getNodeFactory().textNode("2026-11-10T12:30:00+01:00")).isEmpty());
        assertTrue(SchemaValidation.validate(schema, PromptContract.JSON.getNodeFactory().textNode("2026-11-10T11:30:00Z")).isEmpty());
        assertFalse(SchemaValidation.validate(schema, PromptContract.JSON.getNodeFactory().textNode("2026-11-10T12:30:00")).isEmpty());
    }

    @Test
    void formatAssertionsMustBeExplicitAndDatesAreNotJustStrings() throws Exception {
        var schema = PromptContract.JSON.readTree("{\"type\": \"string\", \"format\": \"date\"}");
        var value = PromptContract.JSON.getNodeFactory().textNode("2026-02-30");
        var defaults = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema.toString(), InputFormat.JSON);
        var withoutFormatAssertions = SchemaValidation.registry(false).getSchema(schema.toString(), InputFormat.JSON);
        assertTrue(SchemaValidation.validate(defaults, value).isEmpty());
        assertTrue(SchemaValidation.validate(withoutFormatAssertions, value).isEmpty());
        assertEquals("format", SchemaValidation.validate(SchemaValidation.compile(schema), value).get(0).getKeyword());
    }

    @Test
    void requiredAndUnknownPropertyErrorsPointToParentAndExposePropertySeparately() throws Exception {
        var resource = getClass().getResource("/prompts/patient/disability/certificate-extraction.prompt.yaml");
        if (resource == null) throw new AssertionError("Missing certificate contract fixture");
        var contract = PromptContract.load(Path.of(resource.toURI()));
        ObjectNode value = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/valid-output.json")).deepCopy();
        value.remove("patientSurname");
        value.put("unknown/key~name", true);
        var errors = SchemaValidation.validate(SchemaValidation.compile(contract.outputSchema()), value);
        assertEquals(2, errors.size());
        errors.forEach(error -> assertEquals("", error.getInstanceLocation().toString()));
        assertEquals(Set.of("patientSurname", "unknown/key~name"), errors.stream()
                .map(com.networknt.schema.Error::getProperty).collect(Collectors.toSet()));
    }

    @Test
    void localRefsKeepInstancePointersAndEscapeArrayPropertyNames() throws Exception {
        var schema = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/nested-schema.json"));
        var value = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/nested-invalid.json"));
        var errors = SchemaValidation.validate(SchemaValidation.compile(schema), value);
        assertTrue(errors.stream().anyMatch(error -> error.getKeyword().equals("format")
                && error.getInstanceLocation().toString().equals("/a~1b~0c/0/issuedAt")));
        assertTrue(errors.stream().anyMatch(error -> error.getKeyword().equals("required")
                && error.getInstanceLocation().toString().equals("/a~1b~0c/1")
                && error.getProperty().equals("degree")));
    }
}
