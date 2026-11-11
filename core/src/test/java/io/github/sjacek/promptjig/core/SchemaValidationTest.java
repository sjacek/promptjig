package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SchemaValidationTest {
    @Test
    void dateTimeRequiresOffsetForGeneratedOffsetDateTime() throws Exception {
        var schema = SchemaValidation.compile(PromptContract.JSON.readTree("{\"type\": \"string\", \"format\": \"date-time\"}"));
        assertTrue(schema.validate(PromptContract.JSON.getNodeFactory().textNode("2026-11-10T12:30:00+01:00")).isEmpty());
        assertTrue(schema.validate(PromptContract.JSON.getNodeFactory().textNode("2026-11-10T11:30:00Z")).isEmpty());
        assertFalse(schema.validate(PromptContract.JSON.getNodeFactory().textNode("2026-11-10T12:30:00")).isEmpty());
    }

    @Test
    void formatAssertionsMustBeExplicitAndDatesAreNotJustStrings() throws Exception {
        var schema = PromptContract.JSON.readTree("{\"type\": \"string\", \"format\": \"date\"}");
        var value = PromptContract.JSON.getNodeFactory().textNode("2026-02-30");
        assertTrue(com.networknt.schema.SchemaRegistry.withDefaultDialect(
                com.networknt.schema.SpecificationVersion.DRAFT_2020_12).getSchema(schema).validate(value).isEmpty());
        assertTrue(SchemaValidation.registry(false).getSchema(schema).validate(value).isEmpty());
        assertEquals("format", SchemaValidation.compile(schema).validate(value).get(0).getKeyword());
    }

    @Test
    void requiredAndUnknownPropertyErrorsPointToParentAndExposePropertySeparately() throws Exception {
        var resource = getClass().getResource("/prompts/patient/disability/certificate-extraction.prompt.yaml");
        if (resource == null) throw new AssertionError("Missing certificate contract fixture");
        var contract = PromptContract.load(Path.of(resource.toURI()));
        ObjectNode value = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/valid-output.json")).deepCopy();
        value.remove("patientSurname");
        value.put("unknown/key~name", true);
        var errors = SchemaValidation.compile(contract.outputSchema()).validate(value);
        assertEquals(2, errors.size());
        errors.forEach(error -> assertEquals("", error.getInstanceLocation().toString()));
        assertEquals(Set.of("patientSurname", "unknown/key~name"), errors.stream()
                .map(com.networknt.schema.Error::getProperty).collect(Collectors.toSet()));
    }

    @Test
    void localRefsKeepInstancePointersAndEscapeArrayPropertyNames() throws Exception {
        var schema = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/nested-schema.json"));
        var value = PromptContract.JSON.readTree(getClass().getResourceAsStream("/fixtures/nested-invalid.json"));
        var errors = SchemaValidation.compile(schema).validate(value);
        assertTrue(errors.stream().anyMatch(error -> error.getKeyword().equals("format")
                && error.getInstanceLocation().toString().equals("/a~1b~0c/0/issuedAt")));
        assertTrue(errors.stream().anyMatch(error -> error.getKeyword().equals("required")
                && error.getInstanceLocation().toString().equals("/a~1b~0c/1")
                && error.getProperty().equals("degree")));
    }
}
