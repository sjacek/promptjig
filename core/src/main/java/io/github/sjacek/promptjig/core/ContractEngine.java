package io.github.sjacek.promptjig.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Build-tool-independent validation and append-only lock updates. No model calls or remote lookups. */
public final class ContractEngine {
    /** Creates a stateless engine; each invocation rereads its declared inputs. */
    public ContractEngine() { }

    /**
     * Checks contracts and version history without creating or modifying a lock.
     * Invalid source content is returned as diagnostics, including failures in other contract files.
     *
     * @param request paths and optional baseline for this pass
     * @return aggregated findings and the number of discovered contracts
     * @throws IOException if filesystem traversal or access prevents the pass from completing
     */
    public ValidationResult validate(ValidationRequest request) throws IOException {
        return execute(request, false);
    }

    // Keep generation on the exact contract snapshots whose fingerprints were checked in this pass.
    ValidationResult validate(ValidationRequest request, List<LocatedContract> loaded) throws IOException {
        return execute(request, false, loaded);
    }

    record LocatedContract(PromptContract contract, Path file) { }

    /**
     * Validates all inputs, then appends only previously absent versions. Any validation error prevents a write.
     * Cooperating writers must use the same coordination file; JVM-local calls are also serialized.
     * Existing entries and unchanged lock bytes are preserved.
     *
     * @param request paths and optional baseline for this pass
     * @param coordinationFile shared process-lock file in ignored build state; relative to the process working directory
     * @return aggregated findings; an invalid result leaves version history untouched
     * @throws IOException if coordination, filesystem access or atomic lock replacement fails
     */
    public ValidationResult updateLock(ValidationRequest request, Path coordinationFile) throws IOException {
        // FileChannel.lock does not wait for another lock in the same JVM, so serialize JVM-local callers too.
        synchronized (ContractEngine.class) {
            Path coordination = coordinationFile.toAbsolutePath().normalize();
            Files.createDirectories(coordination.getParent());
            try (FileChannel channel = FileChannel.open(coordination, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return execute(request, true);
            }
        }
    }

    private ValidationResult execute(ValidationRequest request, boolean update) throws IOException {
        return execute(request, update, new ArrayList<>());
    }

    private ValidationResult execute(ValidationRequest request, boolean update, List<LocatedContract> loaded) throws IOException {
        Path project = request.projectDirectory().toAbsolutePath().normalize();
        Path source = resolve(project, request.sourceDirectory());
        Path lock = resolve(project, request.lockFile());
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!source.startsWith(project) || !lock.startsWith(project) || lock.startsWith(source)) {
            diagnostics.add(new Diagnostic("contract-schema", Diagnostic.Severity.ERROR, "prompt-contracts", 1, 1, "",
                    "Source and lock must belong to the project; the lock must be outside the prompt source directory"));
            return new ValidationResult(diagnostics, 0);
        }
        if (Files.exists(source) && !source.toRealPath().startsWith(project.toRealPath())) {
            diagnostics.add(new Diagnostic("contract-schema", Diagnostic.Severity.ERROR, "prompt-contracts", 1, 1, "",
                    "Prompt source directory escapes the project"));
            return new ValidationResult(diagnostics, 0);
        }
        List<PromptContract> contracts = new ArrayList<>();
        if (Files.exists(source) && !Files.isDirectory(source)) {
            diagnostics.add(new Diagnostic("contract-schema", Diagnostic.Severity.ERROR, project.relativize(source).toString(),
                    1, 1, "", "Prompt source must be a directory"));
            return new ValidationResult(diagnostics, 0);
        }
        var ids = new HashMap<String, SourceDocument>();
        List<Path> files = List.of();
        if (Files.isDirectory(source)) try (var paths = Files.walk(source)) {
            files = paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".prompt.yaml"))
                    .sorted(java.util.Comparator.comparing(path -> source.relativize(path).toString().replace('\\', '/'))).toList();
        }
        for (Path file : files) {
            var contract = ContractReader.read(project, source, file, diagnostics, ids);
            if (contract != null) {
                contracts.add(contract);
                loaded.add(new LocatedContract(contract, file));
            }
        }
        String lockName = project.relativize(lock).toString().replace('\\', '/');
        if (request.baselineLockFile() == null) diagnostics.add(new Diagnostic("lock-baseline", Diagnostic.Severity.INFO,
                lockName, 1, 1, "", "Baseline check skipped: no baselineLockFile supplied"));
        if (files.isEmpty() && !Files.exists(lock) && request.baselineLockFile() == null) {
            diagnostics.add(new Diagnostic("contract-schema", Diagnostic.Severity.INFO, project.relativize(source).toString(),
                    1, 1, "", "No .prompt.yaml contracts found"));
            return new ValidationResult(diagnostics, 0);
        }
        LockFile current = LockFile.read(lock, lockName, "lock", update, diagnostics);
        if (current != null) current.check(contracts, update, diagnostics);
        if (request.baselineLockFile() != null) {
            Path baselinePath = resolve(project, request.baselineLockFile());
            String name = baselinePath.startsWith(project) ? project.relativize(baselinePath).toString()
                    : "baseline/" + baselinePath.getFileName();
            LockFile baseline = LockFile.read(baselinePath, name, "lock-baseline", false, diagnostics);
            if (current != null && baseline != null) current.checkBaseline(baseline, diagnostics);
        }
        ValidationResult result = new ValidationResult(diagnostics, files.size());
        if (update && result.valid() && current != null) {
            Files.createDirectories(lock.getParent());
            if (!lock.getParent().toRealPath().startsWith(project.toRealPath())) throw new IOException("Lock output escapes project");
            current.append(lock, contracts);
        }
        return result;
    }

    private static Path resolve(Path project, Path path) {
        return (path.isAbsolute() ? path : project.resolve(path)).normalize();
    }
}
