---
name: project-stage
description: Plan, clarify, implement, test, or record a numbered stage of an existing project master plan for a repository tracked in agents-hub. Use for stage work, not standalone tasks without a master-plan stage.
---

# Project stage

Follow the requested phase only; do not automatically advance through this list.
Use the applicable AGENTS.md project-entry pointer to read relevant agents-hub
material and any linked supplementary knowledge base. Find the actual master
plan and authoritative design/status destinations. If Stage N cannot be found,
report that gap rather than inventing it. Preserve established project structure.

## 1. Initial analysis
Read Stage N, dependencies, approved designs, relevant status and source.
Delegate architectural analysis to architect with a scoped handoff.
If material unresolved questions remain, return only a numbered list of questions,
each with a recommended solution, short rationale and significant tradeoffs.
Otherwise return a design/implementation proposal with scope and acceptance
criteria. Do not start implementation or write documentation.

## 2. Resolve questions
Discuss answers, clarify requested points and retain the user's approved choices.
Accept recommendations when instructed. Resolve routine details using judgment;
ask about decisions that materially affect scope, interfaces or compatibility.
For substantive architectural follow-up questions, including challenges to
recommendations, comparisons of alternatives, or changes to proposed decisions,
follow the architect consultation rule in AGENTS.md. Do not repeat analysis of
the same issue when no new information or concerns have arisen. A decision's
approval is not implementation authorization.

## 3. Record design
When explicitly requested and editing is allowed, Luna updates the authoritative
design documents in agents-hub and/or linked KB as dictated by their ownership
rules. Record approved decisions, rationale and implications without copying
entire knowledge bases. For significant architectural decisions, have architect
perform a read-only consistency review against approved decisions, existing
specifications, and relevant cross-component contracts. Luna resolves identified
inconsistencies before reporting completion. Routine documentation changes do
not need this review. Do not change application code. Report the exact documents
changed.

## 4. Plan implementation
In Plan mode, prepare ordered steps and dependencies based on the settled design.
Include affected repositories/files, interfaces and invariants, acceptance criteria,
verification commands/strategy and material risks. Ask remaining questions with
recommendations. Do not reopen approved decisions without new evidence; do not
implement. A textual instruction cannot itself change the client's mode.

## 5. Implement and verify
Start after explicit instruction to implement and when editing is allowed.
Use Luna for implementation and project-specific build/tests. Consult architect
for the escalation cases in AGENTS.md; it remains advisory. Correct failures,
then run the necessary regression checks. Report actual results and incomplete
criteria. If new evidence demands a material design change outside authorization,
complete unaffected work and present the question before the affected changes.
Do not automatically update knowledge-base status or design records.

## 6. Record status
When asked, update the authoritative implementation-status location, preserving
its format. Distinguish implemented, verified, partially complete and blocked work.
Record relevant test results, remaining items and deviations. Do not mark a stage
complete when required criteria remain unverified. Refer to existing designs
instead of duplicating them.
