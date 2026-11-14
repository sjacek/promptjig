---
title: Maven plugin
permalink: /maven/
---

The Maven plugin **{{ site.releases.maven_plugin }}** is available in Maven
Central. Add it to your application without building Promptjig from source.

Maven projects need Java 17+ and Maven 3.9+. Maven 4 compatibility is checked
against 4.0.0-rc-7, not every future preview. The Gradle plugin and runtime
libraries are separate publications; choose the components your application needs.

## Configure the adapter

Add this plugin inside your application's `build/plugins`:

```xml
<plugin>
  <groupId>io.github.sjacek.promptjig</groupId>
  <artifactId>promptjig-maven-plugin</artifactId>
  <version>{{ site.releases.maven_plugin }}</version>
  <configuration>
    <packageName>example.announcement.generated</packageName>
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

Omit `generate` and `packageName` for validation only. Declare the matching
Jackson dependencies in the application when compiling generated models.
The adapter adds neither Spring AI nor a model client to application dependencies.

| Goal | Default phase | Behavior |
| --- | --- | --- |
| `validate` | `validate` | Checks all contracts, templates, fingerprints and optional baseline |
| `generate` | `generate-sources` | Validates, generates Java models and registers the compile source root |
| `lock` | None | Explicitly appends new versions; refuses existing fingerprint changes |

With the plugin declared, explicitly record a new version using
`mvn promptjig:lock`, then run `mvn verify`. Without a declaration, use its full
coordinates:

```shell
mvn io.github.sjacek.promptjig:promptjig-maven-plugin:{{ site.releases.maven_plugin }}:lock
```

Do not bind `lock` into the normal lifecycle. Ordinary verification should check
reviewed history, not author it. Validation and generation make no model calls.

## Configuration reference

Each option can be set as an XML element under `configuration`, or using
`-Dpromptjig.<name>=<value>`.

| Name | Default |
| --- | --- |
| `source` | `${project.basedir}/src/main/resources/prompts` |
| `lockFile` | `${project.basedir}/prompt-contracts.lock.json` |
| `baselineLockFile` | Unset; explicitly supply a trusted baseline |
| `reportDirectory` | `${project.build.directory}/reports/prompt-contracts` |
| `packageName` | Required for generation |
| `jackson` | `v2`; choose `v2` or `v3` |
| `resourcePrefix` | `prompts` |
| `outputDirectory` | `${project.build.directory}/generated-sources/prompt-contracts` |
| `coordinationFile` | `${project.build.directory}/promptjig/lock-write.lck`; lock goal only |

Changing `source` does not change Maven resource packaging. Configure resources
separately for a source outside `src/main/resources`. `resourcePrefix` must agree
with the classpath location in the built JAR. Generated sources must remain inside
the project and separate from authored resources, lock and baseline.

Every goal accepts the optional baseline. For a reactor, give each module its
own lock, baseline and output directory. Goals are thread safe; cooperating writers
to a shared lock must also use the same coordination file.

## Read failures

Each goal writes `validation.json` and `validation.txt` in a separate `validate`,
`generate` or `lock` report subdirectory, including on validation rejection.
Read the rule IDs before changing inputs. Filesystem/report errors are execution
errors, separate from contract validation failures. Generated source registration
happens only after validation, generation and report writing succeed.

The [standalone example](https://github.com/sjacek/promptjig/tree/main/examples/maven)
uses published artifacts and runs with `mvn verify`; its reviewed POM remains
pinned to 0.3.0. Follow [the example instructions](examples.md#maven-example).
For source development, the repository's
[Maven development guide](https://github.com/sjacek/promptjig/blob/main/docs/maven-plugin.md)
describes acceptance with privately staged current build artifacts.
