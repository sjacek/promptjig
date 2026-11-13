# Maven contract validation and code generation

This small example validates a fictional public event announcement contract,
generates Java 17 records for Jackson 3, and checks JSON date/enum binding and
resource packaging with JUnit Jupiter. It makes no model calls and contains no
real personal data. The Spring AI model experiment remains in
[`examples/runtime`](../runtime/README.md).

The POM targets the **unreleased 0.3.0 Maven adapter**. Until that version is
published, run it through the repository's native acceptance harness:

```powershell
.\gradlew.bat :core:publishAllPublicationsToStagingRepository :maven-plugin:publishAllPublicationsToStagingRepository --dependency-verification=strict
python scripts/maven_integration.py warm
python scripts/maven_integration.py verify --maven-version 3.9.16 --java-home $env:JAVA_HOME
```

After publication, the example will run independently with `mvn verify` from
this directory, without a Promptjig checkout. Its fully qualified plugin
coordinates avoid relying on user-specific Maven plugin groups. Do not change
the pinned version to a development checkout to reproduce a published result.

The authored files are under `src/main/resources/prompts/events/announcement`.
`prompt-contracts.lock.json` preserves contract history and is not packaged in
the application JAR. Generated Java goes to
`target/generated-sources/prompt-contracts`; reports go to
`target/reports/prompt-contracts/{validate,generate}`. Only an explicit
`mvn promptjig:lock` may append a new contract version.

See [Maven configuration and baseline protection](../../docs/maven-plugin.md).
