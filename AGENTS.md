<!-- BEGIN agents-hub shared routing -->
# agents-hub routing

Project ID: `deposit`

Project entry (relative to this repository root): `../../../agents-hub/projects/deposit`

## Shared project workflow

### Knowledge discovery
Use the project-entry pointer above as the starting point in agents-hub.
Read relevant project instructions and follow its existing links to supplementary
knowledge bases, preferring local sibling checkouts. Use those sources alongside
agents-hub; do not duplicate them or invent a missing KB or master plan.
Respect documented ownership of architecture, plans, design decisions and status.
If sources conflict or cannot be accessed, report the specific gap; ask only if it
blocks the requested decision. Source code and executed tests establish actual
implementation; documentation may describe planned behavior.
Search for relevant symbols and sections before broad reads. Reuse established
project mappings and approved decisions within a task.

### Routing
Use the main Luna agent for conversation, routine planning, implementation,
test execution and documentation. Use project-stage for a numbered master-plan
stage; use project-task for other development tasks and project-run for an
authorized sequence of stages. The architect (`gpt-6-astra`,
medium reasoning) advises on architecture and remains read-only. The
expert_implementer (`gpt-6.1-sol`, high reasoning) can implement difficult fixes
within the implementation scope already authorized by the user.

Keep routine implementation with Luna. Invoke expert_implementer only when at
least one of these conditions applies:

1. Two consecutive evidence-driven correction attempts, each followed by
   relevant tests, fail to resolve the same underlying problem. Count correction
   attempts, not individual failed tests.
2. Corrections repeatedly reintroduce regressions that had already been
   resolved.
3. A technically complex defect needs substantially deeper code-level
   reasoning.
4. Luna identifies a well-supported implementation problem it cannot resolve
   reliably.

Do not invoke the expert for ordinary implementation work. Escalation is
instruction-guided delegation, not a deterministic runtime trigger. Use the
supported custom-agent name `expert_implementer`; do not invent routing settings
in config.toml.

On escalation, Luna stops editing the affected files and gives the expert a
focused handoff: task and acceptance criteria, relevant source paths,
architectural constraints, current diff, failed tests, and attempted fixes.
Avoid repeating project context already established. The expert owns edits to
those files until it returns its diagnosis and changes; do not allow concurrent
edits to the same files. Luna coordinates the work, reviews and integrates the
result, runs required validation, and resumes normal implementation after a
successful intervention. Do not invoke the expert repeatedly for the same
unresolved issue without new evidence. If intervention fails, require an
explicit diagnosis and decide whether architectural consultation or another
evidence-based action is warranted.

Architectural decisions and material questions remain subject to architect
consultation. This includes complex algorithms; security or data-integrity
design; transaction or versioning changes; cross-component contracts; and
questions affecting data models, interfaces, compatibility, transaction
semantics, or architectural invariants. Ordinary repeated implementation
failures go to the expert first; consult the architect when evidence shows
architectural uncertainty or contradiction. The expert does not replace
architect and must ask Luna to arrange architect advice rather than making
material design decisions or starting another agent. Also consult architect for stage
architecture and other material architectural questions, including challenges
to prior recommendations, comparisons of alternatives, and proposed changes to
previous decisions. Keep routine clarifications and minor implementation
details with Luna. Avoid redundant consultation when no new evidence or concern
has arisen. Luna presents the architect's advisory conclusions to the user.
Outside project-run, architect approval is required only for an incomplete-work
interruption under the external-blocker rule below. Project-run additionally
requires independent post-implementation architect review with no unresolved
blocking findings before stage completion.

### Implementation continuity
Once implementation is explicitly authorized, continue until the agreed scope
and required verification are complete. Missing features, stubs, failing tests,
absent acceptance-gate tooling, complexity, elapsed time, and context compaction
are work to resolve, not grounds for ending the implementation. Consult architect
when required and keep progressing on work that is not blocked.

An incomplete implementation may be handed back as blocked only when evidence
shows that resolution requires an action outside the repository and architect
explicitly approves the interruption. Give architect the observed failure,
remedies already attempted, remaining work, and exact external action required.
Architect must confirm that no reasonable remedy within the authorized
repository work remains. Finish all unblocked work before handing back, and
report the approved blocker, external action, remaining work, and resumption
criteria. Architect approval cannot authorize a scope change or override the
user's instructions. Honor an explicit user stop or change of scope and
higher-priority execution restrictions.

Luna writes and updates documentation only when the user explicitly requests
those changes. For a significant architectural decision recorded in documentation,
have architect perform a read-only consistency review against approved decisions,
existing specifications, and relevant cross-component contracts. Luna resolves
any inconsistencies identified before reporting completion. Routine documentation
changes do not require architect review.

For architect consultation, send a compact handoff with the requested phase,
repository/project-entry/KB paths, approved decisions, exact question, relevant
files and observed failures. Ask architect for advisory analysis, not
implementation. If architect or its configured model is unavailable, disclose
this; do not claim architect review occurred. Resolve a model change with the
user when specialist analysis is necessary. Routing delegates to a separate
agent; it does not change the main conversation's model.

### User-controlled phases
Follow the phase requested by the user. A request to plan or discuss permits
read-only analysis, not implementation. Accepting recommended design decisions
does not authorize code changes. Start implementation only when explicitly
instructed; a direct instruction to implement already supplies that authorization.
Update design documentation and implementation status only when requested.
Do not ask again for authorization already given for the current phase.
Plan mode must be respected: do not write files in that mode. If a requested
write is blocked by the current mode, explain the needed mode change.

### Authorized project runs
An explicit request to execute a bounded project-run supplies authorization for
its planning, implementation, verification, necessary design/status updates,
review corrections and separate stage commits. This is the narrow exception to
separate user-controlled phase requests above; project-stage and project-task
invoked independently retain their existing behavior. A planning-only run request
does not grant execution authorization. Respect narrower user instructions.

Use the deployed project-run skill for scope discovery, explicit external plan
and tracker files, fresh Luna stage workers, independent architect review and
checkpoint recovery. Root coordinates specialist calls; workers do not spawn
agents. Preserve existing models, concurrency limits and expert escalation rules.
Resolve routine details automatically; material changes to approved architecture,
scope, compatibility or requirements require user input. Continue unaffected
work. Update the selected tracker after every verified and reviewed stage and
finish its commits before advancing. Do not push automatically.

Actual Plan mode and runtime permissions remain binding, including for child
agents and local checkpoints. Use a read-only planning phase inside editing-
enabled runs, not automatic mode switching. User decisions, explicit stops,
mode/permission restrictions and unavailable required specialists can interrupt
a run independently of the external-blocker assessment; do not claim architect
approval is needed to obey higher-priority restrictions. Preserve recovery
evidence and resume without repeating completed work.

### Verification and reporting
Follow the project's actual build and test instructions. Run focused tests and
necessary regression checks; address failures, including reporting unrelated
pre-existing failures. Never report unexecuted checks as passed. Report changes,
verification evidence, limitations and remaining work accurately. Do not mark
untested acceptance criteria as verified. Respect existing repository instructions
and the user's explicit scope; do not push or publish merely to record status.
<!-- END agents-hub shared routing -->
