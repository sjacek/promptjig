package io.github.sjacek.promptjig.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePlugin;

/** Registers contract validation under check and an explicitly invoked lock writer. */
public final class PromptjigPlugin implements Plugin<Project> {
    /** Creates the plugin entry point instantiated by Gradle. */
    public PromptjigPlugin() { }

    @Override
    public void apply(Project project) {
        if (org.gradle.util.GradleVersion.current().compareTo(org.gradle.util.GradleVersion.version("9.0.0")) < 0)
            throw new IllegalArgumentException("Promptjig requires Gradle 9.0 or newer");
        project.getPluginManager().apply(BasePlugin.class);
        var extension = project.getExtensions().create("promptContracts", PromptContractsExtension.class);
        var generation = extension.getGenerate();
        generation.getEnabled().convention(false);
        generation.getPackageName().convention("io.github.sjacek.promptjig.generated");
        generation.getJackson().convention("v2");
        generation.getResourcePrefix().convention("prompts");
        extension.getSource().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/prompts"));
        extension.getLockFile().convention(project.getLayout().getProjectDirectory().file("prompt-contracts.lock.json"));
        var projectDirectory = project.getLayout().getProjectDirectory();
        extension.getBaselineLockFile().convention(project.getProviders().gradleProperty("promptjig.baselineLockFile")
                .map(projectDirectory::file));
        var validate = project.getTasks().register("promptContractsValidate", PromptContractsValidate.class, task -> {
            configure(project, task, extension);
            task.setDescription("Validate prompt contracts, templates and immutable version fingerprints.");
            task.setGroup("verification");
        });
        project.getTasks().named("check").configure(task -> task.dependsOn(validate));
        var lock = project.getTasks().register("promptContractsLock", PromptContractsLock.class, task -> {
            configure(project, task, extension);
            task.setDescription("Append fingerprints for new versions; never overwrite historical entries.");
            task.setGroup("build setup");
        });
        // Ordering only: check must never create or repair its own source-controlled input.
        validate.configure(task -> task.mustRunAfter(lock));
        var generate = project.getTasks().register("promptContractsGenerate", PromptContractsGenerate.class, task -> {
            configure(project, task, extension);
            task.setDescription("Generate typed Java inputs, outputs and compile-time prompt contract constants.");
            task.setGroup("code generation");
            task.getPackageName().set(generation.getPackageName());
            task.getJackson().set(generation.getJackson());
            task.getResourcePrefix().set(generation.getResourcePrefix());
            task.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("generated/sources/prompt-contracts/java/main"));
            task.mustRunAfter(lock);
        });
        // Finish the opt-in DSL before wiring sources, regardless of Java/plugin application order.
        project.afterEvaluate(ignored -> project.getPluginManager().withPlugin("java", applied -> {
            if (generation.getEnabled().get()) project.getExtensions().getByType(org.gradle.api.tasks.SourceSetContainer.class)
                    .named("main", main -> main.getJava().srcDir(generate.flatMap(PromptContractsGenerate::getOutputDirectory)));
        }));
    }

    private static void configure(Project project, PromptContractsTask task, PromptContractsExtension extension) {
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        task.getSource().set(extension.getSource());
        task.getLockFile().set(extension.getLockFile());
        task.getBaselineLockFile().set(extension.getBaselineLockFile());
        String report = task instanceof PromptContractsLock ? "lock" : task instanceof PromptContractsGenerate ? "generate" : "validate";
        task.getReportDirectory().convention(project.getLayout().getBuildDirectory().dir("reports/prompt-contracts/" + report));
        task.getRuntimeDirectory().convention(project.getLayout().getBuildDirectory().dir("tmp/" + task.getName() + "/runtime"));
        task.getCoordinationFile().convention(project.getLayout().getBuildDirectory().file("tmp/promptjig/lock-write.lck"));
    }
}
