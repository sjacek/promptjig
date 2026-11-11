package io.github.sjacek.promptjig.gradle;

import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;
import java.io.IOException;

/** Relocatable validation output depends on templates, contract/lock bytes and the plugin implementation. */
@CacheableTask
public abstract class PromptContractsValidate extends PromptContractsTask {
    /** Creates the read-only validation task registered under the project's {@code check} lifecycle. */
    public PromptContractsValidate() { }

    /**
     * Validates contracts and history without modifying sources or the lock.
     * @throws IOException if the private worker runtime cannot be prepared
     */
    @TaskAction
    public void validateContracts() throws IOException { submit(false); }
}
