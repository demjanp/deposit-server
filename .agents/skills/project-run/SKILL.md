---
name: project-run
description: Execute a bounded sequence of master-plan stages using project-stage, with separate planning, implementation, verification, independent review, status updates, commits, and resumable progress. Accept explicit external Markdown plan and status files.
---

# Project run

Coordinate an authorized run; use [project-stage](../project-stage/SKILL.md)
for stage work and shared AGENTS.md for specialist routing. Do not add agent
roles, change models or permissions, or start a separate orchestration service.

## Establish scope and authority

Accept the project and consecutive stages, "next N stages", or "next incomplete
through Stage X". Ask for a missing boundary rather than assuming the whole plan.
Accept optional explicit implementation-plan and implementation-status `.md`
files independently. Resolve relative paths from the starting working directory.
They may be outside agents-hub, in other repositories, or outside Git. Verify
that the selected files exist and are readable; inspect applicable instructions
and actual checkouts, including nested instructions for affected files.

Explicit file selections override discovery, not higher-priority instructions.
Discover only omitted files using the project-entry pointer and linked sources.
Do not silently substitute another file if an explicit path is inaccessible,
missing, or conflicts with authoritative instructions. Report the precise gap.
Use the selected plan for order, dependencies, designs and acceptance criteria;
use the selected tracker for recorded progress, checked against implementation
evidence. Reconcile conflicting status before skipping work.

Treat stage IDs as labels. Follow documented order, including intermediate stages
such as 5.5, rather than incrementing or sorting numbers. For next-N and
next-incomplete requests, start at the next incomplete stage. For explicitly
named stages, retain that selection; skip only evidenced completed stages.
Check prerequisites before each stage. An incomplete prerequisite outside the
authorized selection requires a scope decision, not silent expansion. Report
the resolved sequence and sources without requesting redundant confirmation.

An explicit request to execute the selected stages authorizes their planning,
implementation, verification, review corrections, necessary design/status
documentation and separate stage commits. It does not authorize material design
changes, unrelated work, pushes, publication, or permission changes. A request
only to plan, explain or preview this workflow authorizes no writes or commits.
Actual client Plan mode remains read-only, including checkpoint writes and
delegated work. Explain the needed editing-enabled mode and return a plan; never
attempt to switch modes or bypass the restriction through a child agent.

## Coordinate one stage at a time

Keep root Luna focused on progress, decisions and coordination. Start a fresh
default Luna subagent for every successive stage, without prior stage transcripts
where the runtime supports fresh contexts. Supply a compact handoff: run scope,
exact selected plan/tracker references, stage ID, approved decisions, dependency
evidence, repository baselines, constraints, and the requested phase. Have workers
return concise evidence summaries and request specialists through root; they
must not spawn agents or independently advance stages, commit, or update status.
Root owns the checkpoint and finalization. Retire completed workers using the
available lifecycle tools; never exceed configured concurrency limits.

1. **Plan, read-only.** Explicitly dispatch project-stage initial analysis and
   implementation planning. Root arranges its required architect consultation.
   Return ordered work, affected repositories/files, interfaces/invariants,
   acceptance criteria and authoritative verification commands. This is a
   read-only phase within editing-enabled execution, not a client mode switch.
2. **Resolve decisions.** Resolve routine details autonomously within approved
   requirements. Consult architect for significant architectural uncertainty.
   Ask the user before decisions materially change approved architecture, scope,
   compatibility or requirements; never treat architect advice as approval.
   Record the decision and finish unaffected work while awaiting input. Do not
   re-ask for phase authorization already covered by this run.
3. **Implement and verify.** Once the plan is settled, explicitly send a separate
   implementation instruction to the stage worker. Record necessary approved
   design changes under project ownership rules. Apply project-stage's preflight,
   focused/regression tests and full acceptance gates. Root dispatches
   expert_implementer only at existing shared escalation thresholds; stop the
   worker's edits to assigned files until the expert returns. Root arranges
   architectural consultation when needed. Never count missing tests as passed.
4. **Independent review.** After implementation and verification, root starts a
   fresh architect review context, separate from the implementing worker and
   earlier design consultation. Provide the settled plan, approved decisions,
   criteria, repository baselines, actual changed source/tests and evidence.
   Require review of the implementation, not just a worker's summary. Resolve
   blocking findings, rerun affected verification, and request another review
   of corrections. Missing mandatory evidence or unavailable review is not a
   pass. Do not mark complete or advance with unresolved blocking findings.
5. **Finalize.** Root updates the selected tracker after successful verification
   and review, preserving its structure. Record completed scope, actual test
   evidence, review outcome, deviations and remaining nonblocking items. Review
   this final documentation diff for consistency; material changes require
   affected verification/review again. Complete the commit/checkpoint procedure
   below before starting the next stage with a fresh worker.

