---
title: Version history and CI
permalink: /versioning/
---

`prompt-contracts.lock.json` maps prompt IDs and version numbers to SHA-256
fingerprints. Keep it source-controlled in the module root, outside packaged
application resources. Review it with the contract and templates.

## Change a prompt deliberately

1. Edit the contract or referenced `.st` files.
2. Increment the contract's `version`.
3. Explicitly run `./gradlew promptContractsLock` (or the upcoming Maven goal
   `mvn promptjig:lock`).
4. Review the appended entry and run normal verification.

Both lock writers refuse to change an existing version's fingerprint. They retain
historical entries even when a prompt is removed from the current source set.
There is no force option. A malformed lock is not replaced. Do not edit hashes
manually to make a changed instruction pass validation.

Normal `check`, `verify` and generation read the lock without updating it.
Coordinate writers to one shared lock through the same coordination file.

## Protect the history already merged

The current lock alone cannot prove that someone has not manually rewritten its
historical hashes. CI should extract a trusted baseline from the target branch
or previous protected revision and pass it to validation.

Every prompt/version in that baseline must remain in the current lock with the
same fingerprint. An explicit missing or malformed baseline fails. Without a
baseline the report records that comparison was skipped.

Gradle:

```shell
./gradlew check -Ppromptjig.baselineLockFile=baseline-lock.json
```

The upcoming Maven adapter accepts:

```shell
mvn verify -Dpromptjig.baselineLockFile=baseline-lock.json
```

## GitHub pull-request example

The following assumes a root-module lock already exists on the target branch
and that your repository has a working Gradle wrapper. It intentionally fails if
baseline extraction fails.

{% raw %}
```yaml
name: Check prompt history
on: pull_request
permissions:
  contents: read
jobs:
  contracts:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          fetch-depth: 0
          persist-credentials: false
      - uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6
        with:
          distribution: temurin
          java-version: '21'
      - name: Extract trusted target-branch lock
        env:
          BASELINE_REVISION: ${{ github.event.pull_request.base.sha }}
        run: git show "${BASELINE_REVISION}:prompt-contracts.lock.json" > baseline-lock.json
      - run: ./gradlew check -Ppromptjig.baselineLockFile=baseline-lock.json
```
{% endraw %}

For a genuinely new module with no previous history, explicitly approve an empty
`lockVersion: 1` baseline according to your repository's policy. Do not turn any
extraction error into an empty baseline. In a multi-module build, extract the
correct module-relative lock for each module. On protected-branch pushes, compare
against the previous protected revision rather than the newly pushed lock itself.

## Two separate version numbers

The contract's integer version identifies instructions and schema. The Promptjig
library/plugin version identifies software behavior. Record both when comparing
model runs. For example, runtime {{ site.releases.runtime }} changed the default
read policy without inserting `strict` into unchanged authored contracts.
