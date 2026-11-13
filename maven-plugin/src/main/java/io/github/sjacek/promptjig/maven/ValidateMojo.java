package io.github.sjacek.promptjig.maven;

import io.github.sjacek.promptjig.core.ContractEngine;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Validates resources and immutable version history before compilation, without rewriting the lock. */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public final class ValidateMojo extends AbstractPromptjigMojo {
    /** Constructs the goal for Maven injection. */
    public ValidateMojo() { }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        run("validate", new ContractEngine()::validate);
    }
}
