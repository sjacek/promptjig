---
title: Gradle plugin
permalink: /gradle/
---

Apply `io.github.sjacek.promptjig` version `{{ site.releases.gradle_plugin }}`
from the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.promptjig).
No local Promptjig checkout is needed.

```groovy
plugins {
    id 'io.github.sjacek.promptjig' version '{{ site.releases.gradle_plugin }}'
}

promptContracts {
    source = layout.projectDirectory.dir('src/main/resources/prompts')
    lockFile = layout.projectDirectory.file('prompt-contracts.lock.json')
}
```

Both configured paths are defaults. Keep the lock outside application resources.
The plugin applies the base lifecycle when necessary, so validation does not
require the Java plugin.

## Tasks

| Task | Behavior |
| --- | --- |
| `promptContractsValidate` | Validates contracts/templates, current fingerprints and any supplied baseline |
| `check` | Includes `promptContractsValidate`; never updates the lock |
| `promptContractsLock` | Explicitly appends absent versions after validation; refuses changed existing entries |
| `promptContractsGenerate` | Validates and generates Java sources; never updates the lock |

For a new or deliberately changed version:

```shell
./gradlew promptContractsLock check
```

The writer runs first when combined with validation or compilation. It always
runs when explicitly requested and is not cached. There is no force-overwrite
option. A module with no contracts and no lock does not acquire an empty lock.

## Opt into Java generation

```groovy
promptContracts {
    generate {
        packageName = 'example.announcement.generated'
        jackson = 'v3'
        resourcePrefix = 'prompts'
    }
}
```

The block enables automatic wiring into Java `main`; `compileJava` runs generation
first. Java and Promptjig plugins can be applied in either order. `jackson` defaults
to `v2`; `resourcePrefix` defaults to `prompts`. The prefix must match the packaged
classpath location of your resources.

Set `generate.enabled = false` to disable automatic wiring. Explicitly requesting
`promptContractsGenerate` still performs generation with the configured settings.
The default output directory is
`build/generated/sources/prompt-contracts/java/main`.

## Protect merged history

Supply a trusted lock extracted from the target branch or previous protected
revision, not the lock edited by the same change:

```shell
./gradlew check -Ppromptjig.baselineLockFile=baseline-lock.json
```

You can also set `promptContracts.baselineLockFile` in the DSL. An explicitly
supplied missing or malformed baseline fails the build. See
[version history and CI](versioning.md) for extraction details.

## Reports and caching

Reports are JSON and text under `build/reports/prompt-contracts/`, in separate
`validate`, `lock` and `generate` subdirectories. Text includes relative resource
paths and one-based line/column positions. JSON adds severity, rule and JSON Pointer.

Validation and generation support configuration cache and relocatable build cache.
Contract, template, lock and baseline contents are tracked inputs. Validation
dependencies are isolated from your application's dependency graph.

The core/plugin target Java 17. Consumer coverage includes Gradle 9.0.0, 9.1.0
and 9.9.1 with JDK 17/21/25, excluding Gradle 9.0.0 on JDK 25. Isolated Projects
support is not declared for this release.
