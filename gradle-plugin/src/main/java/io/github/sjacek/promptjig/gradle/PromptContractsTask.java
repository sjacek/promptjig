package io.github.sjacek.promptjig.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;

/** Only declared paths and flags cross the worker boundary; no Project/model instances do. */
@DisableCachingByDefault(because = "Common task inputs; concrete tasks declare their caching behavior.")
public abstract class PromptContractsTask extends DefaultTask {
    /** Creates a task whose input and output conventions are supplied by {@link PromptjigPlugin}. */
    public PromptContractsTask() { }

    /** {@return owning project directory, used as the root for portable diagnostic paths} */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    /** {@return configured source root; its files and relative path are tracked by separate inputs} */
    @Internal
    public abstract DirectoryProperty getSource();

    /** {@return all source files, including templates whose changes must invalidate validation} */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getSources() { return getSource().getAsFileTree(); }

    /** {@return normalized project-relative source root, also used in empty-source diagnostics} */
    @Input
    public String getSourcePath() { return relative(getSource().get().getAsFile().toPath()); }

    /** {@return source-controlled history; exposed separately as an input and, for the lock task, an output} */
    @Internal
    public abstract RegularFileProperty getLockFile();

    // InputFiles intentionally allows a missing lock for the first explicit lock invocation.
    /** {@return lock bytes as an input that permits absence, so the engine can report or initialize a missing lock} */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public Provider<RegularFile> getLockInput() { return getLockFile(); }

    /** {@return normalized project-relative lock location used in reports} */
    @Input
    public String getLockPath() { return relative(getLockFile().get().getAsFile().toPath()); }

    /** {@return optional baseline location; configured-but-missing files are errors from the engine} */
    @Internal
    public abstract RegularFileProperty getBaselineLockFile();

    /** {@return optional baseline bytes, tracked independently of the current lock} */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public Provider<RegularFile> getBaselineInput() { return getBaselineLockFile(); }

    /** {@return portable baseline label, or an empty string when the baseline check is not configured} */
    @Input
    public String getBaselinePath() {
        if (!getBaselineLockFile().isPresent()) return "";
        Path baseline = getBaselineLockFile().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path project = getProjectDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        return baseline.startsWith(project) ? relative(baseline) : "baseline/" + baseline.getFileName();
    }

    /** {@return destination for JSON/text reports; validation and lock tasks use separate directories} */
    @OutputDirectory
    public abstract DirectoryProperty getReportDirectory();

    /** {@return unpacked worker runtime, excluded from relocatable cache outputs and recreated when needed} */
    @LocalState
    public abstract DirectoryProperty getRuntimeDirectory();

    /** {@return process-lock file shared by cooperating lock writers; contains no validation input} */
    @Internal
    public abstract RegularFileProperty getCoordinationFile();

    /** {@return Gradle's injected executor for classloader-isolated work} */
    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /**
     * Unpacks the private runtime, submits managed values, and waits for validation and reports to finish.
     * @param update whether to append new versions after a successful validation pass
     * @throws IOException if the bundled runtime cannot be read or unpacked
     */
    protected final void submit(boolean update) throws IOException {
        var runtime = RuntimeBundle.unpack(getRuntimeDirectory().get().getAsFile().toPath());
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(runtime));
        queue.submit(PromptContractsWork.class, parameters -> {
            parameters.getProjectDirectory().set(getProjectDirectory());
            parameters.getSource().set(getSource());
            parameters.getLockFile().set(getLockFile());
            parameters.getBaselineLockFile().set(getBaselineLockFile());
            parameters.getReportDirectory().set(getReportDirectory());
            parameters.getCoordinationFile().set(getCoordinationFile());
            parameters.getUpdate().set(update);
        });
        queue.await();
    }

    private String relative(Path file) {
        var project = getProjectDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        var path = file.toAbsolutePath().normalize();
        if (!path.startsWith(project)) throw new IllegalArgumentException("Promptjig source and lock must be inside the project");
        return project.relativize(path).toString().replace('\\', '/');
    }
}
