> Historical implementation note for the named stage and its preserved source snapshot. The current Stage 6.5 public API and coordination changes are described in [stage65-readable-api.md](stage65-readable-api.md). Earlier signatures and API class names below are historical.

# Stage 1 foundation

The active artifact is `0.0.0-stage1`. Neo4j/APOC 2026.05.0, Spatial 2025.10.0, GeoTools 33.2, JTS 1.20.0 and Temurin 21 remain pinned. Stage 1 adds the intact Bouncy Castle 1.82 BCrypt provider (MIT license); its hash is in `runtime/artifacts.json`, and packaging includes its notice and class inventory. Jackson is provided by Neo4j.

## Layers and atomicity

`deposit/api` adapts procedure arguments and native/portable result columns. Its error boundary terminates failed transactions and exposes safe errors with request IDs; unexpected failures carry a diagnostic reference, never exception messages or credentials. Neo4j adds its standard procedure wrapper; the Deposit cause begins `DEPOSIT_ERROR ` followed by one JSON object.

`deposit/service` authenticates, captures session scope, validates data, enforces roles and revision preconditions, and orchestrates actions. `deposit/repository` executes storage operations using the injected transaction. Repositories never depend on APIs/services. `deposit/common` holds DTOs, codecs, signing and lifecycle state.

All action data, schema growth, membership identities, Namespace audit/sequence, scope bindings and receipts use the procedure's injected transaction. Services never open another transaction to commit part of an action. Schema initialization is a separate startup lifecycle task completed before readiness, not a domain action.

The pinned Neo4j `TransactionExecutionMonitor` adapter publishes server revisions and session login/logout/selection only after commit; rollback discards them. It also handles read-only transactions and rollback before commit preparation. Server locks are transaction-owned semaphores, not thread-owned Java locks. Namespace locks are Neo4j transaction write locks. Dedicated actor lock nodes serialize operation scope reservation; lock order is actor, then server or Namespace. This also serializes one actor's concurrent mutations across Namespaces, a throughput tradeoff to measure before changing.

Each transaction captures one token and Namespace. Selection/scoped-call mixing is rejected in either order. A transaction supports one mutation plus reads; a second standalone mutation terminates it. Full batches remain Stage 3.

## Values, sessions and reads

ClassDescriptor owns the authoritative type. Descriptors store owner/name identity, encoding and payload; explicit null retains the Descriptor without a value. Native scalars, List and DDict are supported. Canonical collection JSON sorts keys and stores nested Integer payloads as decimal strings; Bolt reads recover exact signed 64-bit values. DDict keys named `type` and `value` remain ordinary entries. Native Point is supported; DGeometry is a separate Stage 4 feature.

Objects receive one Class membership atomically. Reassignment preserves membership ID and requires compatible declarations, extending destination schema where absent. Populated Classes/declarations cannot be deleted. Object deletion removes owned Descriptors and membership identity. Concrete labels carry scoped ID constraints; no shared live-ID constraint or redundant entity-kind property exists.

Passwords use BCrypt cost 12, minimum 12 characters and maximum 72 UTF-8 bytes. Sessions use hashed 256-bit opaque tokens bound to the authenticated Neo4j principal, independent selection, 30-minute idle expiry and 8-hour absolute expiry. Login delays begin after five failures in five minutes, increase with failures and are bounded at five seconds; keys use normalized username and connection host. Bootstrap uses an owner-only file beside configuration, consumed only after graph initialization commits. Restart invalidates sessions and resets the in-memory server revision to zero.

Native lists emit complete streams. Pages return one `data.items` row, default 200/max 1,000 items, ordered by stable ID, with 15-minute HMAC cursors. Owner-only per-database cursor keys survive restart. Namespace cursors retain actor/scope/revision; server cursors additionally bind the server run. This foundation collects reads in memory for deterministic ordering and checks revision/termination during iteration and close. Memory use for much larger datasets is a scaling risk for subsequent streaming/query work.

Receipts store portable outcomes and IDs, never native handles or authentication input. Their canonical request identity is HMAC-SHA256 with a domain-separated use of the private per-database key; account receipts therefore do not store a fast, unkeyed verifier of password input. Retain that key with operational backups for receipt replay as well as cursor verification. Authorized identical replay precedes stale-revision rejection. Native handles resolve current state, or null after deletion. Server replay reports the current server revision. Durable actor/operation bindings reject retargeting across Namespaces after selection changes or reauthentication. Receipts persist with history disabled.

## Reuse decisions

| Component | Decision and validation |
| --- | --- |
| Stage 0 runtime/build/Spatial probe | Retained; cumulative installed runtime and Spatial tests remain required. |
| Windows provisioning, process ownership, Credential Manager and packaging | Adapted for new catalog/artifact/provider; validated by cumulative startup/reset/distribution/recovery checks. |
| Input collector/runner | Replaced Git discovery with filesystem manifests/snapshots and stage-bound resumption; validated by filesystem fixtures and real runner interruption/resumption. |
| Historical layer organization | Architectural reference only; new map requests and result contracts. |
| Historical authentication/ServerContext | Rejected: accepted empty tokens and stored Namespace selection by username. Replaced with independent-token behavior tested externally. |
| Historical CRUD/codecs/history | Not adopted; old positional APIs and action/type/history semantics do not establish current compliance. New behavior uses fixed public fixtures. |

No recovered Java source was copied into active modules. Root `src` remains inactive. No Spatial port, binary patch or replacement runtime class is used.

## Scope and acceptance

Explicit `historyMode: disabled` is required for Stage 1. Omitted mode retains its documented persistent default and fails with `HISTORY_UNAVAILABLE`, never silently switching modes. Full account administration, administrative mode and Namespace deletion remain Stage 2; batches, User Tools and broader model coverage Stage 3; resources/geometry Stage 4. History/query/exchange/import/browser/release work and relationship-dependent fixture scenarios remain pending.

Run `scripts/Invoke-Stage1Gate.ps1 -RunPrefix <fresh-id> -Port 17770`. It fingerprints both source trees, runs cumulative Stage 0 runtime/Spatial/runner checks, then tests the normal distribution through independent Bolt clients, more than 1,000 Objects, concurrent writes, graceful restart and actual process termination. It preserves the accepted Stage 0 checkpoint and writes `C:\data_processed\Deposit_v2\stage0\stage1-checkpoint.json`. Evidence, UUID maps, graph state, snapshots, artifacts and logs stay under the external root.

The integration harness imports only the driver/test libraries. Expectations and graph assertions do not call plugin helpers. Acceptance covers only delivered portions of A01-A05/A24/A26/A27; whole IDs remain incomplete. Native raw Cypher is outside Deposit guardrails on Community.

The runner performs no Git operations. Filesystem identity includes additions and detects changes/deletions, excluding Git/editor/build metadata and the existing hub status-only document. Snapshots verify captured bytes against their manifest. The user commits manually after acceptance.
