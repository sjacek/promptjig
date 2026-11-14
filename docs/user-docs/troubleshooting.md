---
title: Troubleshooting
permalink: /troubleshooting/
---

Begin with the goal's `validation.txt` or `validation.json`, not only the final
build failure. Gradle reports live under `build/reports/prompt-contracts/`;
Maven reports under `target/reports/prompt-contracts/`. Each operation has its
own `validate`, `generate` or `lock` subdirectory.

## Build diagnostics

| Rule | Check |
| --- | --- |
| `contract-schema` | Required YAML fields, types, duplicate keys and unknown fields |
| `template-exists` | Exact template basename/case, `.st` extension and valid UTF-8 |
| `template-parameters` | Input names used across all messages, unknown names and ST4 syntax |
| `schema-subset` | Supported schema vocabulary and local-reference rules |
| `unique-id` | Duplicate IDs inside the scanned source set |
| `lock` | Missing/malformed lock or changed content at an existing version |
| `lock-baseline` | Missing/malformed baseline or deleted/rewritten historical entries |

Increment the contract version for a deliberate schema or instruction change,
then run the explicit lock writer. Do not replace a protected historical hash.
If generation fails, existing output can remain on disk; fix the failure rather
than compiling stale files manually.

## Generated classes cannot be found

With Gradle, apply Java and enable the `generate {}` block with a valid package.
With Maven, bind the plugin's `generate` goal. Run the normal build
instead of only opening generated Java files. Supply matching Jackson dependencies.
Check for colliding final ID segments that would generate the same class name.

Keep generated output separate from authored resources and source code. If
templates are packaged under a nondefault path, update `resourcePrefix` and
resource packaging together.

## Runtime resources are missing

Inspect the built JAR for the `.prompt.yaml` and both `.st` resources. Check exact
case and the generated `RESOURCE` constant. Loading an empty resource pattern
produces an empty registry; explicitly look up expected IDs during startup.
If you select a custom registry pattern, ensure the selected contracts have unique IDs.

## Response is rejected

Check the safe findings and whether `output.strict` was omitted. Since runtime
{{ site.releases.runtime }}, omission means strict rejection. Enable repairs only
through explicit `false` and review their findings. Invalid JSON, trailing prose
and duplicate keys fail even in repairing mode. Choose the correct Jackson family
when a schema-valid response cannot bind.

Schema acceptance does not establish truth. Compare the extracted fields with
the fictional source or the application's review rules before use.

## Empty or truncated model output

Inspect finish reason, token usage, response presence and request limits before
calling `read`. Check provider-specific reasoning/output token behavior. Adjust
limits in application configuration and retry deliberately; Promptjig does not
change tokens, model selection, deadlines or fallback automatically.

## An artifact does not resolve

The published versions are Portal plugin **{{ site.releases.gradle_plugin }}**
and core/Spring AI libraries **{{ site.releases.runtime }}** from Maven Central.
The Maven plugin **{{ site.releases.maven_plugin }}** is also available in Central.
All current components are 0.3.1, but are published separately; check the exact
group, artifact, version and repository when resolving a dependency. The bundled
examples deliberately keep the older published pins listed in [examples](examples.md).

When reporting an issue, include software versions, task/goal, rule IDs, relative
resource paths and JSON Pointers. Share a fictional minimal reproduction rather
than credentials, source documents or raw model responses.
