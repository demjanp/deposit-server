# deposit-server

Deposit's Stage 0 runtime foundation uses published Neo4j/APOC Core **2026.05.0**, Spatial **2025.10.0**, Temurin **21.0.12.1+1** and explicitly selected **Cypher 25**. This is the highest compatible published Neo4j/Spatial combination verified on 9 October 2026. [Selection, implementation and evidence](../deposit-hub/implementation_status.md#stage-0-implementation-and-evidence).

The development plugin `0.0.0-stage1` adds the layered core: bootstrap/login, independent Namespace sessions, history-disabled typed Class/Object/Descriptor CRUD, native streams, portable pages, revisions and receipts. [Architecture, supported scope and reuse decisions](docs/stage1-foundation.md). Historical root `src` remains inactive reference material; no compatibility layer is maintained.

Run the cumulative Stage 1 gate with `scripts/Invoke-Stage1Gate.ps1 -RunPrefix <fresh-id> -Port 17770`. Its authoritative checkpoint is `C:\data_processed\Deposit_v2\stage0\stage1-checkpoint.json`; it preserves the accepted Stage 0 checkpoint. Stage 1 supports explicit `historyMode: disabled`, all native scalar types, List and DDict. History, DGeometry and DResource remain later-stage work.

## Windows commands

Requirements: Windows PowerShell 5.1 and network access to the pinned official artifact URLs. All tools, caches, the single installation, databases, resource directories, temporary files and evidence use **`C:\data_processed\Deposit_v2\stage0`**. Provisioning does not change the machine's Java or PATH.

```powershell
.\scripts\Stage0.ps1 -Phase Provision -RunId stage0-host
.\scripts\Stage0.ps1 -Phase Build -RunId stage0-host
.\scripts\Stage0.ps1 -Phase Install -RunId stage0-host -Port 17770
.\scripts\Stage0.ps1 -Phase Start -RunId stage0-host -Port 17770
.\scripts\Stage0.ps1 -Phase Test -RunId stage0-host -Port 17770
.\scripts\Stage0.ps1 -Phase Stop -RunId stage0-host -Port 17770
.\scripts\Stage0.ps1 -Phase Package -RunId stage0-host
```

Before reusing an existing stopped installation, run `Stage0.ps1 -Phase Reset -RunId stage0-host -EvidenceGroup <fresh-evidence-id> -Port 17770`; use its recorded port consistently. Reset verifies an archive of the previous state before deleting the owned contents. Evidence remains outside the reset directory.

`Install` creates a test installation with the separate probe JAR. Use `-WithoutProbe` for a normal installation; normal packages always omit the probe and its permissions. Neo4j authentication is initialized with a unique password stored in Windows Credential Manager. No password belongs in committed configuration or evidence.

`Stop` sends a console interrupt and waits; `Kill` terminates only the recorded owned JVM. Both preserve data/evidence. `Start` validates pinned runtime/plugin files and authenticated discovery with a bounded readiness wait. Failed installations remain available for inspection; stop and archive/reset stage0-host before sequential reinstallation. `-SkipReadiness` and `-DiagnosticBuild` are explicitly diagnostic and cannot establish acceptance.

## Complete environment gate

```powershell
.\scripts\Invoke-Stage0Gate.ps1 -RunPrefix acceptance-01 -Port 17770
```

The gate covers a clean build, one authenticated installation with independent client sessions and Namespace isolation, core/Spatial fixtures, graceful restart, actual termination/recovery, isolation, startup failures, normal distribution and checkpoint recovery. It runs the environment suite once, followed by the real Codex interruption/resumption fixture and acceptance collection. Codex CLI is required for that fixture. Sequential reprovisioning reuses the same installation within the suite. No separate review or Git commit is required. To run only the environment suite:

```powershell
.\scripts\Test-Stage0Environment.ps1 -Build -RunPrefix environment-01 -Port 17770
```

All Maven output uses `C:\data_processed\Deposit_v2\stage0\build\<artifactId>\target`, including direct wrapper invocations.

The reactor modules are `plugin`, `runtime-probe` and `integration-tests`. Neo4j APIs remain provided; JTS comes from Neo4j. The published Spatial thin JAR and 26 pinned support JARs avoid the fat artifact's conflicting Neo4j client classes. Maven caches, dependency locks, source snapshots and build receipts make installations reviewable and repeatable.

## Evidence and resumption

Gate invocations write timestamped directories under `stage0\evidence\<prefix>\phases` and an atomic phase checkpoint. JUnit reports, semantic-name/UUID maps and graph/index state identify what passed. `stage0-checkpoint.json` is the authoritative overall acceptance state; [the report](../deposit-hub/implementation_status.md#stage-0-implementation-and-evidence) explains evidence locations and pending later-stage checks.

```powershell
$package = .\scripts\Export-WorkPackage.ps1 -Section Review -Name stage0-review
.\scripts\Invoke-CodexStage0.ps1 -Mode Review -Name stage0-review -WorkPackage $package
.\scripts\Invoke-CodexStage0.ps1 -Mode Work -Name repair-01 -RepairCause stable-cause-id -WorkPackage <work-package.md>
.\scripts\Invoke-CodexStage0.ps1 -Mode Resume -Name repair-01 -WorkPackage <work-package.md>
```

The runner persists exact session IDs, locks writers, rejects changed inputs/work packages and stops after three failed repairs of one stable cause across run names. Optional review is read-only and does not gate acceptance. Dependency lock regeneration is deliberate: `Resolve-SpatialDependencies.ps1`; ordinary provisioning downloads only locked URLs and verifies hashes.

This repository is part of the [Deposit Hub](https://github.com/demjanp/deposit-hub).

Only this existing Windows host and one test-owned Neo4j installation (`stage0-host`) are authorized. Evidence prefixes identify test runs, not additional installations. Normal/probe distributions and clean reruns reuse the same directory and Bolt port. Stage 11 release/recovery acceptance also uses this host, without clean-machine or cross-host validation.

[Implementation plan](../deposit-hub/implementation_plan.md) ? [Implementation status](../deposit-hub/implementation_status.md) ? [Task navigation](../deposit-hub/TODO.md)

Provisioning verifies pinned download checksums. Source identity is captured at run start and acceptance, and on runner resumption; full tool-directory and archive-content comparisons are omitted. No Git staging, commits or pushes are performed.
