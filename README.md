# Deposit Server

Deposit Server is the Neo4j plugin implementation for Deposit 2.0. The Deposit Hub is the authoritative home for product contracts, implementation planning and acceptance status.

## Current state

The build label is `0.0.0-stage65`. Stages 0-10 are accepted for their recorded source snapshots. Stage 11 Windows release and recovery is complete in its approved server scope, with evidence qualifications recorded in the [implementation status](../deposit-hub/implementation_status.md#current-status). Python wrapper and GUI implementation remain pending.

## Project documentation

- [Server specification and Windows operations](../deposit-hub/deposit-server.md)
- [Readable procedure API](../deposit-hub/deposit-server-api.md)
- [Current implementation status, maintenance workflow and acceptance history](../deposit-hub/implementation_status.md)
- [Selected test runner](scripts/Invoke-Tests.ps1)
- [Seed, transition and verification scenarios](scripts/Invoke-TestScenario.ps1)

Use the selected-test and scenario scripts above for current verification. Cumulative stage gates and the legacy Codex/work-package runner have been retired and removed. The inactive historical source formerly under the root `src` tree is preserved at [immutable commit `988e21b524befcd419dd6f1a950c1f170f44c60e`](https://github.com/demjanp/deposit-server/tree/988e21b524befcd419dd6f1a950c1f170f44c60e/src); the active Maven reactor is `plugin`, `runtime-probe` and `integration-tests`.
