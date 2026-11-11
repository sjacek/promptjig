package io.github.sjacek.promptjig.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.io.IOException;

/** Explicit source-controlled mutation: never cached and never implicitly invoked by check. */
@DisableCachingByDefault(because = "Appends immutable source-controlled version history only when explicitly requested.")
public abstract class PromptContractsLock extends PromptContractsTask {
    /** Ensures every explicit invocation rechecks history even when its inputs appear unchanged. */
    public PromptContractsLock() { getOutputs().upToDateWhen(task -> false); }

    /** {@return the history file this task may append to after successful validation} */
    @OutputFile
    public RegularFileProperty getUpdatedLockFile() { return getLockFile(); }

    /**
     * Validates and appends new versions in the isolated worker.
     * @throws IOException if the private worker runtime cannot be prepared
     */
    @TaskAction
    public void updateLock() throws IOException { submit(true); }
}
