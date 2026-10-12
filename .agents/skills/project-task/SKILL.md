---
name: project-task
description: Plan, clarify, implement, test, or record an individual bug fix, feature, refactoring, or development task in a repository tracked in agents-hub, excluding numbered master-plan stages.
---

# Individual project task

Follow the requested phase and applicable AGENTS.md. Read the linked project
entry in agents-hub and relevant supplementary knowledge bases discovered there.
Use their documented ownership of design and status. Do not assume a master plan
or supplementary KB exists. Inspect relevant current source and tests.

## Plan and clarify
For a planning request, analyze without writing files. In Plan mode, produce a
scoped solution with affected components, interfaces, steps, acceptance criteria
and verification strategy. For material questions, present a numbered list with
a recommended answer and rationale for each. Use judgment on routine details.
Keep routine implementation with Luna. Invoke `expert_implementer` only for
implementation escalation defined in shared AGENTS.md: after two consecutive
evidence-driven correction attempts fail on the same underlying problem,
repeated regressions, a technically complex defect needing substantially deeper
code reasoning, or a well-supported problem Luna cannot resolve reliably. Count
correction attempts, not individual failed tests. Do not invoke the expert for
ordinary work. This is instruction-guided delegation, not a deterministic
runtime trigger.

For escalation, Luna stops editing the affected files and sends a focused
handoff containing task and acceptance criteria, relevant source paths,
architectural constraints, current diff, failed tests, and attempted fixes.
Avoid repeating project context already established. Give the expert exclusive
ownership of affected files until it reports back; prevent simultaneous edits.
Luna coordinates, reviews and validates its changes, and resumes normal work
after a successful intervention. After a failed intervention, require an
explicit diagnosis and new evidence before another expert invocation. Do not
let escalation end authorized implementation prematurely.

Delegate architectural questions and substantive follow-ups to architect as
defined in shared AGENTS.md. Ordinary repeated implementation failures go to
the expert first; changes to approved interfaces, invariants, security-sensitive
design, and cross-component contracts remain architect matters. The expert must
raise architectural contradictions to Luna rather than deciding them or
starting another agent. Retain approved decisions; do not repeat analysis of
the same issue when no new information or concerns have arisen.
Do not implement until instructed. Acceptance of recommendations alone is not
authorization.

## Implement
An explicit implementation request authorizes this phase, including when it is
the first request for a straightforward task; do not demand a redundant approval.
Respect Plan mode and any narrower scope. Implement the agreed behavior, preserve
existing contracts, and follow repository build/test instructions. Run focused
tests and necessary regression checks, investigating failures. Escalate difficult
implementation repairs to `expert_implementer` when the shared thresholds apply,
with concise evidence. Consult architect for architectural uncertainty, not
ordinary implementation failures. Follow
the shared **Implementation continuity** rule: keep working until the agreed scope
and required verification are complete. Resolve new material design questions
with architect and the user as needed, while continuing all unaffected work; a
question or incomplete result alone does not permit ending implementation. Hand
back incomplete work as blocked only for an external action obstacle explicitly
approved by architect after reasonable repository remedies are exhausted.

## Report and document
Report the change, executed verification, results, limitations and remaining work.
Only when explicitly requested, update authoritative design or
implementation-status records in agents-hub and/or its linked KB. For requested
documentation updates that record significant architectural decisions, ask
architect for a read-only consistency review against approved decisions, existing
specifications, and relevant cross-component contracts. Luna resolves any
identified inconsistencies before reporting completion. Routine documentation
changes do not require review. Preserve document organization, record actual
verification, and distinguish incomplete work. Do not modify unrelated records.
