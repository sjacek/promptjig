package io.github.sjacek.promptjig.springai;

import io.github.sjacek.promptjig.core.PromptContract;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * A validated contract snapshot. Sensitivity is metadata; it never enables audit or authorizes a model.
 * Use the registry for resource validation. Direct construction assumes the supplied core contract was already
 * validated, and only isolates its mutable document; it does not re-read resources or check version history.
 * @param contract validated contract, defensively copied on construction and access
 */
@Builder
public record RuntimePromptContract(@NonNull PromptContract contract) {
    /**
     * Copies validated data.
     * @param contract validated resource snapshot
     */
    public RuntimePromptContract { contract = new PromptContract(contract.document().deepCopy(), contract.templates()); }

    /** {@return an isolated copy of the contract data} */
    @Override public PromptContract contract() { return new PromptContract(contract.document().deepCopy(), contract.templates()); }
    /** {@return stable business identifier} */
    public String id() { return contract.document().path("id").asText(); }
    /** {@return authored positive contract version} */
    public int version() { return contract.document().path("version").asInt(); }
    /** {@return reference suitable for an application's audit template version} */
    public String versionRef() { return id() + "-v" + version(); }
    /** {@return deterministic digest of this snapshot} */
    public String fingerprint() { return contract.fingerprint(); }
    /** {@return immutable sensitivity labels for application policy and review} */
    public List<String> sensitivity() {
        return java.util.stream.StreamSupport.stream(contract.document().path("sensitivity").spliterator(), false)
                .map(com.fasterxml.jackson.databind.JsonNode::asText).toList();
    }
    /** {@return identity only, without templates or response data} */
    @Override public String toString() { return "RuntimePromptContract[id=" + id() + ", version=" + version() + "]"; }
}
