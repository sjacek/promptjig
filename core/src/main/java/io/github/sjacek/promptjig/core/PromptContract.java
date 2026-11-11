package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.NonNull;

/**
 * Contract data and v1 fingerprint, independent of build tools and Spring.
 * Direct construction does not validate the document; use {@link #load(Path)} for authored files.
 *
 * @param document parsed contract tree, retained by reference and expected to remain unchanged
 * @param templates template basenames and decoded UTF-8 contents, copied into an unmodifiable map
 */
public record PromptContract(JsonNode document, Map<String, String> templates) {
    /** Shared strict JSON codec for contract, lock and report data; callers must not reconfigure it. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    /**
     * Copies the template map; the Jackson document remains owned by the caller.
     * @param document parsed contract document
     * @param templates decoded template contents by basename
     */
    public PromptContract {
        templates = Map.copyOf(templates);
    }

    /**
     * Loads and validates one contract with templates beside it; does not consult version history.
     *
     * @param file UTF-8 contract file
     * @return validated contract and its referenced templates
     * @throws IllegalArgumentException if the contract or templates cannot be read or validated
     */
    public static PromptContract load(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        List<Diagnostic> diagnostics = new ArrayList<>();
        var result = ContractReader.read(directory, directory, absolute, diagnostics, new java.util.HashMap<>());
        if (result == null) throw new IllegalArgumentException(diagnostics.stream().map(Diagnostic::text)
                .collect(java.util.stream.Collectors.joining("\n")));
        return result;
    }

    /**
     * Validates resource-backed contract text and loads referenced templates through the supplied source.
     * This follows the same structural, schema-subset and ST4 checks as filesystem loading; no lock is read.
     * @param name portable diagnostic location, not an absolute host path
     * @param text complete decoded UTF-8 YAML/JSON contract
     * @param templates loader relative to the owning contract resource
     * @return validated contract snapshot
     * @throws IllegalArgumentException when any contract or template check fails
     */
    public static PromptContract parse(@NonNull String name, @NonNull String text, @NonNull PromptTemplateSource templates) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        var result = ContractReader.read(name, text, templates, diagnostics, new java.util.HashMap<>());
        if (result == null) throw new IllegalArgumentException(diagnostics.stream().map(Diagnostic::text)
                .collect(java.util.stream.Collectors.joining("\n")));
        return result;
    }

    /** {@return sorted unknown/unused input findings, treating {@code contract} as the reserved runtime input} */
    public List<String> parameterViolations() {
        TreeSet<String> declared = new TreeSet<>();
        document.path("input").path("schema").path("properties").fieldNames().forEachRemaining(declared::add);
        if (declared.contains("contract")) return List.of("Reserved input name: contract");
        TreeSet<String> used = new TreeSet<>();
        templates.values().forEach(template -> used.addAll(TemplateParameters.read(template)));
        used.remove("contract");
        TreeSet<String> unknown = new TreeSet<>(used);
        unknown.removeAll(declared);
        TreeSet<String> unused = new TreeSet<>(declared);
        unused.removeAll(used);
        List<String> result = new ArrayList<>();
        unknown.forEach(name -> result.add("Unknown input: " + name));
        unused.forEach(name -> result.add("Unused input: " + name));
        return List.copyOf(result);
    }

    /** {@return the output schema subtree, retained by reference} */
    public JsonNode outputSchema() {
        return document.path("output").path("schema");
    }

    /** {@return canonical output schema JSON shared by generated constants and runtime response format} */
    public String outputSchemaJson() {
        try { return JSON.writeValueAsString(canonical(outputSchema())); }
        catch (IOException exception) { throw new IllegalStateException("Cannot serialize output schema", exception); }
    }

    /**
     * Computes the frozen v1 digest: length-framed canonical JSON followed by named template contents.
     * Object keys and template names are sorted; array order is preserved and template line endings become LF.
     *
     * @return lowercase SHA-256 digest prefixed with {@code sha256:}
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            frame(digest, "promptjig-fingerprint-v1".getBytes(StandardCharsets.UTF_8));
            frame(digest, JSON.writeValueAsBytes(canonical(document)));
            // Contract message order is preserved in JSON. Template payload order is lexical by name.
            for (String name : new TreeSet<>(templates.keySet())) {
                frame(digest, name.getBytes(StandardCharsets.UTF_8));
                frame(digest, templates.get(name).replace("\r\n", "\n").replace('\r', '\n')
                        .getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void frame(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value.length).array());
        digest.update(value);
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> sorted.set(key, canonical(node.path(key))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return node;
    }
}
