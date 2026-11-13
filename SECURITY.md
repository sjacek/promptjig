# Security policy

## Private reporting

Use [GitHub Private Vulnerability Reporting](https://github.com/sjacek/promptjig/security/advisories/new)
for suspected vulnerabilities. Do not put exploit details, credentials, private
contracts or model responses in public issues, pull requests or CI artifacts.
If the private form is unavailable, ask publicly only for a private contact or
for reporting to be enabled, without disclosing the vulnerability itself.

Adding this file does not enable GitHub's reporting setting. Its observed state
and the proposed repository controls are recorded in the
[review](docs/security-review-2026-11-13.md).

Include the affected version/commit and distribution, JDK/build-tool versions,
a minimal fictional reproduction, expected/actual behavior and potential impact.
Share sensitive details only through an agreed private channel. Investigation
and fixes are best effort; there is no guaranteed response deadline or backport.
Coordinate disclosure timing privately before an advisory is published.

## Supported versions and scope

Security fixes target the latest appropriate release of each independently
versioned distribution. Gradle Portal 0.1.0 and core/Spring AI 0.2.0 are the
published baselines for this review. Maven adapter 0.3.0 is an unpublished
candidate. Check actual registry availability and the changelog before upgrading.

This policy covers core, the Gradle/Maven adapters, Spring AI runtime, parsers,
generated outputs, dependency processing and release workflows. Build plugins
execute with the build process's permissions; Promptjig is not a sandbox for
untrusted repository code. Runtime schema acceptance does not establish factual
correctness, authorize data disclosure or approve a business operation.

See [trust boundaries](docs/security.md), the
[dependency policy](docs/dependency-security.md) and the
[review evidence](docs/security-review-2026-11-13.md). Passing tests, signatures,
scanner results and bounded network observations are different types of
evidence; none is a comprehensive or independent security audit.
