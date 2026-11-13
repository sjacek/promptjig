# Trust boundaries

Read [SECURITY.md](../SECURITY.md) before reporting a vulnerability. Use fictional
inputs and a private channel for exploit details. Repository reporting settings
are separate from files in this repository; their observed state and proposed
changes are in the [review](security-review-2026-11-13.md).

## What executes with your permissions

The Gradle and Maven plugins are build code. They run with the build process's
filesystem, environment and network permissions. Applying a plugin, evaluating
build scripts or executing fixture hooks is not a sandbox for an untrusted
project. Review third-party build code before supplying credentials or mounting
private directories. The descriptor generator is a Gradle build dependency;
Maven is an actual consumer/Invoker target, not a second producer build.

The core library reads contracts and templates, validates them and writes reports,
locks and generated Java when the caller requests those operations. Validation
does not approve a business action or establish that a model's answer is true.
Generated Java is source code that the consumer compiles. Keep authored contracts,
their version history and generator configuration under review.

| Input | Checks and behavior | Remaining responsibility |
| --- | --- | --- |
| UTF-8 YAML contracts | Duplicate keys/aliases, malformed input, supported schema subset and template references are checked | Review contract meaning and limit untrusted input size at your application boundary |
| `.st` templates | Named resources, supported template syntax and schema parameters; template text participates in the fingerprint | Treat templates and classpath dependencies as reviewed application inputs |
| Resource paths | Filesystem contract loading rejects traversal/escaping paths; Spring registry rejects remote URLs before opening them and checks local template siblings with real paths | Custom Spring `Resource`/resolver implementations are application code; a custom implementation can itself perform I/O |
| JSON Schema references | Supported local references only; validator remote fetching is disabled | Adding a new schema loader/reference feature requires a new review |
| Model JSON | Strict validation by default; `output.strict: false` explicitly enables the documented repair behavior and findings | Choose the model, transport, time/size limits, disclosure rules and any business approval |
| Version locks/baselines | Validation never writes history; explicit lock updates append versions and baseline checks reject rewrites | Supply a trusted CI baseline and review new versions; do not regenerate history to silence a failure |
| Diagnostic text | Control characters, Unicode line separators and bidi/format controls cannot forge terminal lines or reverse a rule/location | JSON reports are structured data, not HTML; escape them appropriately if rendering in another UI |

## Spring AI resource loading

Use the default `classpath*:prompts/**/*.prompt.yaml` scan, an explicit local
subset or local JAR resources. Standard HTTP/HTTPS resources, including remote
JAR URLs and remote `file://host`/UNC locations, are rejected before contract contents are read. Local file templates
must remain beside their contract after resolving symlinks. In-memory resources
without a URL remain supported. Local Spring Boot `jar:nested:` URL forms remain
accepted, following the [Boot loader's documented local archive format](https://docs.spring.io/spring-boot/specification/executable-jar/jarfile-class.html).
The URL-form regression uses a synthetic Resource, not a full Boot application.
The registry snapshots validated text so later
file changes cannot silently change a loaded fingerprint.

`PromptRuntime.prompt`, `read` and `responseFormat` render/validate data and do
not call a model. An application using `ChatClient` or an example's explicit live
mode performs its own network call. Supplying sensitive material to that call
is an application decision, not permission granted by a valid contract. The
standalone public-announcement demo and security scenarios use synthetic data;
they require no model download, API key or paid service.

## Evidence and its limits

The [dependency guide](dependency-security.md) explains strict checksums, native
SBOMs, the scanner/database policy and release gates. Checksums and signatures
establish byte identity/integrity under the stated trust sources; they do not
prove that code is benign. A signature verified with a GitHub account's public
key does not independently certify the publisher's real-world identity.

The isolated Linux test uses a non-root UID, read-only root, no capabilities,
`no-new-privileges`, a network-none namespace and only explicit source/dependency/
scratch/evidence mounts. It receives no developer home, publishing credentials,
Docker socket or model key. Raw `strace` records failed outbound attempts too.
A blocked connection to reserved address `198.51.100.1` is the observer's positive
control; loopback build transport is recorded but permitted. DNS attempts count
as outbound even when directed to loopback. Unknown IP trace syntax fails the
observer instead of being counted as zero traffic.

Passing these representative cases establishes observed behavior for the recorded
source inputs, image and commands. It is not a guarantee about every application,
custom resource, platform, input or future release. Docker/JDK/OS security,
exhaustive denial-of-service analysis and full performance benchmarks remain
separate work. Inspect failures and retained native reports before making claims
about a release.
