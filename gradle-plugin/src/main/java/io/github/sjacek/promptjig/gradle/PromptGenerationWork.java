package io.github.sjacek.promptjig.gradle;

import io.github.sjacek.promptjig.core.GenerationOptions;
import io.github.sjacek.promptjig.core.JavaSourceGenerator;
import io.github.sjacek.promptjig.core.ValidationRequest;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import java.io.IOException;

/** Isolated worker for generation; only managed filesystem locations and strings cross the boundary. */
public abstract class PromptGenerationWork implements WorkAction<PromptGenerationWork.Parameters> {
    /** Creates an action whose parameters are injected by Gradle. */
    public PromptGenerationWork() { }

    /** Inputs and outputs of one generation invocation. */
    public interface Parameters extends WorkParameters {
        /** {@return project root for path containment} */
        DirectoryProperty getProjectDirectory();
        /** {@return recursive contract source root} */
        DirectoryProperty getSource();
        /** {@return current version history} */
        RegularFileProperty getLockFile();
        /** {@return optional trusted version history} */
        RegularFileProperty getBaselineLockFile();
        /** {@return JSON/text report destination} */
        DirectoryProperty getReportDirectory();
        /** {@return generated source destination} */
        DirectoryProperty getOutputDirectory();
        /** {@return Java package name} */
        Property<String> getPackageName();
        /** {@return target Jackson family} */
        Property<String> getJackson();
        /** {@return classpath prefix for authored contracts} */
        Property<String> getResourcePrefix();
    }

    @Override
    public void execute() {
        var parameters = getParameters();
        var request = new ValidationRequest(parameters.getProjectDirectory().get().getAsFile().toPath(),
                parameters.getSource().get().getAsFile().toPath(), parameters.getLockFile().get().getAsFile().toPath(),
                parameters.getBaselineLockFile().isPresent() ? parameters.getBaselineLockFile().get().getAsFile().toPath() : null);
        try {
            var result = new JavaSourceGenerator().generate(request,
                    new GenerationOptions(parameters.getPackageName().get(), parameters.getJackson().get(), parameters.getResourcePrefix().get()),
                    parameters.getOutputDirectory().get().getAsFile().toPath());
            result.writeReports(parameters.getReportDirectory().get().getAsFile().toPath());
            for (var diagnostic : result.diagnostics()) {
                if (diagnostic.severity() == io.github.sjacek.promptjig.core.Diagnostic.Severity.ERROR)
                    Logging.getLogger(PromptGenerationWork.class).error(diagnostic.text());
            }
            if (!result.valid()) throw new GradleException("Prompt contract generation failed; see the JSON/text reports.");
        } catch (IOException exception) {
            throw new GradleException("Cannot read or write Promptjig generated sources", exception);
        }
    }
}
