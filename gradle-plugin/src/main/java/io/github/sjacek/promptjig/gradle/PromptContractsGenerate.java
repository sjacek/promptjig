package io.github.sjacek.promptjig.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import java.io.IOException;

/** Cacheable Java source generation with the same isolated runtime and validation inputs as the validator. */
@CacheableTask
public abstract class PromptContractsGenerate extends PromptContractsTask {
    /** Creates a generator whose conventions are supplied by the plugin. */
    public PromptContractsGenerate() { }

    /** {@return target Java package, included in the cache key} */
    @Input
    public abstract Property<String> getPackageName();

    /** {@return Jackson target family, v2 or v3} */
    @Input
    public abstract Property<String> getJackson();

    /** {@return classpath prefix used by generated RESOURCE constants} */
    @Input
    public abstract Property<String> getResourcePrefix();

    /**
     * Configures the tree owned by the generator; the task provider also carries its producer dependency into main.
     * @return generated source root, defaulting to {@code build/generated/sources/prompt-contracts/java/main}
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Runs validation and generation in a worker without exposing core dependencies to the consuming build.
     * @throws IOException if the private runtime cannot be unpacked
     */
    @TaskAction
    public void generateSources() throws IOException {
        var runtime = RuntimeBundle.unpack(getRuntimeDirectory().get().getAsFile().toPath());
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(runtime));
        queue.submit(PromptGenerationWork.class, parameters -> {
            parameters.getProjectDirectory().set(getProjectDirectory());
            parameters.getSource().set(getSource());
            parameters.getLockFile().set(getLockFile());
            parameters.getBaselineLockFile().set(getBaselineLockFile());
            parameters.getReportDirectory().set(getReportDirectory());
            parameters.getOutputDirectory().set(getOutputDirectory());
            parameters.getPackageName().set(getPackageName());
            parameters.getJackson().set(getJackson());
            parameters.getResourcePrefix().set(getResourcePrefix());
        });
        queue.await();
    }
}
