package io.github.sjacek.promptjig.maven;

import io.github.sjacek.promptjig.core.Diagnostic;
import io.github.sjacek.promptjig.core.ValidationRequest;
import io.github.sjacek.promptjig.core.ValidationResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import lombok.NonNull;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Shared Maven configuration and reporting; the build-tool-independent core owns every contract rule.
 * Maven injects parameter fields after constructing a goal, so their required/optional contracts belong
 * to {@link Parameter}. Lombok checks the required arguments at the adapter's helper boundaries.
 */
public abstract class AbstractPromptjigMojo extends AbstractMojo {
    /** Module root used for path containment and portable diagnostics. */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    protected File projectDirectory;

    /** Recursively scanned UTF-8 prompt resources. */
    @Parameter(property = "promptjig.source", defaultValue = "${project.basedir}/src/main/resources/prompts", required = true)
    protected File source;

    /** Append-only history; validation and generation never create or modify this file. */
    @Parameter(property = "promptjig.lockFile", defaultValue = "${project.basedir}/prompt-contracts.lock.json", required = true)
    protected File lockFile;

    /** Optional trusted baseline, supplied explicitly by the owning repository's CI policy. */
    @Parameter(property = "promptjig.baselineLockFile")
    protected File baselineLockFile;

    /** Root containing goal-specific JSON/text reports, including diagnostics from failed validations. */
    @Parameter(property = "promptjig.reportDirectory", defaultValue = "${project.build.directory}/reports/prompt-contracts", required = true)
    protected File reportDirectory;

    /** Constructs a Maven-injected goal; required configuration is populated before execution. */
    protected AbstractPromptjigMojo() { }

    /**
     * Runs one core operation and writes all diagnostics before reporting a normal build failure.
     * Report writing is a separate I/O step: a failure there cannot undo an already committed core
     * operation, such as an explicit history append. Callers must still treat it as a failed goal.
     * @param goal report subdirectory identifying the invoked operation
     * @param operation validation, explicit history append or generation
     * @throws MojoExecutionException if filesystem access or report writing fails
     * @throws MojoFailureException if the core rejects the contracts or configuration
     */
    protected final void run(@NonNull String goal, @NonNull Operation operation)
            throws MojoExecutionException, MojoFailureException {
        try {
            var result = operation.execute(new ValidationRequest(path(projectDirectory), path(source), path(lockFile),
                    baselineLockFile == null ? null : path(baselineLockFile)));
            result.writeReports(path(reportDirectory).resolve(goal));
            for (var diagnostic : result.diagnostics()) {
                if (diagnostic.severity() == Diagnostic.Severity.ERROR) getLog().error(diagnostic.text());
                else getLog().info(diagnostic.text());
            }
            if (!result.valid()) throw new MojoFailureException("Prompt contract " + goal + " failed; see the JSON/text reports.");
        } catch (IOException failure) {
            throw new MojoExecutionException("Cannot read or write Promptjig inputs/reports", failure);
        }
    }

    /**
     * Converts an injected required path without inspecting files or resolving symlinks eagerly.
     * @param file required Maven parameter
     * @return normalized absolute location
     */
    protected static Path path(@NonNull File file) { return file.toPath().toAbsolutePath().normalize(); }

    /** Core operation, keeping Maven logging and exceptions outside the core library. */
    @FunctionalInterface
    protected interface Operation {
        /**
         * Executes against the same path/baseline configuration used by the Gradle adapter.
         * @param request configured locations
         * @return all findings from the pass
         * @throws IOException if inputs or outputs cannot be accessed
         */
        ValidationResult execute(@NonNull ValidationRequest request) throws IOException;
    }
}
