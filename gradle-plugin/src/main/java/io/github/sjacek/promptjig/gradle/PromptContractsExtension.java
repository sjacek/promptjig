package io.github.sjacek.promptjig.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import javax.inject.Inject;

/** Lazy input locations for the owning project. */
public abstract class PromptContractsExtension {
    private final PromptGenerationExtension generate;

    /**
     * Creates the managed DSL extension; the plugin supplies property conventions lazily.
     * @param objects Gradle's injected factory for nested managed settings
     */
    @Inject
    public PromptContractsExtension(ObjectFactory objects) { generate = objects.newInstance(PromptGenerationExtension.class); }

    /** {@return generation settings; generation is wired into compilation only when enabled} */
    public PromptGenerationExtension getGenerate() { return generate; }

    /**
     * Configures code generation and enables Java main source wiring unless explicitly disabled in the block.
     * @param action configuration of package, Jackson family and optional resource prefix
     */
    public void generate(Action<? super PromptGenerationExtension> action) {
        generate.getEnabled().set(true);
        action.execute(generate);
    }

    /** {@return recursive contract/template source directory; defaults to {@code src/main/resources/prompts}} */
    public abstract DirectoryProperty getSource();

    /** {@return append-only history inside the project, outside source; defaults to {@code prompt-contracts.lock.json}} */
    public abstract RegularFileProperty getLockFile();

    /**
     * Configures historical entries that the current lock must retain unchanged.
     * @return optional trusted history to preserve; defaults to the {@code promptjig.baselineLockFile}
     * Gradle property when supplied, otherwise unset. A configured file must exist and may be outside the project.
     */
    public abstract RegularFileProperty getBaselineLockFile();
}
