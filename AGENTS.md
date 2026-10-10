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
stage; use project-task for other development tasks.
Delegate stage architecture and material architectural uncertainty to architect.
Also consult architect for complex algorithms, security/data integrity,
transaction/versioning changes, cross-component contracts, or two unsuccessful
repair attempts at the same failure. Require architect consultation for
substantive architectural follow-up questions, including challenges to prior
recommendations, comparisons of architectural alternatives, or changes to
previously proposed decisions. This includes questions affecting data models,
interfaces, compatibility, security, transaction semantics, data integrity, or
architectural invariants. Keep routine clarifications, straightforward
acceptance of recommendations, and minor implementation details with Luna.
Avoid redundant consultation when architect has already analyzed the same issue
and no new information or concerns have arisen. Luna presents the architect's
conclusions and recommendations to the user; architect remains advisory.

Luna writes and updates documentation only when the user explicitly requests
those changes. For a significant architectural decision recorded in documentation,
have architect perform a read-only consistency review against approved decisions,
existing specifications, and relevant cross-component contracts. Luna resolves
any inconsistencies identified before reporting completion. Routine documentation
changes do not require architect review.

Send a compact handoff: requested phase, repository/project-entry/KB paths,
approved decisions, exact question, relevant files and observed failures. Ask for
advisory analysis, not implementation.
If architect or its configured model is unavailable, disclose this; do not claim
Sol reviewed the work. Resolve a model change with the user when specialist
analysis is necessary. Routing delegates to a separate agent; it does not change
the main conversation's model.

### User-controlled phases
Follow the phase requested by the user. A request to plan or discuss permits
read-only analysis, not implementation. Accepting recommended design decisions
does not authorize code changes. Start implementation only when explicitly
instructed; a direct instruction to implement already supplies that authorization.
Update design documentation and implementation status only when requested.
Do not ask again for authorization already given for the current phase.
Plan mode must be respected: do not write files in that mode. If a requested
write is blocked by the current mode, explain the needed mode change.

### Verification and reporting
Follow the project's actual build and test instructions. Run focused tests and
necessary regression checks; address failures, including reporting unrelated
pre-existing failures. Never report unexecuted checks as passed. Report changes,
verification evidence, limitations and remaining work accurately. Do not mark
untested acceptance criteria as verified. Respect existing repository instructions
and the user's explicit scope; do not push or publish merely to record status.
<!-- END agents-hub shared routing -->