Honor shared implementation continuity: missing work, failed tests or absent
gate tooling are work to resolve. For a genuine external blocker, obtain the
architect's assessment after exhausting reasonable authorized remedies. User
decisions, explicit stops, actual Plan mode, runtime/approval restrictions and
unavailable required specialists are separate interruption conditions; they do
not require or permit an architect to override those restrictions. Finish safe,
unaffected work, checkpoint when writes are allowed, and report the exact input
or external action needed. Do not repeatedly retry without new evidence.

## Tracker and stage commits

Capture each affected repository's HEAD, branch, staged/unstaged changes and
untracked files before edits. Preserve unrelated and pre-existing work and its
index state. Stage only attributable stage changes after reviewing the exact
diff. Never use blanket staging, destructive cleanup, automatic stashing or
history rewriting. With a dirty index, use an isolated index or an equally
safe targeted procedure and verify unrelated staged content remains intact.
After an isolated-index commit, reconcile only the committed paths in the real
index so the commit does not leave reverse changes staged; preserve all other
staged content. Recheck HEAD/index/worktree against the captured baseline before
finalization to detect concurrent edits. Capture the content baseline of an
unversioned tracker too, and reconcile changes before updating it.
If overlapping changes cannot safely be separated, ask for a targeted decision.

Update the selected tracker **after every completed stage**, before advancement.
If that update fails, retain implementation evidence and stop advancement until
resolved. If the tracker is outside Git, update it in place and record that it
is unversioned; do not initialize a repository or copy it into agents-hub.

Create a separate stage commit in every repository with attributable changes,
including the tracker repository. Use a common run ID and exact stage ID in
commit messages, for example `project-run <run-id>: Stage <id> - <summary>`.
Commit implementation repositories first, then a separate tracker repository.
When source and tracker share a repository, include both in its stage commit.
Do not create empty commits for unchanged repositories. Review the resulting
commits and verify they contain only intended changes. A hook failure is not
permission to skip hooks or acceptance gates; changes made by hooks require
revalidation. Never push automatically.

Tracker completion means verified/reviewed implementation; the checkpoint also
tracks pending commits. A stage is not finalized for advancement until its
tracker update and all required commits succeed. Cross-repository commits are
not atomic: preserve successful commits if a later one fails, record the partial
state, and resume outstanding commits rather than duplicating or reverting work.

## Checkpoint and resume

In the coordinating checkout, resolve the checkpoint with
`git rev-parse --git-path project-run/state.json`; resolve a relative result
against that checkout. Do not assume `.git` is a directory (worktrees use files).
Keep this local JSON separate from deployment's `.codex/agents-hub-state.json`.
Read any existing checkpoint before creating a run; never overwrite an unrelated
unfinished run without resolving which run the user intends to resume.

Use a versioned (`version: 1`) JSON record containing:

- Run ID, authorized scope and constraints, starting working directory, exact
  selected plan/tracker references and their content hashes, ordered stage IDs.
- Current stage/phase, approved decisions and pending user questions, repository
  identities/branches/starting HEADs and baseline index/worktree evidence.
- Per-stage plan summary, changes, executed verification results, acceptance
  mapping, reviewed diff identity, findings and their resolution.
- Tracker update state, intended commit messages/repository order, each resulting
  commit ID, pending commits, remaining work and precise resumption conditions.

Write atomically at phase boundaries outside read-only planning, before and after
each commit, and on interruption when writes are allowed. Keep machine-specific
external document locations only in this local checkpoint/handoffs, never in
tracked hub documentation. Use portable references in tracked status records.
Do not put secrets or raw datasets in checkpoints.

On resume, reconcile checkpoint, current source/diffs, selected documents and
Git history before acting. If interrupted between commit and checkpoint, locate
the intended commit by run/stage identity and verify its diff/parent before
recording success. Do not recreate a commit merely because its hash is absent.
Reuse completed planning, tests and reviews only when their input evidence still
matches; rerun affected gates when source, requirements or reviewed changes have
changed. Reconcile external edits instead of overwriting them. A stale tracker
must not hide pending finalization. Resume the unfinished phase, not the stage
from scratch. Report final per-stage evidence and repository commit IDs.

## Goal mode and limits

Work under the existing Goal when invoked with `/goal`; create a Goal only if
explicitly requested and supported. Do not create child goals, replace unrelated
goals, set unsolicited budgets or manipulate client modes. Follow the runtime's
Goal lifecycle contract, including user/system pause, resume, blocker and budget
rules. A workflow interruption record does not itself authorize changing Goal
status. Mark complete only after every selected stage is verified, reviewed,
recorded in the tracker and committed where applicable. A budget stop is not
completion; preserve recovery information whenever allowed.

Without Goal mode, follow the same authorized sequence while execution remains
available; do not promise continuation after the client stops. Delegation and
gates are instruction-driven, not a deterministic scheduler. Tool/model access,
permissions, budgets and client lifecycle can interrupt progress. Disclose
missing capabilities rather than silently substituting specialists or claiming
a review. Local checkpoints do not automatically transfer between machines.
