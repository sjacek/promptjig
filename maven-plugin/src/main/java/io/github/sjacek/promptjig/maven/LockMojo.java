package io.github.sjacek.promptjig.maven;

import io.github.sjacek.promptjig.core.ContractEngine;
import java.io.File;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Explicit append-only lock update. No lifecycle phase binds this goal automatically. */
@Mojo(name = "lock", threadSafe = true)
public final class LockMojo extends AbstractPromptjigMojo {
    /** Cooperating invocations must use this same process-lock location for the module. */
    @Parameter(property = "promptjig.coordinationFile", defaultValue = "${project.build.directory}/promptjig/lock-write.lck", required = true)
    protected File coordinationFile;

    /** Constructs the goal for Maven injection. */
    public LockMojo() { }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        run("lock", request -> new ContractEngine().updateLock(request, path(coordinationFile)));
    }
}
