# Gradle plugin

Version 0.3.1 is available in the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.promptjig/0.3.1).
The declaration below resolves the published plugin without a Promptjig checkout.

## Setup

```groovy
plugins {
    id 'io.github.sjacek.promptjig' version '0.3.1'
}

promptContracts {
    source = layout.projectDirectory.dir('src/main/resources/prompts')
    lockFile = layout.projectDirectory.file('prompt-contracts.lock.json')
}
```

Both paths are defaults. Sources and the lock belong to the project; keep the
lock outside resources so it does not enter the application JAR. Each contract
references `.st` files in its own directory. The plugin also works without the
Java plugin and applies Gradle's base lifecycle when necessary.

Run the checked-in example against the published release:

```powershell
.\gradlew.bat -p examples/gradle check
```

For source development, add `-PpromptjigPluginBuild=../..` to substitute the local
plugin build. Standalone core/runtime Maven publication is separate from Portal
availability; see the [runtime guide](spring-ai.md).

## Validate and version changes

`check` depends on `promptContractsValidate`. Validation reads every
`**/*.prompt.yaml` under `source`, checks the supported schema subset, template
existence/UTF-8/syntax, parameters across messages, unique IDs and fingerprints.
It never renders patient data, calls a model or updates the lock.

For a new prompt or a changed prompt version:

1. Edit the contract/templates and increment `version` when content changes.
2. Run `promptContractsLock` explicitly.
3. Review and commit the contract, templates and lock together.
4. Run `check`.

`promptContractsLock` validates first, then appends only absent version entries.
An existing version with a different fingerprint fails without changing any
lock bytes. There is no force option. Historical entries remain when their
prompt is no longer in the current source set. A malformed lock is not replaced.
Writes are serialized and use atomic replacement where the filesystem supports
it. JVM/process writers targeting the same file must share the coordination file.

`promptContractsLock check` is also supported: validation runs after the explicit
write. This ordering does not make `check` invoke the writer. The writer always
runs when requested and is not cached.

Projects with no contracts and no lock can apply the plugin without creating
an empty lock. A supplied baseline is still enforced in an empty source set.

## Protect history in CI

Supply a lock from the target branch or the previous protected revision:

```powershell
.\gradlew.bat check '-Ppromptjig.baselineLockFile=baseline-lock.json'
```

The same option is available through `promptContracts.baselineLockFile` as a
`RegularFileProperty`. A baseline may be outside the project, such as a CI
temporary directory. A supplied missing/malformed baseline fails validation.
Every baseline prompt/version must remain in the current lock with exactly the
same fingerprint. This catches manually rewritten or deleted history.

CI prepares the baseline, for example with `git show <target>:<module>/prompt-contracts.lock.json`.
Do not pass the edited current lock as its own baseline. For a genuinely new
module with no prior lock, an explicit empty `lockVersion: 1` baseline is valid.
Without a baseline the report contains an INFO diagnostic stating that the
history comparison was skipped. Local unmerged entries may be recreated during
development; merged baseline entries may not.

The repository's compatibility workflow also checks its example against the
PR target/push predecessor lock. Baseline extraction failures do not silently
disable history protection.

## Reports and caching

Validation writes `validation.json` and `validation.txt` to
`build/reports/prompt-contracts/validate/`; explicit lock updates use the separate
`build/reports/prompt-contracts/lock/` directory. Text diagnostics use
`file:line:column: [rule] message` with project-relative paths and one-based
coordinates. JSON includes the rule, severity and JSON Pointer. All readable
contracts are checked before failure; structurally invalid documents do not
produce speculative secondary semantic errors.

Rules: `contract-schema`, `template-exists`, `template-parameters`,
`schema-subset`, `unique-id`, `lock`, `lock-baseline`.

`promptContractsValidate` supports the build cache, relocation and configuration
cache. Contract/template/lock/baseline contents and portable source names are
declared inputs. An extracted bundled runtime is local state. Jackson and ST4
run in a classloader-isolated Worker API action and are not exposed as plugin
dependencies to the consumer's buildscript. The plugin JAR carries its runtime,
so installation does not depend on a separate Promptjig core publication.

The core/plugin target Java 17. Consumer CI covers Gradle 9.0.0, 9.1.0 and 9.9.1
with JDK 17/21/25, excluding Gradle 9.0.0 on JDK 25. Development probes require
JDK 21+ and are never invoked by consumer `check`. Isolated Projects support is
not declared for this release.

The [generation guide](code-generation.md) describes `promptContractsGenerate`,
the opt-in `generate {}` DSL and automatic Java source wiring. For runtime
rendering and response reading, add the separate [Spring AI library](spring-ai.md).
