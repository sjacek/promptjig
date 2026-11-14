# Release procedure

Published Gradle plugin: **0.3.1** in the
[Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.promptjig/0.3.1),
availability confirmed on 2026-11-14. That version is immutable; further releases
need a new build version, reviewed commit and release tag.
Verification and local staging do not upload artifacts.
The core and Spring AI runtime libraries and Maven adapter **0.3.1** were published
to Maven Central on 2026-11-14. Strict response reading has been the default since
0.2.0. Set the shared
publication/runtime version in `gradle/libs.versions.toml` (`versions.promptjig`);
the root build reads it for
all project publications. Gradle Plugin Portal publication remains independent.
Both [Central](https://github.com/sjacek/promptjig/actions/runs/43587972410)
and [Portal](https://github.com/sjacek/promptjig/actions/runs/43587989664) release
workflows succeeded for `v0.3.1` at `605c8b26e2ca0978bce213802aaada03deef7b31`.
Public JARs, Central signatures and both registries' SBOMs were checked against
the accepted CI; [release evidence](evidence/publication-0.3.1.json) records the result.
Commits, tags and pushes are performed by the owner; Codex does not commit or push.

Historical plugin-release checks: [publication readiness](publication-readiness.md).
Current local checks of the public libraries:
[published runtime consumer](evidence/published-runtime-consumer-2026-11-13.json).

## Verify the candidate

Use JDK 21+ for authoring (JDK 25 is used by CI):

```powershell
.\gradlew.bat clean check assemble :core:spike
```

This verifies the build without publishing credentials. Tests cover an installed
plugin marker from a local Maven repository, classloader isolation, explicit
lock writes, baseline protection, configuration cache and relocatable build
cache reports. Consumer matrix results are recorded in the stage-1 report;
all 20 Compatibility jobs must pass on the reviewed revision.

Validate the Plugin Portal publication separately, with Portal credentials
configured in Gradle user properties or the environment:

```powershell
.\gradlew.bat :gradle-plugin:publishPlugins --validate-only -PpublicationSboms=true --console=plain
```

Prepare the current [audited SBOM set](sbom-publication.md) first. This command
builds its classifier JAR and contacts the Portal without uploading artifacts. It still requires
`GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` (or `gradle.publish.key` /
`gradle.publish.secret` user properties), so ordinary CI and pull requests must
not depend on it. Both the validation and upload steps of the manual release
workflow receive the secrets from the protected `plugin-portal` environment
or repository configuration. Never put their values into repository files.

## Test the CI and release boundaries offline

```powershell
.\gradlew.bat :gradle-plugin:workflowTest --console=plain
```

These tests parse copies of the real workflow files, tracked by
`processWorkflowTestResources` so a workflow edit invalidates cached test
results. They also execute the actual Bash tag guard using controlled Git/Gradle command
doubles. No Portal request, publishing key, real repository mutation or upload
is involved. The dedicated source set keeps the YAML parser out of the TestKit
JVM, preserving the plugin's Jackson isolation checks. It is included in `check`
and has no Maven staging dependency.

Bash is required: Linux/macOS use `bash` on PATH; Windows first checks Bash
under the standard Git for Windows installation. WSL is not required.

Coverage includes secret-free ordinary CI, all eight consumer combinations,
manual-only publishing, secret scope and fail-closed ordering. The guard tests
reject empty/failed version output, wrong tags/branches, shell-looking input,
failed tag/HEAD resolution, mismatched commits and tracked/index changes.
Command failures are checked before comparing commit IDs, so two failed
resolutions cannot be mistaken for matching empty strings.

## Generation and Maven artifacts

Stage-2 tests additionally compile generated Java 17 records and annotation
constants, exercise Jackson 2/3 round trips and restore generated sources from
the build cache after relocation. Run the generation consumer as well:

```powershell
.\gradlew.bat -p examples/generation check '-PpromptjigPluginBuild=../..' --configuration-cache
```

The current generation acceptance is recorded in [the stage-2 report](stage2-report.md).

The Gradle plugin JAR contains its own nested core/runtime bundle and
`META-INF/LICENSE`. It does not need a separate public core artifact before it
can be installed. The local publication repository is
`gradle-plugin/build/staging-repository/` and contains the plugin marker,
implementation JAR, POM and Gradle module metadata.

The core and Spring AI runtime have Maven publications with sources, Javadocs
and MIT metadata:

```powershell
.\gradlew.bat :core:publishAllPublicationsToStagingRepository :spring-ai:publishAllPublicationsToStagingRepository :maven-plugin:publishAllPublicationsToStagingRepository
```

The current build stages `promptjig-core`, `promptjig-spring-ai`
and `promptjig-maven-plugin` under their respective
`build/staging-repository/` directories. It does not upload to Maven Central.
The separate manual **Publish Maven Central artifacts** workflow prepares signed
packages and uploads them through the Central Publisher API. It is described in
[Maven Central releases](maven-central.md), including required environment secrets,
local verification, release guards and status-only recovery. Both libraries are
publicly available as 0.3.1, alongside the Maven adapter. Plugin Portal publishes the build plugin separately;
it does not make the runtime coordinates available.

The 0.3.1 release paths require [audited SBOM attachments](sbom-publication.md)
and a clean Portal publication regression before staging. The published 0.3.0
Portal plugin omitted its SBOM JAR; the public 0.3.1 classifier is present and
contains the four exact audited documents. Central 0.3.0 attachments remain available.
Central signs four classifier JSONs per module; Portal receives a separate
implementation `-sbom.jar`, keeping the POM-only marker unchanged. Release guards
select the exact successful Compatibility run whose SBOM artifact is downloaded
and checked against the rebuilt bytes. Bootstrap local staging above is useful
before inventories exist, but does not authorize a release without SBOMs.

## Publish the reviewed tag

For a new release, update the build version and have the owner create and push
an immutable `v<version>` tag for the reviewed commit. Do not republish `0.1.0`.
Configure the GitHub environment `plugin-portal` with protected deployment
approval as appropriate and secrets `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.
Never pass their values in command-line arguments, logs or repository files.

Run the manual **Publish plugin** workflow with that tag. It checks that the tag
matches the build's version and HEAD, reruns acceptance/metadata validation, and
then invokes `:gradle-plugin:publishPlugins`. The first release's Portal approval
is complete. Verify actual availability of every new version after publishing;
a successful upload alone does not establish public availability.
The owner must select a commit whose latest **Compatibility** workflow is green.
Both release workflows query that exact commit and require all 20 jobs to have
executed successfully. They restore the accepted run's audited SBOM artifact;
their own build rerun does not replace the full acceptance requirement.

## Service adoption

The `tichodroma-svc` runtime pilot uses the explicit `-PpromptjigPilot=true`
opt-in and its mandatory `test:promptjig-pilot` CI job. Both that pilot and the
[standalone public-announcement example](../examples/runtime/README.md) use public
releases independently. The service pilot retains core/runtime 0.2.0 and Portal
plugin 0.1.0; the small example uses core/runtime and Portal plugin **0.3.1**.

No source checkout is required in either normal pilot or pilot CI.
`promptjigBuildDir` remains an explicit development override, substituting plugin,
core and runtime together. Library publication does not activate production
runtime in the service. Build-time validation/generation and runtime adoption
remain separate decisions.
