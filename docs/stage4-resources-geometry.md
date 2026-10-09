# Stage 4 resources and geometry

Build identity: `0.0.0-stage4`. Acceptance requires the Stage 4 checkpoint; development passes do not establish acceptance. Runtime/dependency pins remain unchanged.

## Contracts and transaction flow

Resource/geometry APIs adapt structured rows. Services authenticate, capture Namespace scope, validate input and orchestrate action/receipt boundaries. Repositories operate on the injected transaction; filesystem utilities contain no graph or authorization logic. Domain Descriptor reads, writes, conversions and deletion share value lifecycle methods. Geometry and resource values are prohibited at every collection nesting depth, including null entries and empty typed Lists.

Resource operations use durable actor/operation/scope bindings without advancing server or Namespace revisions. beginUpload resumption returns current committed progress; chunk, finish and prune scheduling receipts retain their original outcomes. Info/download calls renew permitted inactivity leases but use read contexts. downloadUrl is null until Stage 10.

Private Namespace coordination nodes survive Namespace deletion. Existing authorization and actor/server guards precede Namespace coordination and per-resource protections. Multi-Namespace pruning acquires Namespace locks in UUID order. Storage protections survive procedure return through transaction completion; commit/rollback release them. Namespace-level serialization is deliberately conservative and limits simultaneous transfers/reads within one Namespace.

## Durable resources

Transfers persist stable resource identity, metadata, checksummed chunks and contiguous offsets. Flushed staging bytes precede chunk receipts; recovery trims uncommitted tails. Finish verifies and atomically publishes the immutable file before ready metadata commits. A retry recognizes the reserved published path after rollback/crash. Different resource IDs never deduplicate files.

Reference changes commit placement intent and first-attachment state. Maintenance persists a destination, then moves content and records completion; reads resolve either verified location. Private cleanup records survive Namespace deletion. Retention covers live references, upload leases, export grants and generic future history/plan/backup leases. Producer integrations remain in later stages.

Pruning defaults to dry-run and a 48-hour minimum orphan age. Real calls schedule durable jobs after commit. The status procedure reports pending/completed/failed counts, confirmed reclaimed bytes and sanitized diagnostics. Deletion intent precedes unlink, excludes new attachment and survives an interruption before completion accounting. Startup reconciles transfers, placements and deletion intents before readiness; diagnostics retain recovery failures.

## Geometry and reuse

JTS validation and conversion preserve all six supported types, XY/XYZ coordinates, holes and CRS metadata. DGeometry nodes belong to direct Descriptors and retain IDs on edits. Existing-node Spatial registration, empty-tree handling, bbox refresh and transaction finalization were adapted from the accepted Stage 0 probe into the production repository; no historical root-src implementation was compiled or adopted. Layer instances are cached per transaction and keyed by Namespace/horizontal SRID; registry updates are serialized. Storage fails on incompatible layer configuration or invalid index membership. Internal rebuild removes memberships, recreates layers and re-registers unchanged domain nodes with writes excluded.

The normal plugin exposes no test control procedures. The separately packaged StorageProbe supplies reflection-based fault barriers, grant fixtures, index inspection and rebuild invocation for deployed verification. The independent client harness does not import implementation helpers for expected results.

## Acceptance

Run `scripts/Invoke-Stage4Gate.ps1 -RunPrefix <fresh-id> -Port 17770`. It preserves accepted Stage 0-3 checkpoints, captures filesystem source fingerprints/snapshots, exercises normal/probe distributions sequentially on stage0-host, and requires cumulative tests plus eight actual termination/restart storage boundaries and the real CLI interruption fixture. Missing/failed/skipped/stale evidence blocks acceptance. Evidence and generated outputs stay under the existing external Stage 0 root; no Git operations occur.

History remains disabled. Queries, exchange/import, public export producers, browser links/listener and release backup/recovery follow their roadmap stages.
