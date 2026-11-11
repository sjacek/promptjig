package io.github.sjacek.promptjig.springai;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.Builder;
import lombok.NonNull;

/** Owns worker shutdown through AutoCloseable without requiring Java 19's ExecutorService.close API. */
final class PromptTestExecutor implements AutoCloseable {
    private final ExecutorService executor;

    @Builder
    private PromptTestExecutor(int threads) { executor = Executors.newFixedThreadPool(threads); }

    <T> Future<T> submit(@NonNull Callable<T> action) { return executor.submit(action); }

    @Override public void close() { executor.shutdownNow(); }
}
