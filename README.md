# Promptjig

Contract-first validation for Spring AI prompts. Detect contract/template
mismatches during `check`, keep version fingerprints in an append-only lock,
protect merged history with a CI baseline, and generate typed Java records
from the contract schemas. [MIT licensed](LICENSE).
The Spring AI runtime renders these contracts and reads model responses into
generated types with safe validation findings.

Candidate **0.1.0** is implemented locally; Plugin Portal publication is pending.
The core targets Java 17; the plugin supports Gradle 9.0+ on a supported JDK 17+.
Building the development probes requires JDK 21+; the wrapper uses Gradle 9.9.1.

See the [changelog](CHANGELOG.md) for user-visible changes and compatibility notes.

## Use the plugin

After publication, apply `io.github.sjacek.promptjig` version `0.1.0`.
Before publication, try the checked-in consumer with an explicit local build:

```powershell
.\gradlew.bat -p examples/gradle check '-PpromptjigPluginBuild=../..'
```

Place contracts and their `.st` resources under `src/main/resources/prompts`.
Run `promptContractsLock` explicitly for a new version, then `check`. A changed
existing version fails both tasks; neither task rewrites historical fingerprints.

See the [Gradle guide](docs/gradle-plugin.md), [contract format](docs/contract-format.md)
and [release procedure](docs/releasing.md). Enable `generate {}` to compile
[generated Java inputs, outputs and contract constants](docs/code-generation.md).
Validation and generation make no model calls.

Use [`promptjig-spring-ai`](docs/spring-ai.md) for startup resource validation,
system/user rendering and lenient or strict response reading. It targets
Spring AI 2.0.x and Java 17+, with Jackson 2/3 bindings. Applications own model
options, sensitivity policy and durable audit before response reading.

Try the [small standalone Spring AI pilot](examples/runtime/README.md) to extract
a fictional public event announcement into generated Java types. Its default
run uses synthetic responses; an explicit task can call a pinned local model.

```powershell
.\gradlew.bat -p examples/runtime check run --configuration-cache --configuration-cache-problems=fail
```

## Development probes

Build and run the probes with JDK 21 or newer supported by Gradle (the recorded
stage-0 run used JDK 25.0.1). The core library continues to target Java 17:

```powershell
.\gradlew.bat check :core:spike --console=plain
```

The `core` module reads the bundled v1 contract schema, checks template
parameters using the ST4 AST and computes a framed SHA-256 fingerprint. It has
no Spring, Gradle or Maven dependency. Spring AI is used by the `spring-ai`
module and the development `spike` source set. All authored templates are UTF-8 classpath/file resources loaded
through Spring `Resource` and `PromptTemplate` / `SystemPromptTemplate`.

`core/src/spike/resources/prompts/e1/certificate-extraction.st` is an unchanged
snapshot of the E1 working-tree resource. The separate contract fixture covers
all 15 extraction fields, including closed symbol and indication enums. It does
not change the application's prompt or audit behavior. All OCR and model data
used by the experiment are synthetic.

Run the **explicit** local Docker Model Runner experiment after installing the
model and reading its digest with `docker model inspect`:

```powershell
docker model pull ai/qwen3.5:9b-q4_K_XL
docker model inspect ai/qwen3.5:9b-q4_K_XL
.\gradlew.bat :core:modelSpike -PspikeModel=sha256:f799fb1ad223f00fc55cf4d6c12de7dc9b71d10ca427bb0ff7b644f49d9a7960 --console=plain
```

The probe uses `http://localhost:12434/engines/v1/chat/completions`, does not
follow redirects and requires a full digest. It compares `json_object` and the
OpenAI `json_schema.schema` wrapper on both the certificate and an adversarial
enum case. HTTP 200 alone does not count as schema enforcement. There are no
model calls in `check`. The default per-request timeout is 600 seconds; override
with `-PspikeTimeoutSeconds=...` (1–1800). On a cold CPU model, preload using
`docker model run --detach ai/qwen3.5:9b-q4_K_XL` so an HTTP timeout does not
interrupt loading.

Evidence is written under `core/build/reports/stage0/`: `offline.json`,
`offline.txt`, `model-runner.json` and synthetic requests/responses. Test results
are under `core/build/test-results/test/`. See [the stage-0 report](docs/stage0-report.md)
for findings and limitations.

The Gradle validator, located diagnostics and immutable lock/baseline are part
of stage 1; code generation is implemented in stage 2. The Spring AI runtime and
response repair implement stage 3. The Maven adapter remains later work. The
stage-0 report records the original experiment; current behavior is documented
in the guides above.
