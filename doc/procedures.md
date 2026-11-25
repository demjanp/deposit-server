# Procedure reference

This reference uses optional operation IDs in Cypher. For a packaged release, use the reference bundled with your installation. In the source checkout, the public API catalog at `runtime/public-api.json` lists argument order, scopes, and result columns. The server plugin embeds a copy. Start with the [quickstart](quickstart.md) and its [sample seed script](sample-data/seed.cypher).

Examples use Cypher Shell parameters. Set token from the login result. The quickstart seed creates `Specimen` and `Context` classes and prints object IDs; assign those IDs to `$specimenId` and `$contextId` when needed. Other IDs come from the procedure that creates or returns them. Examples that use a `$name` parameter expect you to set it with Cypher Shell `:param` first. Use a disposable Namespace when trying mutations.

The server checks account status, Namespace selection, and roles on every call. Reads usually need viewer access and edits usually need editor access. Special role rules are shown below. Namespace roles are viewer, editor, and owner; server administrators have server-wide rights.

Use SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'deposit.' RETURN name ORDER BY name to inspect the installed server. Neo4j built-in procedures are outside this list.

## Tracked operations

Use one authenticated call for ordinary execution. The Server creates a tracked
attempt with a fresh UUIDv7 when the final operation ID is omitted or `null`.
Run each tracked call in its own transaction. Use `deposit.apply.batch` to group supported edits atomically under one operation ID.
Select the correct Namespace and use a current observed revision where required.
With editor access and an existing Specimen class, run:

```cypher
CALL deposit.create.classDescriptor('Specimen', 'Color', 'String', {}, $token)
YIELD node RETURN node;
```

### Explicit registered attempts

Choose an explicit ID if you need known-ID lookup after a lost response or
need to cancel before execution. Generate a fresh canonical lowercase UUIDv7
with `deposit.bat operation-id` from an extracted distribution, or
`scripts\Run_Shell.bat -OperationId` from the checkout root. Set the returned
value as the Cypher Shell parameter `operationId`. Admission uses the Server
clock. The timestamp must be less than 24 hours old, no more than five minutes
ahead, and newer than the Server's rejection watermark.

Register the exact ordered domain arguments, excluding token and operation ID.
Use `server` for Server-scoped operations and the Namespace UUID for
Namespace-scoped operations. Exchange upload steps use the upload's scope.
For Namespace deletion, register in Server scope and select the target Namespace.

```cypher
CALL deposit.action.begin($operationId, $namespaceId, 'deposit.create.classDescriptor',
  ['Specimen', 'Texture', 'String', {}], $token) YIELD data RETURN data;
```

Commit registration, then execute in a separate transaction on the same live
authenticated Bolt connection. Existing explicit execution callers must move
the token immediately before the ID.

```cypher
CALL deposit.create.classDescriptor('Specimen', 'Texture', 'String', {}, $token, $operationId)
YIELD node RETURN node;
```

Each registration authorizes at most one execution. A returned row from an
explicit transaction still requires a successful transaction commit.

### Inspection, cancellation and lost responses

On an independent authorized control connection, use the original actor,
scope and ID with `deposit.get.operation(operationId, scope, token)` or
`deposit.action.cancel(operationId, scope, token)`. A cancellation request can
race with commit. Check the returned state for confirmed rollback or commit
and check `cleanupState` for cleanup progress.

For calls that omitted the ID, `deposit.get.lastOperation(token)` may reveal the
generated ID if the session is still available and you can reliably match the
observation to the lost request. Other calls or session loss can prevent
correlation. Treat an outcome you cannot establish as unknown and avoid
repeating the mutation blindly. Known-ID lookup can confirm the outcome
even when the original result is unavailable.

The Server retains settled attempts and receipts for at least 24 hours and
longer while a dependent handle or cleanup needs them. After collection,
inspection can return `OUTCOME_UNKNOWN`. The Server rejects an attempt to
register the expired ID again.

### deposit.action.begin

Register a UUIDv7, scope, procedure and its ordered domain arguments before executing with an explicit ID.

Inputs: operationId (String), scope (String), procedure (String), arguments (List<Object>), token (String).

Returns one row with a `data` map.

### deposit.action.cancel

Request cancellation of the actor's operation using its original ID and scope. Use a separate connection for an in-flight operation.

Inputs: operationId (String), scope (String), token (String).

Returns one row with a `data` map.

## Administration

