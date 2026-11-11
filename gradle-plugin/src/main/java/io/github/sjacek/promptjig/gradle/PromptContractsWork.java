package io.github.sjacek.promptjig.gradle;

import io.github.sjacek.promptjig.core.ContractEngine;
import io.github.sjacek.promptjig.core.Diagnostic;
import io.github.sjacek.promptjig.core.ValidationRequest;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import java.io.IOException;

/** Executes only inside a classloader-isolated Gradle worker. */
public abstract class PromptContractsWork implements WorkAction<PromptContractsWork.Parameters> {
    /** Creates a work action whose managed parameters are injected by Gradle. */
    public PromptContractsWork() { }

    /** Serializable managed values; no Project, task or engine objects cross the worker boundary. */
    public interface Parameters extends WorkParameters {
        /** {@return owning project root for path containment and portable reports} */
        DirectoryProperty getProjectDirectory();

        /** {@return recursively scanned prompt source directory} */
        DirectoryProperty getSource();

        /** {@return current append-only version history} */
        RegularFileProperty getLockFile();

        /** {@return optional trusted history; an absent property skips baseline validation} */
        RegularFileProperty getBaselineLockFile();

        /** {@return directory receiving reports before validation failures are raised} */
        DirectoryProperty getReportDirectory();

        /** {@return shared process-lock file used only when updating history} */
        RegularFileProperty getCoordinationFile();

        /** {@return true only for the explicitly requested lock task} */
        Property<Boolean> getUpdate();
    }

    @Override
    public void execute() {
        var parameters = getParameters();
        var request = new ValidationRequest(parameters.getProjectDirectory().get().getAsFile().toPath(),
                parameters.getSource().get().getAsFile().toPath(), parameters.getLockFile().get().getAsFile().toPath(),
                parameters.getBaselineLockFile().isPresent() ? parameters.getBaselineLockFile().get().getAsFile().toPath() : null);
        try {
            var engine = new ContractEngine();
            var result = parameters.getUpdate().get() ? engine.updateLock(request, parameters.getCoordinationFile().get().getAsFile().toPath())
                    : engine.validate(request);
            result.writeReports(parameters.getReportDirectory().get().getAsFile().toPath());
            var logger = Logging.getLogger(PromptContractsWork.class);
            for (var diagnostic : result.diagnostics()) {
                if (diagnostic.severity() == Diagnostic.Severity.ERROR) logger.error(diagnostic.text());
                else logger.info(diagnostic.text());
            }
            if (!result.valid()) throw new GradleException("Prompt contract validation failed; see the JSON/text reports.");
        } catch (IOException exception) {
            throw new GradleException("Cannot read or write Promptjig inputs/reports", exception);
        }
    }
}
