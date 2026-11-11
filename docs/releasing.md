# Release procedure

Candidate: **0.1.0**. Nothing has been uploaded by the stage-1 implementation.
The repository must have a reviewed commit and release tag before publication.
Commits, tags and pushes are performed by the owner; Codex does not commit or push.

## Verify the candidate

Use JDK 21+ for authoring (JDK 25 is used by CI):

```powershell
.\gradlew.bat check assemble :core:spike :gradle-plugin:publishPlugins --validate-only
```

This validates Plugin Portal metadata without upload. Tests cover an installed
plugin marker from a local Maven repository, classloader isolation, explicit
lock writes, baseline protection, configuration cache and relocatable build
cache reports. Consumer matrix results are recorded in the stage-1 report;
the eight-cell workflow must pass on the reviewed revision.

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

The core also has a Maven publication with sources, Javadocs and MIT metadata:

```powershell
.\gradlew.bat :core:publishAllPublicationsToStagingRepository
```

That command is local staging, not Maven Central deployment. Remote Central
credentials/signing are not configured by this stage.

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

## Activate the service after publication

The `tichodroma-svc` pilot currently opts in through the explicit
`promptjigBuildDir` included build, after the merged E1 work. It adds a contract
and lock to the existing resource without changing extraction/audit behavior.

After Plugin Portal availability is verified, replace that preview wiring with
a catalog-backed `plugins { alias(libs.plugins.promptjig) }` declaration at
version 0.1.0, remove the preview-only buildscript dependency/substitution, and
enable a target-branch baseline in the service CI. Until then default service
builds do not require an unpublished plugin. Generated models and runtime
integration are later stages.