### deposit.admin.pruneResources
Purpose: Preview resource collection or run an actual prune.
Inputs: options (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Server administrator.
Example:

```cypher
CALL deposit.admin.pruneResources({}, $token) YIELD data RETURN data;
```

The default dry run creates no attempt and requires an omitted or null ID.
Set `dryRun: false` to run a tracked prune. For explicit tracking, use
`server` for an all-server prune or the target Namespace UUID for a
Namespace prune. The returned `pruneId` identifies the job for
`deposit.admin.pruneResourcesStatus`.

### deposit.admin.pruneResourcesStatus
Purpose: Run or inspect a server administration task.
Inputs: pruneId (String), token (String).
Access: Server administrator.
Example:

```cypher
CALL deposit.admin.pruneResourcesStatus($pruneId, $token) YIELD data RETURN data;
```

## Create

### deposit.apply.batch

Inputs: commands (List<Object>), token (String), operationId (optional UUIDv7 string, default null).
Purpose: Run supported edit procedures as one action. Each nested procedure still checks its own role and inputs.
Each command supplies a `key`, a `procedure` and an `arguments` list.
A reference of the form `{ref: 'key', field: 'id'}` passes an earlier result
ID to a later command. Descriptor writes can supply a `descriptorTypes` map
keyed by descriptor name. The Server checks each explicit type against the
declaration in effect when its command runs.
Access: Namespace editor.
Example:

```cypher
CALL deposit.apply.batch([
  {key: 'class', procedure: 'deposit.create.class', arguments: ['BatchExample', {descriptors: [{name: 'Code', type: 'String'}]}]}
], $token) YIELD data RETURN data;
```

The complete sample graph uses this procedure in [seed.cypher](sample-data/seed.cypher).

### deposit.create.class
Purpose: Create a Class and its declared Descriptors.
Inputs: name (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.class('Site', {descriptors: [{name: 'Mass', type: 'Float'}]}, $token) YIELD node RETURN node;
```

### deposit.create.classDescriptor
Purpose: Add a Descriptor declaration to a Class.
Inputs: class (Object), name (String), type (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.classDescriptor('Specimen', 'Color', 'String', {}, $token) YIELD node RETURN node;
```

### deposit.create.classRelationship
Purpose: Declare a relationship allowed between two Classes.
Inputs: source (Object), target (Object), label (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.classRelationship('Specimen', 'Context', 'foundIn', {}, $token) YIELD rel RETURN rel;
```

### deposit.create.descriptor
Purpose: Add a Descriptor value to an Object.
Inputs: objectId (String), name (String), value (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.descriptor($specimenId, 'Mass', 15.0, $token) YIELD node RETURN node;
```

### deposit.create.namespace
Purpose: Create a Namespace. Its creator becomes the owner.
Inputs: name (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Any enabled Deposit user; creator becomes owner.
Example:

```cypher
CALL deposit.create.namespace('Example', {historyMode: 'disabled'}, $token) YIELD node RETURN node;
```

### deposit.create.object
Purpose: Create an Object in the active Namespace.
Inputs: class (Object), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.object('Specimen', {descriptors: {Code: 'S-04', Mass: 5.0}}, $token) YIELD node RETURN node;
```

### deposit.create.pattern
Purpose: Create a reusable Pattern for matching or importing data.
Inputs: name (String), definition (Map<String,Object>), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.pattern('Specimen form', {objects: {specimen: {className: 'Specimen', root: true, alwaysCreate: false, matchEmpty: false, descriptors: [{name: 'Code'}], relationships: [{label: 'foundIn', targetName: 'context'}]}, context: {className: 'Context', root: false, descriptors: [{name: 'Code'}], relationships: []}}}, {}, $token) YIELD node RETURN node;
```

A Pattern definition contains an `objects` map keyed by role name, such as `specimen` or `context`. Each role describes an Object in the Pattern. Use the same role names in the `values` map when matching Objects or importing form data.

| Role field | Meaning |
| --- | --- |
| `className` | Required Class name. |
| `root` | Marks the root role. Each connected group of roles must have exactly one root, including a group with just one role. Defaults to `false`. |
| `alwaysCreate` | Create a new Object for this role during import. Defaults to `false`. |
| `matchEmpty` | Include empty values in matching for this role. Defaults to `false`. |
| `comment` | Optional text. |
| `descriptors` | List of Descriptor maps. Each requires `name` and accepts `type` and `comment`. The type, if supplied, must agree with the Class declaration. |
| `relationships` | List of outgoing relationship maps. Each requires `label` and `targetName`, and accepts `comment`. `targetName` names another role in the `objects` map. |

The two lists default to empty. Descriptor names must be unique within a role, and relationship entries must have distinct label and target pairs.

### deposit.create.relationship
Purpose: Connect two Objects with a relationship.
Inputs: sourceId (String), targetId (String), label (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.relationship($specimenId, $contextId, $label, {weight: 1.0}, $token) YIELD rel RETURN rel;
```

### deposit.create.user
Purpose: Create a Deposit user account.
Inputs: username (String), password (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Server administrator.
Example:

```cypher
CALL deposit.create.user('demo-user', $password, {}, $token) YIELD data RETURN data;
```

### deposit.create.userTool
Purpose: Save a reusable text tool in the active Namespace.
Inputs: name (String), properties (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.userTool('Example', {data: 'Example text'}, $token) YIELD node RETURN node;
```

## Delete

### deposit.delete.all
Purpose: Clear Objects, Classes, Patterns and User Tools from the active Namespace; keep the Namespace itself.
Inputs: confirmNamespaceName (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.delete.all('Deposit sample', $token) YIELD data RETURN data;
```

### deposit.delete.class
Purpose: Delete an unused Class.
Inputs: class (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.class('UnusedExampleClass', $token) YIELD data RETURN data;
```

This is destructive. It only succeeds if the class exists and has no dependent data. Do not use it on the quickstart classes.

### deposit.delete.classDescriptor
Purpose: Remove a Descriptor declaration from a Class.
Inputs: classDescriptor (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.classDescriptor($classDescriptor, $token) YIELD data RETURN data;
```

### deposit.delete.classRelationship
Purpose: Remove a declared relationship between Classes.
Inputs: classRelationshipId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.classRelationship($classRelationshipId, $token) YIELD data RETURN data;
```

### deposit.delete.descriptor
Purpose: Remove a Descriptor value from an Object.
Inputs: descriptor (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.descriptor($descriptor, $token) YIELD data RETURN data;
```

### deposit.delete.namespace
Purpose: Delete the selected Namespace.
Inputs: confirmNamespaceName (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.delete.namespace('Deposit sample', $token) YIELD data RETURN data;
```

### deposit.delete.object
Purpose: Delete an Object and its attached data.
Inputs: objectId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.object($specimenId, $token) YIELD data RETURN data;
```

### deposit.delete.pattern
Purpose: Delete a saved Pattern.
Inputs: pattern (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.pattern({id: $patternId}, $token) YIELD data RETURN data;
```

### deposit.delete.relationship
Purpose: Delete a relationship between Objects.
Inputs: relationshipId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.relationship($relationshipId, $token) YIELD data RETURN data;
```

### deposit.delete.userTool
Purpose: Delete a saved text tool.
Inputs: userTool (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.userTool($userTool, $token) YIELD data RETURN data;
```

## Read and query

### deposit.get.info
Purpose: Return the running Deposit version. No Deposit login is required.
Inputs: None.
Access: No Deposit token required.
Example:

```cypher
CALL deposit.get.info() YIELD data RETURN data;
```

### deposit.get.activeNamespace
Purpose: Return the Namespace selected for this session.
Inputs: token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.get.activeNamespace($token) YIELD node RETURN node;
```

### deposit.get.administrativeMode
Purpose: Show whether administrative mode is enabled for this session.
Inputs: token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.get.administrativeMode($token) YIELD value RETURN value;
```

### deposit.get.class
Purpose: Read Class.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.class('Specimen', $token) YIELD node RETURN node;
```

### deposit.get.classDescriptor
Purpose: Read Class Descriptor.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classDescriptor(null, $token) YIELD node RETURN node;
```

### deposit.get.classDescriptorPage
Purpose: Read Class Descriptor page.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classDescriptorPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.classMembers
Purpose: Read Objects belonging to a Class.
Inputs: class (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classMembers('Specimen', $token) YIELD node RETURN node;
```

### deposit.get.classMembersPage
Purpose: Read Page of Objects belonging to a Class.
Inputs: class (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classMembersPage('Specimen', {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.classPage
Purpose: Read Class page.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.classRelationship
Purpose: Read Class relationship declarations.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classRelationship(null, $token) YIELD rel RETURN rel;
```

### deposit.get.classRelationshipPage
Purpose: Read Page of Class relationship declarations.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.classRelationshipPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.data
Purpose: Export a selected subgraph as a portable package.
Inputs: rootIds (List<String>), options (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace viewer for a subgraph; Namespace owner for a full export when history mode is persistent. An empty rootIds list selects the full Namespace.
Example:

```cypher
CALL deposit.get.data([$specimenId], {maxHops: 1}, $token) YIELD data RETURN data;
```

### deposit.get.descriptor
Purpose: Read Descriptor values.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.descriptor(null, $token) YIELD node RETURN node;
```

### deposit.get.descriptorPage
Purpose: Read Descriptor value page.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.descriptorPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.history
Purpose: Read Namespace history.
Inputs: token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.history($token) YIELD data RETURN data;
```

### deposit.get.historyPage
Purpose: Read Namespace history page.
Inputs: page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.historyPage({pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.importPlan
Purpose: Read Staged import plan.
Inputs: planId (String), token (String).
Access: Plan creator or Namespace owner; viewer access to the Namespace is also required.
Example:

```cypher
CALL deposit.get.importPlan($planId, $token) YIELD data RETURN data;
```

### deposit.get.importPlanMetadata

Read metadata for an import plan without including its larger collections.

Inputs: planId (String), token (String).

Returns one row with a `data` map.

### deposit.get.importResult

Read the summary and collection metadata for an import result.

Inputs: resultId (String), token (String).

Returns one row with a `data` map.

### deposit.get.importResultPage

Read one collection page using the result ID and lease. Pass the returned cursor for the next page.

Inputs: resultId (String), leaseId (String), collection (String), cursor (Map<String,Object>), limit (Long), token (String).

Returns one row with a `data` map.

### deposit.get.importResultEntry

Look up one collection entry by key using the result ID and lease.

Inputs: resultId (String), leaseId (String), collection (String), key (String), token (String).

Returns one row with a `data` map.

### deposit.get.lastOperation
Purpose: Inspect the most recent tracked attempt claimed by this session, including its `operationId`, `scope`, and `state`. After a lost response, use the observation only if you can reliably match it to the original request.
Inputs: token (String).
Access: Authenticated Deposit session; this session’s actor only.
Example:

```cypher
CALL deposit.get.lastOperation($token) YIELD data RETURN data;
```

### deposit.get.layout
Purpose: Read Object or Class layout positions.
Inputs: selection (Map<String,Object>), options (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.layout(null, {}, $token) YIELD data RETURN data;
```

### deposit.get.namespace
Purpose: Find Namespaces available to this user.
Inputs: selection (Object), token (String).
Access: Authenticated Deposit user; only Namespaces available to the user are returned.
Example:

```cypher
CALL deposit.get.namespace(null, $token) YIELD node RETURN node;
```

### deposit.get.namespaceData
Purpose: Export the active Namespace as a portable package.
Inputs: options (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace viewer for subgraph or transient/disabled full export; Namespace owner for full export with persistent history.
Example:

```cypher
CALL deposit.get.namespaceData({}, $token) YIELD data RETURN data;
```

### deposit.get.namespacePage
Purpose: Read a page of Namespaces available to this user.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Authenticated Deposit user; only Namespaces available to the user are returned.
Example:

```cypher
CALL deposit.get.namespacePage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.namespaceRoles
Purpose: Read Namespace role assignments.
Inputs: token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.get.namespaceRoles($token) YIELD data RETURN data;
```

### deposit.get.namespaceRolesPage
Purpose: Read Page of Namespace role assignments.
Inputs: page (Map<String,Object>), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.get.namespaceRolesPage({pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.object
Purpose: Read Objects.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.object(null, $token) YIELD node RETURN node;
```

### deposit.get.objectByDescriptorId
Purpose: Read Object that owns a Descriptor.
Inputs: descriptorId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.objectByDescriptorId($descriptorId, $token) YIELD node RETURN node;
```

### deposit.get.objectClass
Purpose: Read Class membership for an Object.
Inputs: objectId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.objectClass($specimenId, $token) YIELD node RETURN node;
```

### deposit.get.objectPage
Purpose: Read Object page.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.objectPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.objectsByPattern
Purpose: Read Objects matching a Pattern.
Inputs: pattern (Object), values (Map<String,Object>), rules (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.objectsByPattern({id: $patternId}, {specimen: {Code: 'S-04'}}, {}, $token) YIELD data RETURN data;
```

### deposit.get.objectsByPatternPage
Purpose: Read Page of Objects matching a Pattern.
Inputs: pattern (Object), values (Map<String,Object>), rules (Map<String,Object>), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.objectsByPatternPage({id: $patternId}, {specimen: {Code: 'S-04'}}, {}, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.operation


Inputs: operationId (String), scope (String), token (String).
Read the authoritative operation state and cleanup state for the original
actor and explicit Scope. The Server checks current account and Scope access.

```cypher
CALL deposit.get.operation($operationId, $namespaceId, $token) YIELD data RETURN data;
```

### deposit.get.pattern
Purpose: Read Patterns.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.pattern(null, $token) YIELD node RETURN node;
```

### deposit.get.patternPage
Purpose: Read Pattern page.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.patternPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.query
Purpose: Run a Deposit Query Language query and stream its rows.
Inputs: query (String), parameters (Map<String,Object>), options (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.query('SELECT Specimen.Code AS code ORDER BY code', {}, {}, $token) YIELD data, columns, presence RETURN data, columns, presence;
```

### deposit.get.queryPage
Purpose: Run a Deposit Query Language query and return one page.
Inputs: query (String), parameters (Map<String,Object>), options (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.queryPage('SELECT Specimen.Code AS code ORDER BY code', {}, {}, $token) YIELD data RETURN data;
```

### deposit.get.relationship
Purpose: Read Relationships between Objects.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.relationship(null, $token) YIELD rel RETURN rel;
```

### deposit.get.relationshipPage
Purpose: Read Page of relationships between Objects.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.relationshipPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.requests
Purpose: List active or recent requests visible to this user.
Inputs: token (String).
Access: Own requests; server administrator can inspect requests as allowed by server rules.
Example:

```cypher
CALL deposit.get.requests($token) YIELD data RETURN data;
```

### deposit.get.restorePreview
Purpose: Read Preview of restoring a prior revision.
Inputs: targetRevisionId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.get.restorePreview($revisionId, $token) YIELD data RETURN data;
```

### deposit.get.revision
Purpose: Read Current Namespace revision and history mode.
Inputs: token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.revision($token) YIELD data RETURN data;
```

### deposit.get.session
Purpose: Show this Deposit session.
Inputs: token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.get.session($token) YIELD data RETURN data;
```

### deposit.get.table
Purpose: Read Saved table data.
Inputs: tableId (String), token (String).
Access: Table creator or Namespace owner; viewer access to the Namespace is also required.
Example:

```cypher
CALL deposit.get.table($tableId, $token) YIELD data, columns, presence RETURN data, columns, presence;
```

### deposit.get.tablePage
Purpose: Read Page of saved table data.
Inputs: tableId (String), page (Map<String,Object>), token (String).
Access: Table creator or Namespace owner; viewer access to the Namespace is also required.
Example:

```cypher
CALL deposit.get.tablePage($tableId, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.user
Purpose: Read this user profile, or user profiles as an administrator.
Inputs: selection (Object), token (String).
Access: Own profile; server administrator can read other users.
Example:

```cypher
CALL deposit.get.user(null, $token) YIELD data RETURN data;
```

### deposit.get.userPage
Purpose: Read Page of user profiles; server administrator only.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Server administrator.
Example:

```cypher
CALL deposit.get.userPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

### deposit.get.userTool
Purpose: Read Saved User Tools.
Inputs: selection (Object), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.userTool(null, $token) YIELD node RETURN node;
```

### deposit.get.userToolPage
Purpose: Read Page of saved User Tools.
Inputs: selection (Object), page (Map<String,Object>), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.get.userToolPage(null, {pageSize: 20}, $token) YIELD data RETURN data;
```

## Import

Analysis checks the import before applying graph changes. A successful analysis without conflicts supplies a `planId`. Use `deposit.get.importPlanMetadata` to inspect its state and `deposit.get.importPlan` for the analysis details. If there are conflicts, inspect the conflict results and resolve them before analyzing again. When a response supplies a `resultId`, use `deposit.get.importResult` for collection metadata and a lease, then `deposit.get.importResultPage` or `deposit.get.importResultEntry` to read the details. Apply a ready plan on the connection that created it.

`deposit.import.analyze`, `deposit.import.rows`, and `deposit.import.dataByPattern` accept these rules:

| Rule | Meaning |
| --- | --- |
| `alwaysCreate` | A list of distinct Class names whose incoming Objects should be created instead of matched to existing Objects. |
| `matchEmpty` | A Boolean, defaulting to `false`. When enabled, empty Descriptor values can participate in matching. Applicable Pattern roles control matching through their own `matchEmpty` setting. Blank tabular cells remain excluded from matching. |
| `rootId` | For `deposit.import.dataByPattern` only. Bind the Pattern's root to an existing Object UUID in the active Namespace. The Pattern must have one root connected to every role, and the root must belong to the expected Class and allow reuse. |

### deposit.import.analyze


Inputs: source (Map<String,Object>), rules (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Analyze a canonical Exchange package without changing the receiving Namespace.
The active Namespace requires editor access. Obtain `packageId` and `grant`
from `deposit.exchange.export` in the source Namespace or from
`deposit.exchange.finishUpload` after intake in the receiving Scope. A full
export with persistent history requires source owner access. Run analysis in the receiving Namespace.

```cypher
CALL deposit.import.analyze({packageId: $packageId, grant: $grant}, {}, $token)
  YIELD data RETURN data;
```

### deposit.import.apply


Inputs: planId (String), token (String), operationId (optional UUIDv7 string, default null).
Apply the ready plan in the same receiving Store session and on
the same connection that created it. The Server checks the plan's target
revision and applies it in one transaction.

```cypher
CALL deposit.import.apply($planId, $token) YIELD data RETURN data;
```

### deposit.import.discardPlan

Discard an unused import plan in the active Namespace.

Inputs: planId (String), token (String).

Returns one row with a `data` map.

### deposit.import.dataByPattern


Inputs: pattern (Object), values (Map<String,Object>), rules (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Analyze form input against a stored Pattern in the active Namespace.

```cypher
CALL deposit.import.dataByPattern({id: $patternId}, {specimen: {Code: 'S-04'}}, {},
  $token) YIELD data RETURN data;
```

### deposit.import.cloneNamespace


Inputs: source (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Clone a complete Exchange package into a new Namespace in one transaction.
This operation uses Server scope. Use an authorized source-export grant
or a Server-scoped receiving-upload grant. The authenticated actor owns the
new Namespace.

```cypher
CALL deposit.import.cloneNamespace({packageId: $packageId, grant: $grant}, $token)
  YIELD data RETURN data;
```

### deposit.import.rows
Purpose: Match source rows to Objects and stage an import plan.
Inputs: source (Map<String,Object>), targets (Map<String,Object>), relationships (List<Object>), rules (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.import.rows({rows: [{id: 'row-1', values: {Code: 'S-04', Mass: 5.0}}]}, {Code: {object: 'specimen', className: 'Specimen', descriptor: 'Code', type: 'String'}, Mass: {object: 'specimen', className: 'Specimen', descriptor: 'Mass', type: 'Float'}}, [], {}, $token) YIELD data RETURN data;
```

To import a staged table, use `{tableId: $tableId}` as the source and key `targets` by the table's column names. See [Load](#load) for staging and previewing the table.

## Load

Upload the input file as a [resource](#resources), then pass its `resourceId` to the loader. The response includes `tableId`, columns, row count, validity, and diagnostics. Preview the rows with `deposit.get.tablePage`. If `valid` is `false`, inspect the diagnostics and load again with corrected data or options. Use a valid `tableId` with `deposit.import.rows` or a [save procedure](#save).

| Load option | Formats | Meaning |
| --- | --- | --- |
| `delimiter` | CSV | Single-character field separator. Defaults to a comma. |
| `headerRow` | CSV, XLSX | One-based header row. Defaults to `1`. |
| `sheet` | XLSX | Worksheet name. Defaults to the first worksheet. |
| `layer` | GeoPackage | Required layer name. |
| `geometryColumn` | GeoPackage | Required for feature layers and must match the registered geometry column. Omit for attribute layers. |
| `columnTypes` | All | Map from column names to Deposit value types. Ordinary columns default to `String`. The registered GeoPackage geometry column uses `DGeometry`. |
| `dateFormats` | All | Map from column names to Java date/time patterns for columns declared with a date or time type. |
| `decimalSeparator` | All | `.` (default) or `,` for numeric text conversion. |

### deposit.load.csv
Purpose: Stage a CSV resource as a table.
Inputs: resourceId (String), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example for a CSV with columns named `Code` and `Mass`. Set `$tableId` from the first result before running the preview.

```cypher
CALL deposit.load.csv($resourceId, {columnTypes: {Code: 'String', Mass: 'Float'}}, $token)
YIELD data RETURN data;

CALL deposit.get.tablePage($tableId, {pageSize: 20}, $token)
YIELD data RETURN data;
```

### deposit.load.gpkg
Purpose: Stage a GeoPackage layer as a table.
Inputs: resourceId (String), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example for a feature layer named `specimens` with a registered geometry column named `geom`:

```cypher
CALL deposit.load.gpkg($resourceId, {layer: 'specimens', geometryColumn: 'geom'}, $token) YIELD data RETURN data;
```

### deposit.load.xlsx
Purpose: Stage an Excel worksheet as a table.
Inputs: resourceId (String), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.load.xlsx($resourceId, {}, $token) YIELD data RETURN data;
```

## Resources

### deposit.resource.abortUpload
Purpose: Cancel an unfinished resource upload.
Inputs: uploadId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.abortUpload($uploadId, $token) YIELD data RETURN data;
```

### deposit.resource.beginUpload
Purpose: Start a chunked resource upload.
Inputs: filename (String), bytes (Long), sha256 (String), mediaType (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.beginUpload('hello.txt', 5, '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824', 'text/plain', $token) YIELD data RETURN data;
```

### deposit.resource.downloadChunk
Purpose: Read a byte range from a resource you are allowed to access.
Inputs: resourceId (String), offset (Long), length (Long), token (String).
Access: Current resource permission: reference, upload creator, or valid export grant.
Example:

```cypher
CALL deposit.resource.downloadChunk($resourceId, 0, 5, $token) YIELD data RETURN data;
```

### deposit.resource.finishUpload
Purpose: Verify and finish a resource upload.
Inputs: uploadId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.finishUpload($uploadId, $token) YIELD data RETURN data;
```

### deposit.resource.info
Purpose: Read metadata for a resource you are allowed to access.
Inputs: resourceId (String), token (String).
Access: Current resource permission: reference, upload creator, or valid export grant.
Example:

```cypher
CALL deposit.resource.info($resourceId, $token) YIELD data RETURN data;
```

### deposit.resource.uploadChunk
Purpose: Send one verified chunk of an unfinished resource upload.
Inputs: uploadId (String), offset (Long), bytesBase64 (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.uploadChunk($uploadId, 0, 'aGVsbG8=', $token) YIELD data RETURN data;
```

For a small upload, call `beginUpload` with the filename, byte count, and whole-file SHA-256. Send each chunk in order with its byte offset and Base64 bytes, then call `finishUpload`. The calls above use the text `hello` (5 bytes). Use `abortUpload` to discard an unfinished transfer. To read a resource, call `info`, then request successive `downloadChunk` ranges until the reported byte count is reached.

To open an attachment link from an exported table in a browser, see [Download attachments in a browser](#download-attachments-in-a-browser).

## Save

Pass exactly one source, `{query: 'SELECT ...'}` or `{tableId: $tableId}`. Use the `parameters` argument for values referenced by a query, or `{}` for a staged table. Select scalar Descriptors when exporting a query. The result includes `resourceId`, filename, byte count, checksum, and any export warnings. Use `deposit.resource.downloadChunk` with the returned `resourceId` to retrieve the file.

| Format | `formatOptions` |
| --- | --- |
| CSV | `filename` defaults to `export.csv`. `delimiter` defaults to a comma. |
| XLSX | `filename` defaults to `export.xlsx`. `sheet` defaults to `Data`. |
| GeoPackage | `filename` defaults to `export.gpkg`. `layer` defaults to `data`. An export that produces multiple GeoPackages returns a ZIP. |

### Download attachments in a browser

When an exported table contains file attachments (`DResource` values), the export includes links to those files. Open a link in your browser. If prompted, sign in with your Deposit username and password. Deposit returns you to the requested file and checks your current access to that resource in its Namespace.

The links use `download.publicBaseUrl`. A loopback URL works on the server machine. For remote users, configure a reachable HTTPS URL before exporting. See [Installation and configuration](installation.md#configuration).

To manage the browser session, open the configured base URL with `/login` appended. Choose **Log out** to end the session. Deposit administrators can also enable or disable administrative mode for that browser session on the same page.

### deposit.save.csv
Purpose: Export query or table results as CSV.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.csv({query: 'SELECT Specimen.Code AS code'}, {}, {}, $token) YIELD data RETURN data;
```

### deposit.save.gpkg
Purpose: Export query or table results as a GeoPackage.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.gpkg({query: 'SELECT Specimen.Code AS code'}, {}, {}, $token) YIELD data RETURN data;
```

### deposit.save.xlsx
Purpose: Export query or table results as an Excel workbook.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.xlsx({query: 'SELECT Specimen.Code AS code'}, {}, {}, $token) YIELD data RETURN data;
```

## Update and selection

### deposit.set.activeNamespace
Purpose: Select the active Namespace for this session.
Inputs: namespace (Object), token (String).
Access: Authenticated Deposit session; the selected Namespace must be available to the user.
Example:

```cypher
CALL deposit.set.activeNamespace($namespace, $token) YIELD node RETURN node;
```

### deposit.set.administrativeMode
Purpose: Enable or disable administrative mode for this session.
Inputs: enabled (Boolean), token (String).
Access: Server administrator to enable; any authenticated user can disable their own mode.
Example:

```cypher
CALL deposit.set.administrativeMode(false, $token) YIELD value RETURN value;
```

### deposit.set.class
Purpose: Update a Class.
Inputs: class (Object), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.class('Specimen', {comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.classDescriptor
Purpose: Update a Class Descriptor declaration.
Inputs: classDescriptor (Object), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.classDescriptor($classDescriptor, {comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.classRelationship
Purpose: Update a declared Class relationship.
Inputs: classRelationshipId (String), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.classRelationship($classRelationshipId, {comment: 'Example update'}, $token) YIELD rel RETURN rel;
```

### deposit.set.descriptor
Purpose: Change a Descriptor value on an Object.
Inputs: objectId (String), name (String), value (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.descriptor($specimenId, 'Mass', 15.0, $token) YIELD node RETURN node;
```

### deposit.set.membership
Purpose: Move an Object to a Class.
Inputs: objectId (String), class (Object), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.membership($specimenId, 'Specimen', $token) YIELD rel RETURN rel;
```

### deposit.set.namespace
Purpose: Update Namespace settings.
Inputs: patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.namespace({comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.namespaceRole
Purpose: Change a user’s role in the Namespace.
Inputs: user (Object), role (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.namespaceRole('demo-user', 'viewer', $token) YIELD data RETURN data;
```

### deposit.set.object
Purpose: Update an Object.
Inputs: objectId (String), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.object($specimenId, {comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.password
Purpose: Change the password for the current Deposit user.
Inputs: oldPassword (String), newPassword (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Authenticated Deposit session; changes the current user’s password.
Example:

```cypher
CALL deposit.set.password($oldPassword, $newPassword, $token) YIELD value RETURN value;
```

### deposit.set.pattern
Purpose: Update a Pattern definition or comment.
Inputs: pattern (Object), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.pattern({id: $patternId}, {comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.relationship
Purpose: Update a relationship between Objects.
Inputs: relationshipId (String), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.relationship($relationshipId, {comment: 'Example update'}, $token) YIELD rel RETURN rel;
```

### deposit.set.user
Purpose: Update a Deposit user account.
Inputs: user (Object), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Server administrator.
Example:

```cypher
CALL deposit.set.user('demo-user', {comment: 'Example update'}, $token) YIELD data RETURN data;
```

### deposit.set.userTool
Purpose: Update a saved text tool.
Inputs: userTool (Object), patch (Map<String,Object>), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.userTool($userTool, {comment: 'Example update'}, $token) YIELD node RETURN node;
```

### deposit.set.versioningType
Purpose: Change the Namespace history mode.
Inputs: mode (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.versioningType('persistent', $token) YIELD data RETURN data;
```

### deposit.set.versionTag
Purpose: Add or update a tag on a revision.
Inputs: revisionId (String), tag (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.versionTag($revisionId, 'example', $token) YIELD data RETURN data;
```

## Utilities and accounts

### deposit.utils.bootstrap
Purpose: Create the first Deposit administrator.
Inputs: username (String), password (String).
Access: Requires an authenticated Neo4j caller authorized to execute administrative procedures; no Deposit token. The server must be uninitialized. On Neo4j Community, every authenticated native account has administrator privileges and can bootstrap a fresh installation.
Example:

```cypher
CALL deposit.utils.bootstrap('demo-user', $password) YIELD data RETURN data;
```

### deposit.utils.cancel
Purpose: Request cancellation of active requests using the current session token.
Inputs: token (String).
Access: Authenticated Deposit session; applies to requests using the same token.
Example:

```cypher
CALL deposit.utils.cancel($token) YIELD value RETURN value;
```

### deposit.utils.cancelRequest
Purpose: Request cancellation of one authorized active request.
Inputs: requestId (String), token (String).
Access: The request actor for their own active request, or a server administrator.
Example:

```cypher
CALL deposit.utils.cancelRequest($requestId, $token) YIELD value RETURN value;
```

### deposit.utils.canUndo
Purpose: Check whether an action can be undone.
Inputs: revisionId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.utils.canUndo($revisionId, $token) YIELD value, data RETURN value, data;
```

### deposit.utils.coordinatesToWKT
Purpose: Convert coordinates to Well-Known Text geometry.
Inputs: type (String), coordinates (Object), options (Map<String,Object>), token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.utils.coordinatesToWKT('Point', [17.1, 48.1], {}, $token) YIELD data RETURN data;
```

### deposit.utils.isRestored
Purpose: Check whether the active Namespace has been restored.
Inputs: token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.utils.isRestored($token) YIELD value RETURN value;
```

### deposit.utils.login
Purpose: Authenticate a Deposit user and return a session token.
Inputs: username (String), password (String).
Access: No Deposit token; valid username and password required.
Example:

```cypher
CALL deposit.utils.login('demo-user', $password) YIELD value RETURN value;
```

### deposit.utils.logout
Purpose: End the current Deposit session.
Inputs: token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.utils.logout($token) YIELD value RETURN value;
```

### deposit.utils.redo
Purpose: Reapply the effects of an eligible undo by appending a new revision.
Inputs: undoRevisionId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Action actor as editor; Namespace owner for another actor's action, subject to the history rules.
Example:

```cypher
CALL deposit.utils.redo($undoRevisionId, $token) YIELD data RETURN data;
```

### deposit.utils.refresh
Purpose: Refresh session state after server or Namespace changes.
Inputs: scope (String), token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.utils.refresh('namespace', $token) YIELD data RETURN data;
```

### deposit.utils.restore
Purpose: Restore the whole Namespace to a selected revision.
Inputs: targetRevisionId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Namespace owner.
Example:

```cypher
CALL deposit.utils.restore($revisionId, $token) YIELD data RETURN data;
```

### deposit.utils.toData
Purpose: Convert a native Neo4j entity handle to portable Deposit data.
Inputs: entity (Object), token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.utils.toData($entity, $token) YIELD data RETURN data;
```

### deposit.utils.undo
Purpose: Reverse an eligible action by appending a new revision.
Inputs: revisionId (String), token (String), operationId (optional UUIDv7 string, default null).
Access: Action actor as editor; owner for another actor's action.
Example:

```cypher
CALL deposit.utils.undo($revisionId, $token) YIELD data RETURN data;
```

### deposit.utils.wktToCoordinates
Purpose: Convert Well-Known Text geometry to coordinates.
Inputs: wkt (String), options (Map<String,Object>), token (String).
Access: Authenticated Deposit session.
Example:

```cypher
CALL deposit.utils.wktToCoordinates('POINT (17.1 48.1)', {}, $token) YIELD data RETURN data;
```

## Exchange transfer

Each procedure returns a `data` map. Export and each upload step have their own tracked attempt.

| Procedure | Arguments in order | Use |
| --- | --- | --- |
| `deposit.exchange.export` | `rootIds, options, token, operationId = null` | Stage an exchange package for transfer. |
| `deposit.exchange.beginUpload` | `scope, inputKind, metadata, token, operationId = null` | Open an upload for an exchange package. |
| `deposit.exchange.uploadChunk` | `uploadId, member, offset, bytesBase64, token, operationId = null` | Upload a member's Base64-encoded bytes at the given offset. |
| `deposit.exchange.finishUpload` | `uploadId, token, operationId = null` | Finalize the upload and obtain the staged package. |
| `deposit.exchange.abortUpload` | `uploadId, token, operationId = null` | Abort the upload. |
| `deposit.exchange.inspect` | `packageId, grant, token` | Read package metadata and obtain a lease. |
| `deposit.exchange.memberPage` | `packageId, grant, leaseId, cursor, limit, token` | Page through package members. |
| `deposit.exchange.graphPage` | `packageId, grant, leaseId, collection, cursor, limit, token` | Page through a graph collection. |
| `deposit.exchange.readChunk` | `packageId, grant, leaseId, member, offset, length, token` | Read a byte range from a member. |
| `deposit.exchange.discard` | `packageId, grant, token` | Discard a staged package. |

### Export and upload options

Use `null` for `rootIds` to export the active Namespace, or a nonempty list of Object UUIDs for a subgraph. For a subgraph, `maxHops` ranges from `0` to `10` and defaults to `3`. `rootClassBoundary` defaults to `true` and prevents traversal across edges between two non-root Objects of the same Class as one of the roots. Whole-Namespace Exchange exports include persistent history when available and require owner access in that case.

Use the receiving Namespace UUID as the upload `scope` for an import into that Namespace. Select it before uploading and use a session with editor access. Use `server` for an upload intended for `deposit.import.cloneNamespace`.

| `inputKind` | `metadata` | Upload members |
| --- | --- | --- |
| `zip` | `{}` | `package` contains the complete ZIP. |
| `snapshot` | `{includeHistory: true}` or `{includeHistory: false}` | `manifest` and `graph`, plus `history` when included and `resources/<sha256>` for the resource contents. Use lowercase SHA-256 hex for each resource member name. |

### Transfer a ZIP and import into an existing Namespace

Run each tracked call in its own transaction. In Cypher Shell, set the parameters for each step from the preceding results using `:param`. With the source Namespace active, export it and use the returned `packageId` and `grant` to obtain a read lease:

```cypher
CALL deposit.exchange.export(null, {}, $sourceToken) YIELD data
RETURN data.packageId, data.grant, data.bytes, data.sha256;

CALL deposit.exchange.inspect($sourcePackageId, $sourceGrant, $sourceToken) YIELD data
RETURN data.leaseId;
```

On the receiving server, log in, select the receiving Namespace, and open a ZIP upload. Keep this receiving connection for the upload and subsequent import.

```cypher
CALL deposit.exchange.beginUpload($receivingNamespaceId, 'zip', {}, $receivingToken) YIELD data
RETURN data.uploadId;
```

Start `$offset` at `0`. Repeat the two calls below in their respective connections until the upload's `nextOffset` reaches the exported byte count. Pass each returned `bytesBase64` string directly as `$chunk` and use `nextOffset` as the next `$offset`. The example requests 64 KiB per read, and the final chunk may be shorter.

```cypher
CALL deposit.exchange.readChunk($sourcePackageId, $sourceGrant, $leaseId, 'package', $offset, 65536, $sourceToken)
YIELD data RETURN data.bytesBase64;
```

```cypher
CALL deposit.exchange.uploadChunk($uploadId, 'package', $offset, $chunk, $receivingToken)
YIELD data RETURN data.nextOffset;
```

If the read lease expires while the source package is still available, call `inspect` again for a fresh lease and resume at the last confirmed offset. Use `abortUpload` to discard an unfinished upload.

On the receiving connection, finish the upload and use the receiving `packageId` and `grant` to analyze the import. Inspect the analysis and apply the ready `planId` on that connection. The plan is bound to the receiving Namespace revision, so analyze again if the revision has changed.

```cypher
CALL deposit.exchange.finishUpload($uploadId, $receivingToken) YIELD data
RETURN data.packageId, data.grant;

CALL deposit.import.analyze({packageId: $receivedPackageId, grant: $receivedGrant}, {}, $receivingToken)
YIELD data RETURN data;

CALL deposit.import.apply($planId, $receivingToken) YIELD data RETURN data;
```

For a clone into a new Namespace, finish a `server`-scoped upload and pass the returned `packageId` and `grant` to `deposit.import.cloneNamespace`. Cloning requires a whole-Namespace package whose Namespace name is available on the receiving server. The actor becomes its owner.

## Result notes

`node` and `rel` are native Neo4j handles. Use `deposit.utils.toData(entity, token)` to convert an entity to portable Deposit data. Use `value` for a scalar result and `data` for a structured result. Query streams expose `data`, `columns`, and `presence` as separate result columns. Page procedures return their page inside `data`.




