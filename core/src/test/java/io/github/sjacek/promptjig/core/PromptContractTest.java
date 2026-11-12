package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PromptContractTest {
    @TempDir
    Path temp;

    private PromptContract certificate() throws Exception {
        var resource = getClass().getResource("/prompts/patient/disability/certificate-extraction.prompt.yaml");
        if (resource == null) throw new AssertionError("Missing certificate contract fixture");
        return PromptContract.load(Path.of(resource.toURI()));
    }

    @Test
    void loadsFullCertificateAndChecksParametersAcrossMessages() throws Exception {
        var contract = certificate();
        assertTrue(contract.parameterViolations().isEmpty());
        assertEquals(15, contract.outputSchema().path("properties").size());
        assertTrue(contract.fingerprint().matches("sha256:[0-9a-f]{64}"));
        // Reference vector recorded before the stage-1 implementation; framing v1 must remain compatible.
        assertEquals("sha256:e92be038aef9799dc8818211a16010f270d376b6d718e119d1908149d387484b", contract.fingerprint());
    }

    @Test
    void fingerprintIgnoresObjectKeyOrderAndTemplateLineEndings() throws Exception {
        var contract = certificate();
        ObjectNode reversed = PromptContract.JSON.createObjectNode();
        var fields = new java.util.ArrayList<String>();
        contract.document().fieldNames().forEachRemaining(fields::add);
        java.util.Collections.reverse(fields);
        fields.forEach(key -> reversed.set(key, contract.document().path(key)));
        Map<String, String> crlf = new LinkedHashMap<>();
        contract.templates().forEach((key, text) -> crlf.put(key, text.replace("\r\n", "\n").replace("\n", "\r\n")));
        assertEquals(contract.fingerprint(), new PromptContract(reversed, crlf).fingerprint());
    }

    @Test
    void fingerprintChangesForInstructionSchemaAndMessageOrderChanges() throws Exception {
        var contract = certificate();
        Map<String, String> changed = new LinkedHashMap<>(contract.templates());
        changed.replaceAll((name, text) -> text + "\n");
        assertNotEquals(contract.fingerprint(), new PromptContract(contract.document(), changed).fingerprint());
        ObjectNode document = contract.document().deepCopy();
        ((ObjectNode) document.path("output").path("schema").path("properties")).remove("notes");
        assertNotEquals(contract.fingerprint(), new PromptContract(document, contract.templates()).fingerprint());
        document = contract.document().deepCopy();
        var messages = document.putArray("messages");
        messages.add(contract.document().path("messages").get(1));
        messages.add(contract.document().path("messages").get(0));
        assertNotEquals(contract.fingerprint(), new PromptContract(document, contract.templates()).fingerprint());
    }

    @Test
    void rejectsUnknownAndUnusedParametersAndReservedInput() throws Exception {
        var contract = certificate();
        ObjectNode document = contract.document().deepCopy();
        var properties = (ObjectNode) document.path("input").path("schema").path("properties");
        properties.remove("ocrText");
        properties.putObject("unused").put("type", "string");
        assertEquals(java.util.List.of("Unknown input: ocrText", "Unused input: unused"),
                new PromptContract(document, contract.templates()).parameterViolations());
        properties.putObject("contract").put("type", "string");
        assertEquals(java.util.List.of("Reserved input name: contract"),
                new PromptContract(document, contract.templates()).parameterViolations());
    }

    @Test
    void reservedContractCanBeUnusedAndSystemCanBeStatic() throws Exception {
        var contract = certificate();
        var user = contract.templates().get("certificate-extraction.user.st");
        assertTrue(new PromptContract(contract.document(), Map.of("user.st", user)).parameterViolations().isEmpty());
    }

    @Test
    void rejectsUnknownContractOptionsAndUnsupportedSchemaKeywords() throws Exception {
        var contract = certificate();
        var document = (ObjectNode) contract.document().deepCopy();
        document.put("model", "forbidden");
        assertInvalid(document);
        document = contract.document().deepCopy();
        ((ObjectNode) document.path("output").path("schema")).putArray("oneOf");
        assertInvalid(document);
    }

    @Test
    void rejectsTemplateTraversalAndExternalSchemaReferences() throws Exception {
        var contract = certificate();
        var document = (ObjectNode) contract.document().deepCopy();
        ((ObjectNode) document.path("messages").get(0)).put("template", "../outside.st");
        assertInvalid(document);
        document = contract.document().deepCopy();
        ((ObjectNode) document.path("output").path("schema").path("properties").path("degree"))
                .put("$ref", "https://example.invalid/schema");
        assertInvalid(document);
    }

    @Test
    void rejectsDuplicateYamlKeysAndMultipleDocuments() throws Exception {
        Path file = temp.resolve("broken.prompt.yaml");
        var unreadable = assertThrows(IllegalArgumentException.class, () -> PromptContract.load(file));
        assertTrue(unreadable.getMessage().contains("[contract-schema]"));
        Files.writeString(file, "promptContract: 1\npromptContract: 1\n");
        assertThrows(IllegalArgumentException.class, () -> PromptContract.load(file));
        Files.writeString(file, "promptContract: 1\n---\npromptContract: 1\n");
        assertThrows(IllegalArgumentException.class, () -> PromptContract.load(file));
    }

    @Test
    void rejectsMalformedUtf8Templates() throws Exception {
        var contract = certificate();
        Path file = temp.resolve("certificate.prompt.yaml");
        PromptContract.JSON.writeValue(file.toFile(), contract.document());
        for (String template : contract.templates().keySet()) {
            Files.write(temp.resolve(template), new byte[]{(byte) 0xc3, 0x28});
        }
        var failure = assertThrows(IllegalArgumentException.class, () -> PromptContract.load(file));
        assertTrue(failure.getMessage().contains("template-exists"));
    }

    private void assertInvalid(ObjectNode document) throws Exception {
        Path file = temp.resolve("invalid.prompt.yaml");
        PromptContract.JSON.writeValue(file.toFile(), document);
        assertThrows(IllegalArgumentException.class, () -> PromptContract.load(file));
    }
}
