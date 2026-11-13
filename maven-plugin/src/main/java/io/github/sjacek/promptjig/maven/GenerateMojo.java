package io.github.sjacek.promptjig.maven;

import io.github.sjacek.promptjig.core.GenerationOptions;
import io.github.sjacek.promptjig.core.JavaSourceGenerator;
import java.io.File;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Generates validated Java 17 models and registers them as compile sources only after successful generation. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateMojo extends AbstractPromptjigMojo {
    /** Owning module; generated sources never become source roots of another reactor project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /** Package of generated constants, input/output records and nested enums. */
    @Parameter(property = "promptjig.packageName", required = true)
    protected String packageName;

    /** Jackson target: v2 or v3; both use the common Jackson annotation package. */
    @Parameter(property = "promptjig.jackson", defaultValue = "v2", required = true)
    protected String jackson;

    /** Classpath prefix under which the owning project's normal resource processing packages the prompts. */
    @Parameter(property = "promptjig.resourcePrefix", defaultValue = "prompts", required = true)
    protected String resourcePrefix;

    /** Dedicated generated tree; the core prevents overlap with authored sources or version locks. */
    @Parameter(property = "promptjig.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/prompt-contracts", required = true)
    protected File outputDirectory;

    /** Constructs the goal for Maven injection. */
    public GenerateMojo() { }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        run("generate", request -> new JavaSourceGenerator().generate(request,
                new GenerationOptions(packageName, jackson, resourcePrefix), path(outputDirectory)));
        // Register only after validation, file generation and report writing succeed. Existing
        // source roots belong to Maven/the owning project and must be preserved by this addition.
        project.addCompileSourceRoot(path(outputDirectory).toString());
    }
}
