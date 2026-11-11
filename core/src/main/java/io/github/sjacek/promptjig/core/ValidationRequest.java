package io.github.sjacek.promptjig.core;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Locations for one validation pass. Relative paths are resolved against {@code projectDirectory}.
 *
 * @param projectDirectory owning project, resolved to an absolute normalized path by the engine
 * @param sourceDirectory directory scanned recursively for {@code .prompt.yaml} files, inside the project
 * @param lockFile version history inside the project and outside the source directory
 * @param baselineLockFile optional trusted history to preserve; may be outside the project, or {@code null} to skip
 */
public record ValidationRequest(Path projectDirectory, Path sourceDirectory, Path lockFile,
                                @Nullable Path baselineLockFile) { }
