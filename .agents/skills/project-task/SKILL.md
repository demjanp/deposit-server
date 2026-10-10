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
Delegate the architectural/escalation cases in AGENTS.md to architect, including
substantive architectural follow-up questions. Retain approved decisions; do not
repeat analysis of the same issue when no new information or concerns have arisen.
Do not implement until instructed. Acceptance of recommendations alone is not
authorization.

## Implement
An explicit implementation request authorizes this phase, including when it is
the first request for a straightforward task; do not demand a redundant approval.
Respect Plan mode and any narrower scope. Implement the agreed behavior, preserve
existing contracts, and follow repository build/test instructions. Run focused
tests and necessary regression checks, investigating failures. Consult architect
for complex or repeatedly failing repairs, with concise failure evidence. Pause
only the affected work for material design decisions outside the approved scope.

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
