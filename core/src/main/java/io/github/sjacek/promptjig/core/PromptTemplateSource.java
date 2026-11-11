package io.github.sjacek.promptjig.core;

import java.io.IOException;

/** Loads a template next to its owning contract, without requiring a filesystem path. */
@FunctionalInterface
public interface PromptTemplateSource {
    /**
     * Reads a validated v1 template basename; callers must decode bytes as strict UTF-8.
     * @param name basename declared by the contract, with no directory traversal
     * @return complete template text
     * @throws IOException if the template is unavailable or malformed
     */
    String read(String name) throws IOException;
}
