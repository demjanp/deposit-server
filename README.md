# Deposit Server

Deposit Server is the Neo4j plugin implementation for Deposit 2.0. The Deposit Hub is the authoritative home for product contracts, implementation planning and acceptance status.

## Current state

The build label is `0.0.0-stage65`. Stages 0-10 are accepted for their recorded source snapshots. Stage 11 Windows release and recovery work is underway in the current server worktree and remains unaccepted. See [implementation status and evidence](../deposit-hub/implementation_status.md#current-status).

## Project documentation

- [Server specification and Windows operations](../deposit-hub/deposit-server.md)
- [Readable procedure API](../deposit-hub/deposit-server-api.md)
- [Implementation plan and gate workflow](../deposit-hub/implementation_plan.md)
- [Acceptance status and evidence](../deposit-hub/implementation_status.md)
- [Stage implementation and acceptance history](../deposit-hub/implementation_status.md)

Follow the hub plan for build, test, release and acceptance procedures. Historical source under the root `src` tree is inactive reference material; the active Maven reactor is `plugin`, `runtime-probe` and `integration-tests`.
