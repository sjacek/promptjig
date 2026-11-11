package io.github.sjacek.promptjig.core;

/**
 * Java generation settings; invalid values produce {@code generate} diagnostics before any output changes.
 *
 * @param packageName Java package for all generated top-level types
 * @param jackson target Jackson family, {@code v2} or {@code v3}; both use the shared Jackson annotations
 * @param resourcePrefix classpath prefix for the source root, such as {@code prompts}, without a leading slash
 */
public record GenerationOptions(String packageName, String jackson, String resourcePrefix) {
    /**
     * Uses the conventional classpath prefix {@code prompts}.
     * @param packageName Java package for the generated types
     * @param jackson target Jackson family, {@code v2} or {@code v3}
     */
    public GenerationOptions(String packageName, String jackson) { this(packageName, jackson, "prompts"); }
}
