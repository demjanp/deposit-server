# Deposit Server

Deposit Server is the Neo4j plugin implementation for Deposit 2.0. The Deposit Hub is the authoritative home for product contracts, implementation planning and acceptance status.

## Current state

The build label is `0.0.0-dev`. The accepted source snapshots cover typed data, authorization, batch updates, resource storage, revision history, query, patterns, clone and import/export workflows, and browser sessions. Windows release and recovery behavior is complete in its approved server scope, with evidence qualifications recorded in the [implementation status](../deposit-hub/implementation_status.md#current-status). Python wrapper and GUI implementation remain pending.

## Project documentation

- [Deposit Server user guide](doc/README.md)
- [Server specification and Windows operations](../deposit-hub/deposit-server.md)
- [Readable procedure API](../deposit-hub/deposit-server-api.md)
- [Current implementation status, maintenance workflow and acceptance history](../deposit-hub/implementation_status.md)
- [Full Windows integration suite](scripts/Invoke-IntegrationSuite.ps1)
- [Selected test runner](scripts/Invoke-Tests.ps1)
- [Seed, transition and verification scenarios](scripts/Invoke-TestScenario.ps1)

The full suite runs the documented integration workflows, including seed/restart/verification transitions, browser workflows, crash recovery and Windows service/listener scenarios. The selected-test and scenario scripts remain available for focused work. Retired cumulative test gates and the legacy Codex/work-package runner have been removed. The inactive historical source formerly under the root `src` tree is preserved at [immutable commit `988e21b524befcd419dd6f1a950c1f170f44c60e`](https://github.com/demjanp/deposit-server/tree/988e21b524befcd419dd6f1a950c1f170f44c60e/src); the active Maven reactor is `plugin`, `runtime-probe` and `integration-tests`.

## Windows integration suite

Integration test sources and execution remain in this repository. The runner derives the sibling harness path as `..\test_harness`; generated tools, caches, database state, temporary files, secrets, logs and results stay there. The harness is not checked into the repository. If it is empty, setup downloads and verifies pinned dependencies, extracts the Java/Neo4j/Maven/Python/Firefox/geckodriver tools, builds and packages the server, and prepares a fresh local test runtime. Packaging also requires the adjacent `deposit-hub` checkout because setup reads `deposit-hub/deposit-server.md` to generate the release `OPERATIONS.md`.

### Requirements

- Windows x64 with Windows PowerShell 5.1, .NET Framework and an NTFS volume for the repository and sibling harness.
- Network access to the pinned Maven, Neo4j, Temurin, Python, Firefox and geckodriver artifact sources, plus Maven repositories. A corporate proxy or firewall must allow those downloads when the corresponding archives are not already in the harness cache. The pinned geckodriver archive is downloaded from Mozilla's GitHub releases and checked against the SHA-256 value in `runtime/integration-tools.json`.
- Run the full suite from an elevated PowerShell window. The suite temporarily registers a dedicated Neo4j Windows service for service lifecycle scenarios. `-SetupOnly` prepares the environment without registering or starting a service and does not require elevation.
- Firefox and geckodriver are provisioned into the harness at pinned versions and launched headlessly by the browser integration workflows; no existing browser installation is required. The test runner passes the pinned driver path to Selenium, so browser tests do not depend on Selenium Manager downloading a driver at runtime.
- Allow localhost TCP ports 17770 (Bolt) and the test-selected download listener port; the listener scenarios also exercise local TLS fixtures.

### Setup and run

From the repository root:

```powershell
.\scripts\Invoke-IntegrationSuite.ps1 -SetupOnly
```

Run the complete suite from an elevated PowerShell window:

```powershell
.\scripts\Invoke-IntegrationSuite.ps1
```

The default run prepares the harness if needed, rebuilds the test runtime from the pinned manifests, runs the workflows in order and cleans up the test-owned service in a `finally` block. During the suite the service is set to manual start with automatic recovery disabled, then removed before the runner reports success. The runner starts a separate watchdog before any service can be registered. If the runner process exits unexpectedly, the watchdog uses the protected ownership journal to stop the exact test runtime and remove the exact test service. During service registration, Neo4j briefly creates the service before the package hook changes it to manual start and disables SCM recovery; a power loss in that interval can leave the service at its installer default. A failed cleanup keeps the journal and diagnostics for recovery. Use `-ListOnly` to print the selected workflows without setting up or running the suite.

If both the runner and watchdog are terminated, or Windows loses power/restarts during service registration, the runtime or service registration can remain because this harness does not install a boot-time cleanup task. From the repository root in an elevated PowerShell window, run the watchdog's explicit recovery command after confirming the runner is no longer active:

```powershell
$harness = [IO.Path]::GetFullPath((Join-Path $PWD '..\test_harness'))
.\scripts\IntegrationSuiteWatchdog.ps1 -JournalPath (Join-Path $harness 'private\suite\active.json') -CleanupOnly
```

### Harness contents and retention

The runner uses the harness root itself; there is no nested `test-harness` directory.

| Path | Contents |
| --- | --- |
| `cache` | Pinned archives, Maven dependencies and Selenium Manager binary cache |
| `tools` | Extracted JDK, Neo4j, Maven, Python, Firefox and geckodriver |
| `maven-user`, `maven-repository` | Maven wrapper distribution and local artifact repository |
| `build` | Maven build outputs and release package |
| `runs\test-host` | Test database, runtime configuration, fixtures and service installation |
| `private` | Owner-only DPAPI credential blob, watchdog journal and generated TLS material |
| `tmp` and `runs\\test-host\\tmp` | Windows, Java and test-process temporary files |
| `results` | Per-invocation logs and generated verification packages |
| `preserved` | Previous runtime and package trees retained when a clean runtime is prepared |

The setup phase reuses checksum-verified downloads and Maven dependencies, but prepares a new database/runtime tree for the suite. Results and preserved evidence remain in `test_harness`; remove the harness directory manually only after no runner, watchdog, service or runtime process is using it. The harness password is protected with Windows DPAPI for the current Windows user and machine, stored in an owner-only file, and replaced when a new database runtime is prepared; the suite does not create temporary Windows Credential Manager entries. Setup, the full suite and watchdog/service cleanup were exercised on this Windows host; [the hub status record](../deposit-hub/implementation_status.md#latest-assembled-regression) gives the 21 October 2026 revision-specific results and qualifications. This provides no clean-machine or cross-host verification.

### Earlier machine-level test service

The earlier harness used a machine-level Neo4j service and separate local data. That installation and its credentials are outside the current `test_harness`; the current runner does not inspect or manage that separate root or credential entry.
