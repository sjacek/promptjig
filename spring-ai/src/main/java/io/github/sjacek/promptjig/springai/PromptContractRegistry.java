package io.github.sjacek.promptjig.springai;

import io.github.sjacek.promptjig.core.PromptContract;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Immutable registry intended to be loaded by an application startup bean, including from JARs.
 * Loading is atomic: one invalid resource or duplicate ID prevents registry creation.
 * It validates resource contents, not the source-controlled version lock; lock history remains a build concern.
 * @param contracts validated snapshots indexed by business ID
 */
@Builder
public record PromptContractRegistry(@NonNull Map<String, RuntimePromptContract> contracts) {
    /** Default scan of authored prompt resources. */
    public static final String DEFAULT_PATTERN = "classpath*:prompts/**/*.prompt.yaml";

    /**
     * Freezes the registry.
     * @param contracts snapshots keyed by matching contract ID
     */
    public PromptContractRegistry {
        contracts = Map.copyOf(contracts);
        contracts.forEach((id, contract) -> {
            if (!id.equals(contract.id())) throw new IllegalArgumentException("Registry key differs from contract ID");
        });
    }
    /**
     * Loads every default-pattern contract at application startup.
     * Invoke from an eager startup bean; this library does not install auto-configuration or trigger a scan itself.
     * An empty scan is allowed. Applications requiring a particular contract should resolve its ID during startup.
     * @param resolver application classloader-aware resolver
     * @return fully validated registry
     */
    public static PromptContractRegistry load(@NonNull ResourcePatternResolver resolver) {
        return load(resolver, DEFAULT_PATTERN);
    }
    /**
     * Loads an explicit resource subset, useful when multiple contract versions are on the classpath.
     * IDs must be unique within the selected resources, even when their versions differ.
     * @param resolver application resolver
     * @param pattern Spring resource pattern
     * @return fully validated registry
     * @throws IllegalArgumentException if reading, validation or uniqueness checks fail
     */
    public static PromptContractRegistry load(@NonNull ResourcePatternResolver resolver, @NonNull String pattern) {
        try { return load(resolver.getResources(pattern)); }
        catch (IOException exception) { throw new IllegalArgumentException("Cannot scan prompt contract resources", exception); }
    }
    /**
     * Loads resource snapshots and resolves template basenames relative to each contract.
     * Snapshots retain the validated text; subsequent file changes cannot alter rendering under the same fingerprint.
     * @param resources UTF-8 YAML resources, including JAR entries
     * @return fully validated registry
     */
    public static PromptContractRegistry load(@NonNull Resource... resources) {
        Map<String, RuntimePromptContract> contracts = new LinkedHashMap<>();
        for (Resource resource : resources) {
            try {
                var contract = RuntimePromptContract.builder().contract(PromptContract.parse(
                        resource.getFilename() == null ? "contract.prompt.yaml" : resource.getFilename(),
                        utf8(resource), name -> utf8(resource.createRelative(name)))).build();
                if (contracts.putIfAbsent(contract.id(), contract) != null)
                    throw new IllegalArgumentException("Duplicate prompt contract ID: " + contract.id());
            } catch (IOException exception) { throw new IllegalArgumentException("Cannot read prompt contract resource", exception); }
        }
        return PromptContractRegistry.builder().contracts(contracts).build();
    }
    /**
     * Finds a validated snapshot.
     * @param id contract business ID
     * @return matching contract
     * @throws IllegalArgumentException when the ID was not registered
     */
    public RuntimePromptContract get(@NonNull String id) {
        var result = contracts.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown prompt contract ID");
        return result;
    }
    private static String utf8(Resource resource) throws IOException {
        // Relative JAR resources may outlive a loader's own cache; avoid keeping deployment archives open.
        if (resource instanceof org.springframework.core.io.UrlResource url) url.setUseCaches(false);
        try (var stream = resource.getInputStream()) {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(stream.readAllBytes())).toString();
        }
    }
}
