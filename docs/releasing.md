# Release procedure

Candidate: **0.1.0**. Verification and local staging do not upload artifacts.
The repository must have a reviewed commit and release tag before publication.
Commits, tags and pushes are performed by the owner; Codex does not commit or push.

Latest local results and remaining release prerequisites:
[publication readiness](publication-readiness.md).

## Verify the candidate

Use JDK 21+ for authoring (JDK 25 is used by CI):

```powershell
.\gradlew.bat clean check assemble :core:spike
```

This verifies the build without publishing credentials. Tests cover an installed
plugin marker from a local Maven repository, classloader isolation, explicit
lock writes, baseline protection, configuration cache and relocatable build
cache reports. Consumer matrix results are recorded in the stage-1 report;
the eight-cell workflow must pass on the reviewed revision.

Validate the Plugin Portal publication separately, with Portal credentials
configured in Gradle user properties or the environment:

```powershell
.\gradlew.bat :gradle-plugin:publishPlugins --validate-only --console=plain
```

This contacts the Portal without uploading artifacts. It still requires
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
.\gradlew.bat :core:publishAllPublicationsToStagingRepository :spring-ai:publishAllPublicationsToStagingRepository
```

This stages `promptjig-core` and `promptjig-spring-ai` under their respective
`build/staging-repository/` directories. It does not upload to Maven Central.
Remote Central credentials/signing are not configured. Plugin Portal publishes
the build plugin separately; it does not make the runtime coordinates available.

## Publish the reviewed tag

The owner creates and pushes an immutable `v0.1.0` tag for the reviewed commit.
Configure the GitHub environment `plugin-portal` with protected deployment
approval as appropriate and secrets `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.
Never pass their values in command-line arguments, logs or repository files.

Run the manual **Publish plugin** workflow with that tag. It checks that the tag
matches the build's version and HEAD, reruns acceptance/metadata validation, and
then invokes `:gradle-plugin:publishPlugins`. Plugin Portal approval may still
be required for the first publication. Verify actual availability before
removing the pending-publication notes or enabling default service consumption.
The owner must select a commit whose **Compatibility** workflow is green. The
release workflow does not query the outcome of an earlier Compatibility run;
its own build rerun does not replace that eight-cell acceptance requirement.

## Service adoption

The `tichodroma-svc` pilot remains an explicit `promptjigBuildDir` experiment,
outside default build/CI and production extraction. Runtime integration is paused
at the user's request while service compilation is repaired. A separate worktree
has been prepared; audit `VERSION_REF`, response-schema capture and validation
evidence have not been integrated or accepted.

After independent pilot acceptance and verified artifact availability, a separate
reviewed change can replace preview wiring with catalog-backed published
dependencies/plugin, enable a target-branch lock baseline in service CI and
activate the integration. Publication alone must not activate service consumption.
