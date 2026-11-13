# Native Maven acceptance

`scripts/maven_integration.py` renders this driver from the Gradle version
catalog. It stages exact current core/adapter bytes in a private repository,
clones the projects below, and invokes Maven Invoker offline. Invoker runs
the `verify.groovy` assertions after each build, including expected failures.
An expected failure must contain the intended core finding; a dependency or
compiler failure cannot masquerade as a successful negative test.

Generation and example hooks read the Surefire XML and require a nonempty
test run with no failures, errors or skipped tests. Merely packaging a JAR or
seeing Maven's successful exit does not prove that JSON binding was exercised.

| Project | Required behavior |
| --- | --- |
| `validate-valid` | Native goal discovery, successful validation, no implicit generation. |
| `generation-v2`, `generation-v3` | Lifecycle generation, Java compilation, JUnit date/enum JSON round trip, packaged prompt resources. |
| `missing-lock` | Normal validation refuses to create a missing lock. |
| `changed-contract` | A changed locked version fails validation. |
| `lock-create` | The explicit prefix goal creates new history, then validation succeeds. |
| `lock-refuse-overwrite` | The explicit lock goal refuses historical rewrites. |
| `baseline-rewrite` | A supplied baseline detects replacement of a historical fingerprint. |
| `template-parameters` | Template parameter findings reach Maven reports. |
| `output-containment` | Generation cannot overwrite authored resources. |
| `without-contracts` | A reactor module without contracts needs no artificial lock. |
| `custom-paths` | Configured source, lock, output, reports and resource prefix agree with compilation/packaging. |
| `parallel-reactor` | Two modules compile generated models and run JUnit under native `-T2 verify`. |
| `standalone-example` | The independent `examples/maven` POM is cloned and tested alongside the fixtures. |

The parent references in authored fixtures target the **cloned** layout under
`target/projects`, not an independently buildable fixture reactor. The standalone
example has no parent or dependency on the producer's Gradle model.

Preparation uses only dependency-resolution goals. It never invokes Invoker,
fixture hooks, compilation or test phases. Outputs, cloned projects, local
candidate artifacts and the temporary candidate checksum policy stay outside
the shared dependency snapshot. Native Maven enforces SHA-256 for restored
external files and the exact current candidates before loading them.
