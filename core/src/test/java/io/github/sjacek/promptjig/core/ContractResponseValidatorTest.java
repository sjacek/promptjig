package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Response policy tests keep authored schemas separate from deliberately malformed model values. */
class ContractResponseValidatorTest {
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["name","codes"],"properties":{
              "name":{"type":["string","null"]},
              "degree":{"type":["string","null"],"enum":["MILD","SEVERE",null]},
              "date":{"type":["string","null"],"format":"date"},
              "timestamp":{"type":["string","null"],"format":"date-time"},
              "pattern":{"type":["string","null"],"pattern":"^[A-Z]+$"},
              "enabled":{"type":["boolean","null"]},
              "count":{"type":["integer","null"]},
              "codes":{"type":"array","items":{"type":"string","enum":["A","B"]}},
              "items":{"type":"array","items":{"$ref":"#/$defs/item"}},
              "child":{"$ref":"#/$defs/item"}},
            "$defs":{"item":{"type":"object","additionalProperties":false,"required":["value"],
              "properties":{"value":{"type":["string","null"]}}}}}
            """;

    private static ContractResponseValidator validator() throws Exception {
        return ContractResponseValidator.builder().schema(PromptContract.JSON.readTree(SCHEMA)).build();
    }

    /** Require an accepted tree before checking its fields; rejected results must never expose partial data. */
    private static JsonNode normalized(@NonNull ResponseValidation result) {
        var normalized = result.normalized();
        if (normalized == null) throw new AssertionError("Expected an accepted response: " + result.violations());
        return normalized;
    }

    @Test void repairsOnlyInvalidNullableScalarsAndPreservesOtherValues() throws Exception {
        var result = validator().read("""
                {"name":"Synthetic","codes":["A","bad","B",42],"degree":"OTHER","date":"2025-02-30",
                 "timestamp":"yesterday","pattern":"secret42","enabled":"true","count":1.5}
                """, false);
        assertTrue(result.accepted());
        assertEquals(List.of("A", "B"), PromptContract.JSON.convertValue(normalized(result).required("codes"), List.class));
        assertEquals("Synthetic", normalized(result).path("name").asText());
        for (String key : List.of("degree", "date", "timestamp", "pattern", "enabled", "count"))
            assertTrue(normalized(result).required(key).isNull(), key);
        // A numeric enum item violates both its type and enum, preserving both rules.
        assertEquals(9, result.violations().size());
        assertFalse(result.violations().toString().contains("secret42"));
        assertFalse(result.toString().contains("Synthetic"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "[]", "42", "\"text\"", "{} {}", "```json\n{}\n```", "{", "{\"name\":null,\"name\":null}"})
    void rejectsNonObjectMalformedDuplicateAndTrailingJson(String raw) throws Exception {
        var result = validator().read(raw, false);
        assertFalse(result.accepted()); assertNull(result.normalized()); assertFalse(result.violations().isEmpty());
    }

    @Test void fillsRequiredFieldsButLeavesOptionalAbsenceWithoutFindings() throws Exception {
        var result = validator().read("{}", false);
        assertTrue(result.accepted()); assertTrue(normalized(result).required("name").isNull());
        assertEquals(0, normalized(result).required("codes").size()); assertFalse(normalized(result).has("degree"));
        assertEquals(List.of("/codes", "/name"), result.violations().stream().map(ResponseViolation::pointer).toList());
        assertTrue(result.violations().stream().allMatch(v -> v.action() == ResponseViolation.Action.FILLED));
    }

    @Test void removesWholeInvalidArrayObjectsWithoutRepairingThemAndUsesOriginalIndices() throws Exception {
        var result = validator().read("""
                {"name":null,"codes":[],"items":[{}, {"value":"keep"}, {"value":4}, {"value":"drop","sensitive-key":true}, {"value":null}]}
                """, false);
        assertTrue(result.accepted()); assertEquals(2, normalized(result).path("items").size());
        assertEquals("keep", normalized(result).at("/items/0/value").asText());
        assertEquals(List.of("/items/0/value", "/items/2/value", "/items/3"),
                result.violations().stream().map(ResponseViolation::pointer).toList());
        assertTrue(result.violations().stream().allMatch(v -> v.action() == ResponseViolation.Action.REMOVED));
        assertFalse(result.violations().toString().contains("sensitive-key"));
    }

    @ParameterizedTest @ValueSource(strings = {"{\"name\":null,\"codes\":null}", "{\"name\":null,\"codes\":[],\"child\":\"bad\"}"})
    void rejectsWrongContainerStructure(String raw) throws Exception { assertFalse(validator().read(raw, false).accepted()); }

    @Test void rejectsMissingNonNullableRequiredScalarAndDoesNotExposePartialTree() throws Exception {
        var schema = PromptContract.JSON.readTree(SCHEMA);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("name")).put("type", "string");
        assertFalse(ContractResponseValidator.builder().schema(schema).build().read("{}", false).accepted());
    }

    @Test void strictRejectsEveryKindOfRepair() throws Exception {
        for (String raw : List.of("{}", "{\"name\":3,\"codes\":[]}", "{\"name\":null,\"codes\":[\"bad\"]}",
                "{\"name\":null,\"codes\":[],\"unknown\":true}")) {
            var result = validator().read(raw, true);
            assertFalse(result.accepted(), raw);
            assertTrue(result.violations().stream().allMatch(v -> v.action() == ResponseViolation.Action.REJECTED));
        }
        assertTrue(validator().read("{\"name\":null,\"codes\":[]}", true).accepted());
    }

    @Test void redactsUnknownNamesCountsUnicodeAndEscapesAuthoredPointers() throws Exception {
        var result = validator().read("{\"name\":null,\"codes\":[],\"patient-secret\":\"😀x\"}", false);
        var finding = result.violations().get(0);
        assertEquals("", finding.pointer()); assertEquals(2, finding.valueLength());
        assertFalse(finding.toString().contains("patient-secret")); assertFalse(finding.toString().contains("😀"));
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"properties":{"a/b~c":{"type":["string","null"]}}}
                """);
        var escaped = ContractResponseValidator.builder().schema(schema).build().read("{\"a/b~c\":42}", false);
        assertEquals("/a~1b~0c", escaped.violations().get(0).pointer());
    }

    @Test void nullableTypeDoesNotAllowNullExcludedByEnumAndSchemaIsIsolated() throws Exception {
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"properties":{"value":{"type":["string","null"],"enum":["A"]}}}
                """);
        var validator = ContractResponseValidator.builder().schema(schema).build();
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema).removeAll();
        ((com.fasterxml.jackson.databind.node.ObjectNode) validator.schema()).removeAll();
        assertFalse(validator.read("{\"value\":\"B\"}", false).accepted());
    }

    @Test void canonicalizesIntegralDecimalWithoutScalarCoercion() throws Exception {
        var result = validator().read("{\"name\":null,\"codes\":[],\"count\":1.0}", false);
        assertTrue(result.accepted()); assertTrue(normalized(result).path("count").isIntegralNumber());
        assertTrue(result.violations().isEmpty());
    }

    @Test void repairsNestedFieldsButRejectsInvalidStructureAndHandlesRecursiveDefinitions() throws Exception {
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"properties":{"node":{"$ref":"#/$defs/node"}},
                 "$defs":{"node":{"type":["object","null"],"additionalProperties":false,
                   "required":["name"],"properties":{"name":{"type":["string","null"]},
                   "child":{"$ref":"#/$defs/node"}}}}}
                """);
        var validator = ContractResponseValidator.builder().schema(schema).build();
        var result = validator.read("{\"node\":{\"name\":42,\"child\":{\"child\":null}}}", false);
        assertTrue(result.accepted());
        assertTrue(normalized(result).at("/node/name").isNull());
        assertTrue(normalized(result).at("/node/child/name").isNull());
        assertEquals(List.of("/node/child/name", "/node/name"), result.violations().stream().map(ResponseViolation::pointer).toList());
        assertFalse(validator.read("{\"node\":{\"name\":null,\"child\":false}}", false).accepted());
    }

    @Test void missingRequiredNullableArraysBecomeEmptyButExplicitAllowedNullRemainsValid() throws Exception {
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["values"],
                 "properties":{"values":{"type":["array","null"],"items":{"type":["string","null"]}}}}
                """);
        var validator = ContractResponseValidator.builder().schema(schema).build();
        assertTrue(normalized(validator.read("{}", false)).path("values").isArray());
        var explicit = validator.read("{\"values\":null}", false);
        assertTrue(explicit.accepted()); assertTrue(explicit.violations().isEmpty());
        var items = validator.read("{\"values\":[null,42,\"keep\"]}", false);
        assertEquals(2, normalized(items).path("values").size());
        assertTrue(normalized(items).at("/values/0").isNull());
        assertEquals("/values/1", items.violations().get(0).pointer());
    }

    @Test void preservesValidValuesWithoutTrimmingOrAddingOptionalProperties() throws Exception {
        String raw = """
                {"name":"  Synthetic  ","codes":["B","A"],"date":"2024-02-29",
                 "timestamp":"2026-11-11T12:30:00+05:30","pattern":"ABC","enabled":false,"count":-42}
                """;
        for (boolean strict : List.of(false, true)) {
            var result = validator().read(raw, strict);
            assertTrue(result.accepted()); assertTrue(result.violations().isEmpty());
            // Integer nodes are canonicalized to BigInteger; their JSON values must remain identical.
            assertEquals(PromptContract.JSON.readTree(raw).toString(), normalized(result).toString());
            assertFalse(normalized(result).has("degree"));
        }
    }

    @Test void finalValidationRejectsARequiredArrayRepairThatItsEnumDisallows() throws Exception {
        // This legal restricted schema admits null only; filling the missing array with [] must not bypass enum.
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["values"],"properties":{
                  "values":{"type":["array","null"],"enum":[null],"items":{"type":"string"}}}}
                """);
        var result = ContractResponseValidator.builder().schema(schema).build().read("{}", false);
        assertFalse(result.accepted()); assertNull(result.normalized());
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("required") && v.action() == ResponseViolation.Action.FILLED));
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("enum") && v.action() == ResponseViolation.Action.REJECTED));
    }

    @Test void fatalErrorsStillCollectOtherFindingsInStableOrder() throws Exception {
        var schema = PromptContract.JSON.readTree(SCHEMA);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("name")).put("type", "string");
        var validator = ContractResponseValidator.builder().schema(schema).build();
        String first = "{\"name\":42,\"codes\":[\"bad\"],\"date\":\"bad\",\"synthetic-secret-key\":true}";
        String reordered = "{\"synthetic-secret-key\":true,\"date\":\"bad\",\"codes\":[\"bad\"],\"name\":42}";
        for (boolean strict : List.of(false, true)) {
            var result = validator.read(first, strict);
            assertFalse(result.accepted()); assertNull(result.normalized());
            assertEquals(List.of("", "/codes/0", "/date", "/name"), result.violations().stream().map(ResponseViolation::pointer).toList());
            assertEquals(result.violations(), validator.read(reordered, strict).violations());
            assertFalse(result.violations().toString().contains("synthetic-secret-key"));
            assertThrows(UnsupportedOperationException.class, () -> result.violations().clear());
            if (strict) assertTrue(result.violations().stream().allMatch(v -> v.action() == ResponseViolation.Action.REJECTED));
        }
    }

    @Test void resolvesAliasChainsForScalarRepairsAndRequiredFields() throws Exception {
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["date"],
                 "properties":{"date":{"$ref":"#/$defs/alias"}},
                 "$defs":{"alias":{"$ref":"#/$defs/date"},"date":{"type":["string","null"],"format":"date"}}}
                """);
        var validator = ContractResponseValidator.builder().schema(schema).build();
        var invalid = validator.read("{\"date\":\"not-a-date\"}", false);
        assertTrue(invalid.accepted()); assertTrue(normalized(invalid).path("date").isNull());
        assertEquals("format", invalid.violations().get(0).rule());
        var missing = validator.read("{}", false);
        assertTrue(missing.accepted()); assertTrue(normalized(missing).path("date").isNull());
        assertEquals(ResponseViolation.Action.FILLED, missing.violations().get(0).action());
    }

    @ParameterizedTest @MethodSource("valueSummaries")
    void unknownPropertyFindingsDescribeTypesAndLengthsWithoutEchoingValues(String json, String type, int length) throws Exception {
        var result = validator().read("{\"name\":null,\"codes\":[],\"synthetic-secret-key\":" + json + "}", false);
        assertTrue(result.accepted()); assertEquals(1, result.violations().size());
        var finding = result.violations().get(0);
        assertEquals("", finding.pointer()); assertEquals("additionalProperties", finding.rule());
        assertEquals(ResponseViolation.Action.REMOVED, finding.action());
        assertEquals(type, finding.valueType()); assertEquals(length, finding.valueLength());
        assertFalse(finding.toString().contains("synthetic-secret"));
        assertFalse(normalized(result).has("synthetic-secret-key"));
    }

    private static java.util.stream.Stream<Arguments> valueSummaries() {
        return java.util.stream.Stream.of(Arguments.of("\"😀x\"", "string", 2),
                Arguments.of("[1,2]", "array", 2), Arguments.of("{\"synthetic-secret\":true}", "object", 1),
                Arguments.of("true", "boolean", 4), Arguments.of("-42", "number", 3), Arguments.of("null", "null", 0));
    }
}
