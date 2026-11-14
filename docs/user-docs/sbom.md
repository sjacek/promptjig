---
title: Software bill of materials
permalink: /sbom/
---

# Software bill of materials

**Maven Central 0.3.0 includes SBOM attachments.** The Gradle plugin 0.3.0 is
available in Plugin Portal, but its separate SBOM JAR was omitted during
publication. The fix is prepared for **0.3.1, which is not published yet**.
Older releases keep their original files and do not gain attachments retroactively.

Promptjig publishes CycloneDX 1.6 JSON inventories for each distribution,
separately for these scopes:

| Scope | What it describes |
| --- | --- |
| runtime | Dependencies selected for the library/plugin, including the Gradle plugin's embedded runtime |
| build | Build and annotation-processing tools |
| test | Test libraries and the exercised consumer tooling |
| provided | APIs/annotations expected from the host rather than bundled at runtime |

Build, test and provided entries are useful for audit. They are not dependencies
to add to your application. The inventory does not cover every component of the
host Maven/Gradle installation, JDK or operating system.

## Maven Central

For `promptjig-core`, `promptjig-spring-ai` and `promptjig-maven-plugin`, download
the JSON with the same artifact version as your JAR:

| Scope | Classifier / extension |
| --- | --- |
| runtime | `cyclonedx` / `json` |
| build | `cyclonedx-build` / `json` |
| test | `cyclonedx-test` / `json` |
| provided | `cyclonedx-provided` / `json` |

For example, the published 0.3.0 core runtime inventory is:

```text
https://repo.maven.apache.org/maven2/io/github/sjacek/promptjig/promptjig-core/0.3.0/promptjig-core-0.3.0-cyclonedx.json
```

The detached PGP signature uses the same URL with `.asc`; SHA-256 and SHA-512
sidecars use `.sha256` and `.sha512`. Check the publisher's signing identity as
well as signature validity. The main JAR's SHA-256 appears in the SBOM's
`metadata.component.hashes`.

## Gradle Plugin Portal

The corrected Portal distribution carries the four documents in a separate
classifier JAR. **After 0.3.1 is published**, its URL will be:

```text
https://plugins.gradle.org/m2/io/github/sjacek/promptjig/promptjig-gradle-plugin/0.3.1/promptjig-gradle-plugin-0.3.1-sbom.jar
```

Open it as a ZIP and read:

```text
META-INF/sbom/runtime.cdx.json
META-INF/sbom/build.cdx.json
META-INF/sbom/test.cdx.json
META-INF/sbom/provided.cdx.json
META-INF/sbom/manifest.json
```

It contains metadata only; do not add it to your application's classpath.
Normal plugin installation still uses the same marker and implementation JAR.
Compare the main implementation JAR's hash with the SBOM/manifest, not with the
SBOM wrapper JAR's hash. Portal PGP signatures are not promised by this setup.

## Interpreting the documents

Each SBOM records the software version, source commit, scope, selected dependency
graph and artifact hashes. CI scans these exact documents and release checks
reject attachments that no longer describe the rebuilt JAR or reviewed sources.
Publishing an SBOM does not certify an absence of vulnerabilities; scanners and
advisory databases change over time. It also does not create an SBOM attestation.

For release preparation, diagnostics and verification commands, see the
[maintainer guide](https://github.com/sjacek/promptjig/blob/main/docs/sbom-publication.md).
