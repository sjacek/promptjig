package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Immutable historical entries; only new prompt/version pairs may be appended. */
final class LockFile {
    final ObjectNode root;
    final SourceDocument source;

    private LockFile(ObjectNode root, SourceDocument source) {
        this.root = root;
        this.source = source;
    }

    static @Nullable LockFile read(Path file, String name, String rule, boolean allowMissing, List<Diagnostic> diagnostics) {
        try {
            if (!Files.exists(file) && allowMissing) {
                var node = PromptContract.JSON.createObjectNode().put("lockVersion", 1);
                node.putObject("prompts");
                return new LockFile(node, SourceDocument.read(name, node.toString()));
            }
            if (Files.isSymbolicLink(file)) throw new IOException("Symbolic lock files are not supported");
            String text = Files.readString(file);
            JsonNode node = PromptContract.JSON.readTree(text);
            SourceDocument source = SourceDocument.read(name, text);
            if (node == null || !node.isObject() || node.size() != 2 || !node.path("lockVersion").isIntegralNumber()
                    || !node.path("lockVersion").bigIntegerValue().equals(java.math.BigInteger.ONE) || !node.path("prompts").isObject()) {
                diagnostics.add(source.error(rule, "", "Expected lockVersion: 1 and a prompts object"));
                return null;
            }
            int before = diagnostics.size();
            for (var prompt : node.path("prompts").properties()) {
                String pointer = "/prompts/" + SourceDocument.escape(prompt.getKey());
                if (!prompt.getKey().matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*") || !prompt.getValue().isObject()) {
                    diagnostics.add(source.error(rule, pointer, "Expected a prompt id and version/fingerprint object"));
                    continue;
                }
                for (var version : prompt.getValue().properties()) {
                    boolean validVersion;
                    try { validVersion = version.getKey().matches("[1-9][0-9]*") && Integer.parseInt(version.getKey()) > 0; }
                    catch (NumberFormatException exception) { validVersion = false; }
                    if (!validVersion || !version.getValue().isTextual()
                            || !version.getValue().asText().matches("sha256:[0-9a-f]{64}")) diagnostics.add(source.error(rule,
                            pointer + "/" + SourceDocument.escape(version.getKey()), "Expected a positive int version and sha256 fingerprint"));
                }
            }
            return diagnostics.size() == before ? new LockFile((ObjectNode) node, source) : null;
        } catch (IOException exception) {
            diagnostics.add(ContractReader.syntax(rule, name, exception));
            return null;
        }
    }

    void check(List<PromptContract> contracts, boolean allowNew, List<Diagnostic> diagnostics) {
        for (PromptContract contract : contracts) {
            String id = contract.document().path("id").asText();
            String version = Integer.toString(contract.document().path("version").asInt());
            JsonNode value = root.path("prompts").path(id).path(version);
            String pointer = "/prompts/" + SourceDocument.escape(id) + "/" + version;
            if (value.isMissingNode()) {
                if (!allowNew) diagnostics.add(source.error("lock", pointer,
                        "New version has no fingerprint; run promptContractsLock (Maven: promptjig:lock)"));
            } else if (!value.asText().equals(contract.fingerprint())) diagnostics.add(source.error("lock", pointer,
                    "Content changed for " + id + " v" + version + "; increment version before updating the lock"));
        }
    }

    void checkBaseline(LockFile baseline, List<Diagnostic> diagnostics) {
        for (var prompt : baseline.root.path("prompts").properties()) for (var version : prompt.getValue().properties()) {
            String pointer = "/prompts/" + SourceDocument.escape(prompt.getKey()) + "/" + version.getKey();
            if (!root.at(pointer).equals(version.getValue())) diagnostics.add(source.error("lock-baseline", pointer,
                    "Baseline entry must exist with its original fingerprint: " + prompt.getKey() + " v" + version.getKey()));
        }
    }

    void append(Path file, List<PromptContract> contracts) throws IOException {
        ObjectNode prompts = (ObjectNode) root.path("prompts");
        boolean changed = !Files.exists(file);
        for (PromptContract contract : contracts) {
            String id = contract.document().path("id").asText();
            String version = Integer.toString(contract.document().path("version").asInt());
            ObjectNode versions = prompts.has(id) ? (ObjectNode) prompts.path(id) : prompts.putObject(id);
            if (!versions.has(version)) { versions.put(version, contract.fingerprint()); changed = true; }
        }
        // Preserve formatting and modification time when there are no new versions to append.
        if (!changed) return;
        ObjectNode ordered = PromptContract.JSON.createObjectNode().put("lockVersion", 1);
        ObjectNode sortedPrompts = ordered.putObject("prompts");
        TreeSet<String> ids = new TreeSet<>();
        prompts.fieldNames().forEachRemaining(ids::add);
        for (String id : ids) {
            ObjectNode versions = sortedPrompts.putObject(id);
            prompts.path(id).properties().stream().sorted(java.util.Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey())))
                    .forEach(entry -> versions.set(entry.getKey(), entry.getValue()));
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".promptjig-", ".tmp");
        try {
            // A sibling stays on the same filesystem for atomic replacement; cleanup only removes our own file.
            Files.writeString(temporary, PromptContract.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ordered) + "\n");
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
