> Historical implementation note for the named stage and its preserved source snapshot. The current Stage 6.5 public API and coordination changes are described in [stage65-readable-api.md](stage65-readable-api.md). Earlier signatures and API class names below are historical.

# Stage 5 history implementation

This document describes the code work package. Installed acceptance is recorded separately in the hub implementation status and external Stage 5 checkpoint; this file describes the implementation, not acceptance results. The pinned Neo4j/APOC/Spatial/JTS/Temurin stack is unchanged. The active reactor remains `plugin`, `runtime-probe`, and `integration-tests`; no legacy history implementation was adopted.

## Layers and transaction flow

`HistoryAPI` exposes the request/context procedures and adapts their results. `HistoryService` owns permissions, selection, preview validation, mode/tag orchestration, and reversal actions. `HistoryRepository` owns canonical graph capture, history persistence, explicit resource retention, and dependency-ordered graph application. `HistoryCodec`, `ChangeSet`, `Revision`, `UndoPlan`, `RestorePlan`, and `HistoryPlanner` are shared portable state and planning components. `Versioning` provides the documented transaction-bound helper facade; it never opens or commits an independent transaction.

The public surface adds eleven procedures (the work package's list of ten also requires the separately documented `deposit.utils.restore`): `get.revision`, `get.history`, `get.historyPage`, `get.restorePreview`, `utils.undo`, `utils.redo`, `utils.restore`, `utils.canUndo`, `utils.isRestored`, `set.versioningType`, and `set.versionTag`. The normal catalog now contains 88 procedures. Mutation calls remain outside the batch allowlist. Existing page defaults and limits apply: 200 records by default, at most 1,000 per page; history streams are not capped at 1,000.

The existing `ActionService` boundary authenticates current authority, acquires the actor and Namespace coordination locks, checks operation binding and receipt replay, then checks the expected sequence. Fresh scientific work captures an action-local journal only after that replay boundary. Each batch command checkpoints the journal before executing the next command. The journal retains the first before-image, final after-image, union of scientific field touches, historical kinds, and schema/structural dependencies. A changed-then-restored batch therefore remains an action even when its final scientific diff is empty. Per-command portable results remain frozen in the saved batch receipt.

After successful work, Namespace sequence/audit, revision, snapshots, retention links, and receipt are written in the same injected transaction as domain and Spatial changes. Cancellation, rollback, failed commands, stale sequence, and commit failure roll back that graph transaction. Ordinary no-ops, including restore to an identical scientific state, save the normal receipt without advancing sequence or recording history. Successful undo/redo always append a revision, including an empty scientific inverse. Server, account, transfer, role, mode and tag operations do not acquire reversible scientific snapshots; Namespace name/description edits do.

## Images, epochs and reconstruction

Canonical images explicitly map scientific node fields and portable relationship endpoints. They never use aggregated public Object/Class maps. Descriptor images carry ownership/name/value but no type. The planner reconstructs the epoch baseline plus ordered changes, checks before-image continuity, and resolves Descriptor types through that reconstructed state's membership and Class declaration. Audit-only differences do not become conflicts or restore edits. Snapshot category and image category must agree; image shape, ownership, endpoints, declarations, uniqueness, supported values and Class relationship dependencies are validated.

`Revision`, `DepositSnapshot`, `DepositHistoryEpoch`, and `RevisionTag` have history-only labels and scoped identity constraints. Revisions have Namespace/sequence and epoch/sequence indexes; snapshots have an epoch index. Revisions and snapshots are immutable through public APIs. Tags are separate owner annotations. Transient revisions store their epoch boot ID. Each snapshot stores existence flags, portable category, logical identity and JSON images. An intermediate entity created and deleted within a batch still retains its category and touches. No historical node joins a live Spatial layer.

New enabled Namespaces get a sequence-zero baseline. Later enabled epochs start at the mode-change sequence. Persistent epochs remain indefinitely browsable after closure. Transient-to-persistent promotes the epoch in the action transaction; revision contents are not rewritten. Epoch metadata is authoritative for retention after promotion. Disabled mode continues sequence/receipt behavior without new scientific history.

`HistoryLifecycle` invalidates old transient epochs durably before resource recovery or ordinary readiness. It commits cleanup in batches of at most 128 history records and resumes from persisted expired epochs after a crash. It then atomically publishes a fresh baseline only for currently transient Namespaces (and initializes a missing enabled persistent baseline during startup migration). Cleanup never removes live domain data or advances user sequence/audit. An internal Namespace availability generation invalidates history cursors even when the scientific sequence is unchanged. Closed transient epochs are also expired, including when the current mode is disabled.

## Reversal and restore

Default undo first selects the actor's latest eligible action in the current enabled epoch. A conflict never causes fallback. Editors reverse their own actions; an owner can explicitly select another actor's action. Viewer `canUndo` is inspection, not write permission. Baselines and undo revisions cannot be undone. Redo reverses undo, and a redo can itself be undone. The planner tracks the current tip of each reversal chain so earlier IDs cannot reverse the same applied effect twice. A prior undo can be redone again only after its redo effect has been undone.

Scalar inversion merges only touched scientific fields, preserving unrelated later work. Creation, deletion, schema names/types, membership, endpoints and structured replacement changes are structural. Class and declaration comments/order remain independently reversible fields. Independent later touches conflict even if values return to the same value, including within one batch. Undoing the intervening effect can clear that conflict. Historical schema dependencies remain part of this check even for null values.

Whole-Namespace restore reconstructs a retained baseline/revision target and computes a validated current-to-target diff. Preview and apply share the same planner; apply uses the preview's expected sequence. No confirmation-token protocol was introduced. Current roles, sessions, mode, receipts and creating-build provenance stay outside the restored scientific image.

Application removes dependent edges and removed/converted values first, removes obsolete owned entities, creates/updates Classes, declarations, Objects and tools, restores memberships, then values and relationships. Explicit repository restoration entry points create nodes/edges/geometries with historical IDs. Original creation audit is preserved, while changed or recreated entities receive the current action's update audit. Unchanged entities are not rewritten to reproduce old audit fields.

## Resource and geometry reuse

The Stage 4 resource lock, immutable file verification, recoverable placement protocol and collector are reused. Before retention or restoration, referenced resources must have ready metadata and verified bytes/checksum. `RETAINS_RESOURCE` links are explicit graph references from snapshots, and valid retained epochs protect collector/orphan accounting. Current viewers/editors/owners can read browsable history-only resources; editor reattachment still requires an authorized reversal. Export grants retain the owner rule. Expired transient links grant neither access nor collection protection. Namespace deletion removes its history atomically while operational cleanup and receipts survive.

Geometry restoration reuses the existing transactional Spatial lifecycle with an explicit historical identity. Index membership/bounds are derived from restored live WKT/CRS/dimension fields. The implementation stores no historical index internals, performs no Spatial source port, and adds no replacement runtime classes. Resource corruption or Spatial failure aborts the complete action.

## Validation and supervisor execution

The new pure unit suite has 19 independent fixed-fixture tests for reconstructed schema, journals, audit exclusion, before-image continuity, canonical shape, field conflicts and reversal chains. Together with the four existing codec tests, the expected unit count is 23.

New installed suites are `Stage5CoreIT` (24 cases), `Stage5ProbeIT` (8), `Stage5RecoveryIT` (one case executed after both graceful and forced restart), and `Stage5CrashIT` (four method names used for 12 invocations across six process-boundary scenarios). The Stage 5 increment is 46 deployed cases. The cumulative final gate expects 89 normal/recovery cases plus 135 environment/probe/crash cases: 224 deployed cases, with no failures, errors or skips. These are expected counts, not executed results.

Startup barriers are provided exclusively by `HistoryStartupProbe` in the probe artifact via a service provider loaded before history initialization. The normal distribution has no provider, probe procedure or public startup control. The host gate arms a specific Namespace/boundary, starts the single existing installation through the ordinary readiness path, terminates the identity-checked JVM, verifies the failed startup checkpoint, then restarts and inspects recovery. Action barriers cover before receipt/commit and after commit. Startup barriers cover invalidation, a cleanup batch, and before/after fresh-baseline publication.

From `C:\documents_synced\Deposit_v2\deposit-server`, the supervisor runs:

```powershell
.\scripts\Invoke-Stage5Gate.ps1 -RunPrefix ('stage5-final-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')) -Port 17770
```

The gate fingerprints/snapshots filesystem sources, builds once, runs cumulative normal/probe distributions sequentially on `stage0-host`, performs graceful/forced restarts and the real CLI interruption fixture, and collects scenario/JUnit timings, fixed fixture UUIDs, sanitized logs and artifact hashes. It verifies that Stage 0–4 accepted checkpoint hashes remain unchanged. Only that supervisor gate may publish Stage 5 acceptance. Hub status/TODO acceptance updates remain pending until its evidence supports them.

## Scaling and remaining scope

This first implementation favors explicit, validated images. Journal checkpoints scan the current Namespace after each batch command; reconstruction and history reads materialize portable state/history in memory. Cost grows with Namespace size, batch length and retained history, and persistent history is intentionally not pruned. The scoped indexes support a future streaming repository implementation without changing the public contract. Large-dataset throughput and installed runtime behavior require the supervisor's deployed checks; unit/build success is not evidence of deployed acceptance.

Patterns, imports/exchange, browser transport, backups and whole acceptance-ID completion remain pending in their later stages. Stage 4's temporary null browser download URL remains unchanged. No alternative runtime, installation, VM or database was introduced.
