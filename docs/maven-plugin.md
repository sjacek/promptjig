# Maven plugin

The Maven adapter **0.3.1** is published in Maven Central. The Gradle Portal
plugin and Central runtime libraries are separate publications, also available
as 0.3.1. Maven projects need Java 17+ and Maven 3.9+.
Maven 4 compatibility is checked against the pinned preview 4.0.0-rc-7;
this does not promise compatibility with every future Maven 4 preview.

## Configure your project

Store UTF-8 `.prompt.yaml` contracts and referenced `.st` templates in
`src/main/resources/prompts`. Add the plugin to your POM:

```xml
<plugin>
  <groupId>io.github.sjacek.promptjig</groupId>
  <artifactId>promptjig-maven-plugin</artifactId>
  <version>0.3.1</version>
  <configuration>
    <packageName>example.generated</packageName>
    <jackson>v3</jackson>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
        <goal>generate</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Omit the `generate` goal and `packageName` when you only need validation.
The default Jackson generation mode is `v2`; select `v3` for Jackson 3.
Your application supplies the matching Jackson dependency. The adapter does
not add Spring AI or a model client to your application's dependencies.

| Goal | Default phase | Effect |
| --- | --- | --- |
| `validate` | `validate` | Checks all contracts, templates, fingerprints and optional baseline. |
| `generate` | `generate-sources` | Performs the same validation, then generates Java records/enums/contract constants and registers the compile source root. |
| `lock` | None; explicit invocation only | Appends fingerprints for new versions; refuses to rewrite existing history. |

For a new contract version, explicitly run:

```shell
mvn io.github.sjacek.promptjig:promptjig-maven-plugin:0.3.1:lock
mvn verify
```

The fully qualified invocation works without changing Maven's plugin groups.
When declared in the POM, the short prefix is `mvn promptjig:lock`.
Review and commit `prompt-contracts.lock.json` with the contracts. Changing an
already locked version fails both `validate` and `lock`; increment the version
and preserve historical fingerprints. Ordinary `verify` never creates a lock.
Validation and generation make no model calls.

## Paths and CI baseline

| XML parameter | Command-line property | Default |
| --- | --- | --- |
| `source` | `promptjig.source` | `${project.basedir}/src/main/resources/prompts` |
| `lockFile` | `promptjig.lockFile` | `${project.basedir}/prompt-contracts.lock.json` |
| `baselineLockFile` | `promptjig.baselineLockFile` | Unset |
| `reportDirectory` | `promptjig.reportDirectory` | `${project.build.directory}/reports/prompt-contracts` |
| `packageName` | `promptjig.packageName` | Required for generation |
| `jackson` | `promptjig.jackson` | `v2` |
| `outputDirectory` | `promptjig.outputDirectory` | `${project.build.directory}/generated-sources/prompt-contracts` |
| `resourcePrefix` | `promptjig.resourcePrefix` | `prompts` |
| `coordinationFile` | `promptjig.coordinationFile` | `${project.build.directory}/promptjig/lock-write.lck` (lock goal only) |

Source/template paths, containment, enum/date handling and generated output use
the [same core rules as Gradle](code-generation.md). Changing a source path does
not change Maven resource packaging: configure Maven resources separately if
the source lives outside `src/main/resources`. `resourcePrefix` describes the
contract's classpath location, so it must agree with the packaged resource path.
Generated Java sources belong under the build output, separate from authored
resources; the core rejects overlapping inputs and outputs.

CI can protect already merged history with a trusted lock extracted from its
target branch, then pass `-Dpromptjig.baselineLockFile=/path/to/baseline.json`.
The owning repository chooses the baseline; the adapter imposes no branch name.
An explicitly configured missing or malformed baseline fails the build.
In a reactor, configure a baseline for each module's own lock rather than
passing one unrelated lock to all modules. All goals are thread safe;
each module should own its lock and output directories.

Each goal writes `validation.json` and `validation.txt` under its own
`validate`, `generate` or `lock` report subdirectory, including on rejected
contracts. Read the individual rule IDs in these reports; the adapter reports
all findings from the core pass before failing Maven. Filesystem/report-writing
errors are execution errors rather than contract validation failures.

## Build and test the unpublished adapter

The producer is Gradle: it generates the Maven descriptor and stages the
adapter with its current core version. No second Maven producer build or
manually maintained descriptor is needed.

```powershell
.\gradlew.bat :maven-plugin:check :core:publishAllPublicationsToStagingRepository :maven-plugin:publishAllPublicationsToStagingRepository --dependency-verification=strict --configuration-cache --configuration-cache-problems=fail
python scripts/maven_integration.py warm
python scripts/maven_integration.py verify --maven-version 3.9.16 --java-home $env:JAVA_HOME
```

Use an authoring JDK 25 for Gradle; `verify --java-home` selects the native Maven
consumer JDK 17, 21 or 25. The warm command requires the generated core POM
from the Gradle step. Full CI preparation is described in
[dependency snapshots](ci-dependency-cache.md). Maven acceptance runs offline,
including the standalone example and Invoker post-build assertions; dependency
preparation never executes these hooks or tests.

See the [standalone Maven example](../examples/maven/README.md). Source builds
use private, temporary Maven repositories with only the exact current staged
core/adapter candidates added. They neither install into your normal `~/.m2`
nor put locally built artifacts into a shared dependency cache.
