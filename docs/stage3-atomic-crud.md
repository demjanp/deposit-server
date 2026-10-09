# Stage 3 atomic actions and typed CRUD

Build identity: `0.0.0-stage3`. Acceptance is established only by the Stage 3 checkpoint and linked hub status. The runtime/dependency pins remain unchanged.

## Action and transaction flow

API classes adapt public rows, services authorize and validate, and repositories persist through the injected transaction. Domain command handlers are shared by standalone mutations and BatchService. Commands never open transactions or invoke procedures recursively. The action boundary owns authentication, captured Namespace/administrative mode, the transaction mutation guard, actor and Namespace locks, expected revision, audit advancement and durable receipt. Batch permission validation uses the strongest included command role before replay; command execution also validates its own role.

Batches retain the original ordered request in the keyed canonical request hash, resolving only top-level documented ID references during execution. They return detached per-command snapshots, including entities subsequently changed or deleted. A single changed command makes the action changed even if later commands restore all initial values. All-no-change actions save receipts without revising audit or sequence. A failure terminates the enclosing transaction. History stays disabled; no revision ID is fabricated.

## Domain behavior

Relationships derive their category from type and endpoint labels. The Namespace/category/endpoints/label registry commits with each edge; membership retains its existing Namespace/Object key and stable UUID through reassignment. Reassignment preserves incident Object edges and reconciles both descriptor declarations and Class Relationships, including self-relations. Old Class Relationships remain until explicitly removed. Cascades, delete.all and Namespace deletion remove all implemented live categories and corresponding registries while retaining durable receipt bindings.

User Tools are Namespace-contained native nodes with portable opaque String data, including whitespace, Unicode and non-JSON content. No content parsing or entity-reference enforcement occurs. The declaration order/comment setters replace the former combined setter. Class Descriptor names are fixed after creation.

Read/Page selection shares decoding, scoping and ordering. Classes/declarations sort by order and UUID; other lists sort by stable IDs. Typed equality compares normalized values. Reads retain revision checks throughout emission and reject mixed-revision results. Current reads materialize ordered results in memory; relationship ID lookup scans the scoped category. These are explicit scaling limitations, not pagination limits.

## Cancellation and recovery

Authenticated request IDs map to the owning transaction until commit/rollback. Reuse in another active transaction is rejected. Cancellation validates the actor/current administrator without waiting for the target's authorization or scope lock, marks termination, and returns whether an active target was found. Its server metadata reads the committed counter without acquiring the target's server lock. Ordinary protected actions retain their authorization fences and commit-time checks. Termination does not promise rollback after commit; receipts resolve unknown outcomes.

The independent harness includes a loopback TCP relay that suppresses a real commit response after the command result. It verifies the receipt through an independent connection, disconnects the original connection and proves replay adds no effects. Separate tests use installed probe commit failure/clock controls, explicit rollback, cancellation, graceful restart and process termination. Production distributions include no test relay or probe procedures.

## Verification and reuse

Retained/adapted components: accepted Stage 1/2 codecs, cursor signing, authentication fences, transaction completion, repository primitives and filesystem-based gate infrastructure. No historical Java source is adopted and the root src tree remains inactive. The independent client tests import no plugin helpers.

Run `scripts/Invoke-Stage3Gate.ps1 -RunPrefix <fresh-id> -Port 17770`. It requires and preserves accepted Stage 0/1/2 checkpoints; fingerprints and snapshots both repositories; sequentially runs probe and normal distributions on the existing stage0-host installation; validates cumulative runtime/security/runner and Stage 1/2/3 scenarios; and records stage3-checkpoint.json under `C:\data_processed\Deposit_v2\stage0`. Missing, failed, skipped or stale-input evidence prevents acceptance. The server is stopped after the gate. No Git operations occur.

Resources/DGeometry follow Stage 4, recorded history Stage 5, queries Stage 6, Patterns/exchange Stage 7 and later import/browser/release scenarios their own gates. Stage 3 evidence establishes scenario subsets only.
