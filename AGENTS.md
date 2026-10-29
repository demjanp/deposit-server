<!-- BEGIN agents-hub shared routing -->
# agents-hub routing

Project ID: `deposit`

Project entry (relative to this repository root): `../../../agents-hub/projects/deposit`

## Shared project workflow

### Authority and discovery
This managed section defines common policy; skills add workflow-specific steps.
Retrieve referenced policy before relying on it and include applicable rules in
isolated handoffs. Follow the project-entry pointer and linked KBs, preferring
local checkouts. Inspect current code, tests and nested instructions; respect
ownership of design/status. Do not invent missing sources or confuse plans with
implementation. Report conflicts/access gaps; ask only when they block a decision.
Search relevant sections/symbols before broad reads; retain approved decisions.

### Routing
Luna handles routine conversation, planning, implementation, tests and documentation.
Use project-task for individual work, project-stage for numbered stages and
project-run for authorized sequences. Architect (`gpt-6-astra`, medium) is read-only
and advisory; expert_implementer (`gpt-6.1-sol`, high) edits only authorized scope.
Preserve configured roles, models, reasoning and concurrency; delegation never
switches the parent model. Disclose unavailable specialists; never invent review
or silently substitute models. Resolve a necessary model change with the user.

Invoke expert_implementer only for: two consecutive evidence-driven corrections,
each tested, failing on the same underlying issue; recurring resolved regressions;
a complex defect needing deeper code reasoning; or a well-supported problem Luna
cannot reliably solve. Count corrections, not failed tests; no routine escalation.
Luna stops editing assigned files and hands over criteria, paths, constraints,
diff, failures and attempted fixes. The expert has exclusive edit ownership until
returning diagnosis/changes. Luna integrates and validates, then resumes. Failed
intervention requires diagnosis and new evidence before another invocation.

Consult architect for stage architecture and material algorithms, security,
integrity, data models, interfaces, compatibility, transaction/versioning semantics,
cross-component contracts or invariants, including substantive challenges,
alternatives and changes to prior advice. Ordinary repair failures go to expert
first; architectural contradictions go to architect through Luna. Specialists
must not spawn agents. Send phase, project/KB paths, approved decisions, question,
relevant files and failures; request advice, not implementation. Luna presents
advice to the user. Skip repeated consultation without new evidence/concerns.
Architect advice cannot approve material changes: obtain the user's decision,
continuing unaffected work. Routine details stay with Luna.

### Authorization and continuity
Planning/discussion and design acceptance authorize no writes. Explicit
implementation authorizes that phase without another approval. Update design or
status only when requested. Actual Plan mode forbids all writes, including
checkpoints/delegation; explain the needed mode change, never bypass it.
Significant architectural documentation updates require read-only architect
consistency review against approved decisions/specifications/contracts; resolve
findings. Routine documentation needs no review.

Continue authorized implementation through agreed scope and required verification.
Missing work, stubs, failed tests, absent gate tooling, complexity, elapsed time
or compaction are work to resolve. Hand back incomplete work as externally blocked
only after architect explicitly confirms, from failure evidence and attempted
remedies, that no reasonable authorized repository remedy remains. Finish unblocked
work; report approval, exact external action, remaining work and resume conditions.
Approval cannot expand scope or override instructions.

Pending user decisions, stops/scope changes, Plan mode, runtime/approval restrictions and unavailable
required specialists interrupt independently of architect approval. Obey them;
use only exposed permitted approvals, preserve recovery and continue independent
work where allowed. Never retry denied actions through an equivalent route.

### Authorized project runs
Explicit bounded execution authorizes planning, implementation, verification,
review corrections, necessary design/status updates and separate stage commits;
planning-only requests do not. Honor narrower instructions; no unrelated work,
material changes, pushes, publication or permission changes. Root coordinates
fresh Luna stage workers and specialists; workers request specialists through root.
Project-run requires fresh independent post-implementation architect review with
no blocking findings, tracker update and all stage commits before advancement.
Standalone tasks/stages retain separate phases and no automatic commits.

For a demonstrated Windows administrator requirement in a non-elevated project-run,
stop implementation and delegated edits immediately, including unaffected work.
Preserve permitted cleanup/recovery; ask for an Administrator session and resume.
Do not self-elevate, change permissions or infer elevation need from generic denial;
follow the environment reference's administrator procedure.

### Verification and reuse
Follow project build/test instructions; run focused checks and required regression/
acceptance gates. Before costly gates establish actual cwd, access, tools/runner,
reviewer availability, artifact versus acceptance-script provenance, writable
scratch/reset boundaries, retained redacted evidence and cleanup ownership.
Recheck affected execution assumptions after context changes. Isolate test state;
retain evidence before resets. Diagnose/report each failure (implementation,
fixture, harness, environment or unknown), including unrelated failures; validate
focused corrections before expensive gates. Never silently exclude failures,
relax compatibility/security checks or mark unverified criteria complete.
Inspect original process/journal state before restarting. Distinguish runtime,
ACL and handle restrictions; configuration proves neither access nor cause.
Cleanup must be attributable, permitted and verified.

Reuse context/results solely by required-file presence; no stage manifests,
cache records or content/hash/revision/environment freshness comparisons. Retrieve
missing files or rerun necessary checks; access errors mean unknown presence.
Presence is not a passing result. Report original execution references for reuse,
and distinguish executed, reused, skipped and unexecuted checks. Mandatory fresh
checks and independent reviews remain binding. Use only relevant sections of
`.agents/skills/project-run/references/verification-environment.md` for procedures.
Report changes, evidence, limits and remaining work accurately.
<!-- END agents-hub shared routing -->
