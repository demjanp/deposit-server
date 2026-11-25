# Procedure reference

The [2.0.1 API catalog](https://github.com/demjanp/deposit-server/blob/2.0.1/runtime/public-api.json) lists argument order, scopes, and result columns. The server plugin embeds a copy. Start with the [quickstart](quickstart.md) and its [sample seed script](sample-data/seed.cypher).

Examples use Cypher Shell parameters. Set token from the login result. The quickstart seed creates `Specimen` and `Context` classes and prints object IDs; assign those IDs to `$specimenId` and `$contextId` when needed. Other IDs come from the procedure that creates or returns them. Examples that use a `$name` parameter expect you to set it with Cypher Shell `:param` first. Each tracked example assumes a fresh UUIDv7 in the operation ID parameter and a committed registration using the exact domain arguments shown. Use a new ID for each example. Use a disposable Namespace when trying mutations.

The server checks account status, Namespace selection, and roles on every call. Reads usually need viewer access and edits usually need editor access. Special role rules are shown below. Namespace roles are viewer, editor, and owner; server administrators have server-wide rights.

Use SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'deposit.' RETURN name ORDER BY name to inspect the installed server. Neo4j built-in procedures are outside this list.

## Single-attempt operations

Generate a fresh UUIDv7 for each attempt. Register the procedure, exact ordered
domain arguments and explicit scope with `deposit.action.begin`. Commit the
registration before invoking the procedure once on the original authenticated
Bolt connection in a separate transaction. Use `server` for Server scope and
the Namespace UUID for Namespace scope.

```cypher
CALL deposit.action.begin($operationId, $namespaceId, 'deposit.set.class',
  [{name: 'Specimen'}, {comment: 'Example'}], $token) YIELD data RETURN data;
CALL deposit.set.class({name: 'Specimen'}, {comment: 'Example'}, $operationId, $token)
  YIELD node RETURN node;
```

Inspect the original attempt with `deposit.get.operation(operationId, scope, token)`. On a separate
connection, request cancellation with
`deposit.action.cancel(operationId, scope, token)`. Wait for a definitive
rollback before starting a fresh attempt. Cleanup progress is reported in
`cleanupState`.

The server retains settled attempts and receipts for at least 24 hours, and
longer while a dependent handle or cleanup needs them. After collection,
inspection can return `OUTCOME_UNKNOWN`. Treat the outcome as unknown and
keep the original operation ID for investigation. The server rejects attempts
to register the expired ID again.

### deposit.action.begin

Register a UUIDv7, scope, procedure and its ordered domain arguments before executing a tracked call.

Inputs: operationId (String), scope (String), procedure (String), arguments (List<Object>), token (String).

Returns one row with a `data` map.

### deposit.action.cancel

Request cancellation of the actor?s registered operation using its original ID and scope. Use a separate connection for an in-flight operation.

Inputs: operationId (String), scope (String), token (String).

Returns one row with a `data` map.

## Administration

### deposit.admin.pruneResources
Purpose: Run or inspect a server administration task.
Inputs: options (Map<String,Object>), operationId (String), token (String).
Access: Server administrator.
Example:

```cypher
CALL deposit.admin.pruneResources({}, $operationId, $token) YIELD data RETURN data;
```

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

Inputs: commands (List<Object>), operationId (String), token (String).
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
], $operationId, $token) YIELD data RETURN data;
```

The complete sample graph uses this procedure in [seed.cypher](sample-data/seed.cypher).

### deposit.create.class
Purpose: Create a Class and its declared Descriptors.
Inputs: name (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.class('Site', {descriptors: [{name: 'Mass', type: 'Float'}]}, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.classDescriptor
Purpose: Add a Descriptor declaration to a Class.
Inputs: class (Object), name (String), type (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.classDescriptor('Specimen', 'Example', 'String', {}, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.classRelationship
Purpose: Declare a relationship allowed between two Classes.
Inputs: source (Object), target (Object), label (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.classRelationship('Specimen', 'Context', 'foundIn', {}, $operationId, $token) YIELD rel RETURN rel;
```

### deposit.create.descriptor
Purpose: Add a Descriptor value to an Object.
Inputs: objectId (String), name (String), value (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.descriptor($specimenId, 'Mass', 15.0, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.namespace
Purpose: Create a Namespace. Its creator becomes the owner.
Inputs: name (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Any enabled Deposit user; creator becomes owner.
Example:

```cypher
CALL deposit.create.namespace('Example', {historyMode: 'disabled'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.object
Purpose: Create an Object in the active Namespace.
Inputs: class (Object), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.object('Specimen', {descriptors: {Code: 'S-04', Mass: 5.0}}, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.pattern
Purpose: Create a reusable Pattern for matching or importing data.
Inputs: name (String), definition (Map<String,Object>), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.pattern('Specimen form', {objects: {specimen: {className: 'Specimen', root: true, alwaysCreate: false, matchEmpty: false, descriptors: [{name: 'Code'}], relationships: [{label: 'foundIn', targetName: 'context'}]}, context: {className: 'Context', root: false, descriptors: [{name: 'Code'}], relationships: []}}}, {}, $operationId, $token) YIELD node RETURN node;
```

### deposit.create.relationship
Purpose: Connect two Objects with a relationship.
Inputs: sourceId (String), targetId (String), label (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.relationship($specimenId, $contextId, $label, {weight: 1.0}, $operationId, $token) YIELD rel RETURN rel;
```

### deposit.create.user
Purpose: Create a Deposit user account.
Inputs: username (String), password (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Server administrator.
Example:

```cypher
CALL deposit.create.user('demo-user', $password, {}, $operationId, $token) YIELD data RETURN data;
```

### deposit.create.userTool
Purpose: Save a reusable text tool in the active Namespace.
Inputs: name (String), properties (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.create.userTool('Example', {data: 'Example text'}, $operationId, $token) YIELD node RETURN node;
```

## Delete

### deposit.delete.all
Purpose: Clear Objects, Classes, Patterns and User Tools from the active Namespace; keep the Namespace itself.
Inputs: confirmNamespaceName (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.delete.all('Deposit sample', $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.class
Purpose: Delete an unused Class.
Inputs: class (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.class('UnusedExampleClass', $operationId, $token) YIELD data RETURN data;
```

This is destructive. It only succeeds if the class exists and has no dependent data. Do not use it on the quickstart classes.

### deposit.delete.classDescriptor
Purpose: Remove a Descriptor declaration from a Class.
Inputs: classDescriptor (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.classDescriptor($classDescriptor, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.classRelationship
Purpose: Remove a declared relationship between Classes.
Inputs: classRelationshipId (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.classRelationship($classRelationshipId, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.descriptor
Purpose: Remove a Descriptor value from an Object.
Inputs: descriptor (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.descriptor($descriptor, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.namespace
Purpose: Delete the selected Namespace.
Inputs: confirmNamespaceName (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.delete.namespace('Deposit sample', $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.object
Purpose: Delete an Object and its attached data.
Inputs: objectId (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.object($specimenId, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.pattern
Purpose: Delete a saved Pattern.
Inputs: pattern (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.pattern({id: $patternId}, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.relationship
Purpose: Delete a relationship between Objects.
Inputs: relationshipId (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.relationship($relationshipId, $operationId, $token) YIELD data RETURN data;
```

### deposit.delete.userTool
Purpose: Delete a saved text tool.
Inputs: userTool (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.delete.userTool($userTool, $operationId, $token) YIELD data RETURN data;
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
Inputs: rootIds (List<String>), options (Map<String,Object>), operationId (String), token (String).
Access: Namespace viewer for a subgraph; Namespace owner for a full export when history mode is persistent. An empty rootIds list selects the full Namespace.
Example:

```cypher
CALL deposit.get.data([$specimenId], {maxHops: 1}, $operationId, $token) YIELD data RETURN data;
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
Purpose: Show the last operation result known to this session. This is diagnostic and does not identify an uncertain request.
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
Inputs: options (Map<String,Object>), operationId (String), token (String).
Access: Namespace viewer for subgraph or transient/disabled full export; Namespace owner for full export with persistent history.
Example:

```cypher
CALL deposit.get.namespaceData({}, $operationId, $token) YIELD data RETURN data;
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

### deposit.import.analyze


Inputs: source (Map<String,Object>), rules (Map<String,Object>), operationId (String), token (String).
Analyze a canonical Exchange package without changing the receiving Namespace.
The active Namespace requires editor access. Obtain `packageId` and `grant`
from `deposit.exchange.export` in the source Namespace or from
`deposit.exchange.finishUpload` after intake in the receiving Scope. A full
export with persistent history requires source owner access. Register the
analyze call in the receiving Namespace and then execute it once.

```cypher
CALL deposit.import.analyze({packageId: $packageId, grant: $grant}, {}, $operationId, $token)
  YIELD data RETURN data;
```

### deposit.import.apply


Inputs: planId (String), operationId (String), token (String).
Register and apply the ready plan in the same receiving Store session and on
the same connection that created it. The Server checks the plan's target
revision and applies it in one transaction.

```cypher
CALL deposit.import.apply($planId, $operationId, $token) YIELD data RETURN data;
```

### deposit.import.discardPlan

Discard an unused import plan in the active Namespace.

Inputs: planId (String), token (String).

Returns one row with a `data` map.

### deposit.import.dataByPattern


Inputs: pattern (Object), values (Map<String,Object>), rules (Map<String,Object>), operationId (String), token (String).
Analyze form input against a stored Pattern in the active Namespace.

```cypher
CALL deposit.import.dataByPattern({id: $patternId}, {specimen: {Code: 'S-04'}}, {},
  $operationId, $token) YIELD data RETURN data;
```

### deposit.import.cloneNamespace


Inputs: source (Map<String,Object>), operationId (String), token (String).
Clone a complete Exchange package into a new Namespace in one transaction.
Register the attempt in Server scope. Use an authorized source-export grant
or a Server-scoped receiving-upload grant. The authenticated actor owns the
new Namespace.

```cypher
CALL deposit.import.cloneNamespace({packageId: $packageId, grant: $grant}, $operationId, $token)
  YIELD data RETURN data;
```

### deposit.import.rows
Purpose: Match source rows to Objects and stage an import plan.
Inputs: source (Map<String,Object>), targets (Map<String,Object>), relationships (List<Object>), rules (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.import.rows({rows: [{id: 'row-1', values: {Code: 'S-04', Mass: 5.0}}]}, {Code: {object: 'specimen', className: 'Specimen', descriptor: 'Code', type: 'String'}, Mass: {object: 'specimen', className: 'Specimen', descriptor: 'Mass', type: 'Float'}}, [], {}, $operationId, $token) YIELD data RETURN data;
```

## Load

### deposit.load.csv
Purpose: Load a resource into a table or inspect a package.
Inputs: resourceId (String), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.load.csv($resourceId, {}, $operationId, $token) YIELD data RETURN data;
```

### deposit.load.gpkg
Purpose: Load a resource into a table or inspect a package.
Inputs: resourceId (String), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.load.gpkg($resourceId, {}, $operationId, $token) YIELD data RETURN data;
```

### deposit.load.xlsx
Purpose: Load a resource into a table or inspect a package.
Inputs: resourceId (String), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.load.xlsx($resourceId, {}, $operationId, $token) YIELD data RETURN data;
```

## Resources

### deposit.resource.abortUpload
Purpose: Cancel an unfinished resource upload.
Inputs: uploadId (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.abortUpload($uploadId, $operationId, $token) YIELD data RETURN data;
```

### deposit.resource.beginUpload
Purpose: Start a chunked resource upload.
Inputs: filename (String), bytes (Long), sha256 (String), mediaType (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.beginUpload('hello.txt', 5, '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824', 'text/plain', $operationId, $token) YIELD data RETURN data;
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
Inputs: uploadId (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.finishUpload($uploadId, $operationId, $token) YIELD data RETURN data;
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
Inputs: uploadId (String), offset (Long), bytesBase64 (String), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.resource.uploadChunk($uploadId, 0, 'aGVsbG8=', $operationId, $token) YIELD data RETURN data;
```

For a small upload, call `beginUpload` with the filename, byte count, and whole-file SHA-256. Send each chunk in order with its byte offset and Base64 bytes, then call `finishUpload`. The calls above use the text `hello` (5 bytes). Use `abortUpload` to discard an unfinished transfer. To read a resource, call `info`, then request successive `downloadChunk` ranges until the reported byte count is reached.

## Save

### deposit.save.csv
Purpose: Export query or table results as CSV.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.csv({query: 'SELECT Specimen.Code AS code'}, {}, {}, $operationId, $token) YIELD data RETURN data;
```

### deposit.save.gpkg
Purpose: Export query or table results as a GeoPackage.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.gpkg({query: 'SELECT Specimen.Code AS code'}, {}, {}, $operationId, $token) YIELD data RETURN data;
```

### deposit.save.xlsx
Purpose: Export query or table results as an Excel workbook.
Inputs: source (Map<String,Object>), parameters (Map<String,Object>), formatOptions (Map<String,Object>), operationId (String), token (String).
Access: Namespace viewer.
Example:

```cypher
CALL deposit.save.xlsx({query: 'SELECT Specimen.Code AS code'}, {}, {}, $operationId, $token) YIELD data RETURN data;
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
Inputs: class (Object), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.class('Specimen', {comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.classDescriptor
Purpose: Update a Class Descriptor declaration.
Inputs: classDescriptor (Object), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.classDescriptor($classDescriptor, {comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.classRelationship
Purpose: Update a declared Class relationship.
Inputs: classRelationshipId (String), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.classRelationship($classRelationshipId, {comment: 'Example update'}, $operationId, $token) YIELD rel RETURN rel;
```

### deposit.set.descriptor
Purpose: Change a Descriptor value on an Object.
Inputs: objectId (String), name (String), value (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.descriptor($specimenId, 'Mass', 15.0, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.membership
Purpose: Move an Object to a Class.
Inputs: objectId (String), class (Object), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.membership($specimenId, 'Specimen', $operationId, $token) YIELD rel RETURN rel;
```

### deposit.set.namespace
Purpose: Update Namespace settings.
Inputs: patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.namespace({comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.namespaceRole
Purpose: Change a user’s role in the Namespace.
Inputs: user (Object), role (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.namespaceRole('demo-user', 'viewer', $operationId, $token) YIELD data RETURN data;
```

### deposit.set.object
Purpose: Update an Object.
Inputs: objectId (String), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.object($specimenId, {comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.password
Purpose: Change the password for the current Deposit user.
Inputs: oldPassword (String), newPassword (String), operationId (String), token (String).
Access: Authenticated Deposit session; changes the current user’s password.
Example:

```cypher
CALL deposit.set.password($oldPassword, $newPassword, $operationId, $token) YIELD value RETURN value;
```

### deposit.set.pattern
Purpose: Update a Pattern definition or comment.
Inputs: pattern (Object), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.pattern({id: $patternId}, {comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.relationship
Purpose: Update a relationship between Objects.
Inputs: relationshipId (String), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.relationship($relationshipId, {comment: 'Example update'}, $operationId, $token) YIELD rel RETURN rel;
```

### deposit.set.user
Purpose: Update a Deposit user account.
Inputs: user (Object), patch (Map<String,Object>), operationId (String), token (String).
Access: Server administrator.
Example:

```cypher
CALL deposit.set.user('demo-user', {comment: 'Example update'}, $operationId, $token) YIELD data RETURN data;
```

### deposit.set.userTool
Purpose: Update a saved text tool.
Inputs: userTool (Object), patch (Map<String,Object>), operationId (String), token (String).
Access: Namespace editor.
Example:

```cypher
CALL deposit.set.userTool($userTool, {comment: 'Example update'}, $operationId, $token) YIELD node RETURN node;
```

### deposit.set.versioningType
Purpose: Change the Namespace history mode.
Inputs: mode (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.versioningType('persistent', $operationId, $token) YIELD data RETURN data;
```

### deposit.set.versionTag
Purpose: Add or update a tag on a revision.
Inputs: revisionId (String), tag (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.set.versionTag($revisionId, 'example', $operationId, $token) YIELD data RETURN data;
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
Inputs: undoRevisionId (String), operationId (String), token (String).
Access: Action actor as editor; Namespace owner for another actor's action, subject to the history rules.
Example:

```cypher
CALL deposit.utils.redo($undoRevisionId, $operationId, $token) YIELD data RETURN data;
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
Inputs: targetRevisionId (String), operationId (String), token (String).
Access: Namespace owner.
Example:

```cypher
CALL deposit.utils.restore($revisionId, $operationId, $token) YIELD data RETURN data;
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
Inputs: revisionId (String), operationId (String), token (String).
Access: Action actor as editor; owner for another actor's action.
Example:

```cypher
CALL deposit.utils.undo($revisionId, $operationId, $token) YIELD data RETURN data;
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

Each procedure returns a `data` map. Use operation registration for export and upload calls that take an `operationId`.

| Procedure | Arguments in order | Use |
| --- | --- | --- |
| `deposit.exchange.export` | `rootIds, options, operationId, token` | Stage an exchange package for transfer. |
| `deposit.exchange.beginUpload` | `scope, inputKind, metadata, operationId, token` | Open an upload for an exchange package. |
| `deposit.exchange.uploadChunk` | `uploadId, member, offset, bytesBase64, operationId, token` | Upload a member's Base64-encoded bytes at the given offset. |
| `deposit.exchange.finishUpload` | `uploadId, operationId, token` | Finalize the upload and obtain the staged package. |
| `deposit.exchange.abortUpload` | `uploadId, operationId, token` | Abort the upload. |
| `deposit.exchange.inspect` | `packageId, grant, token` | Read package metadata and obtain a lease. |
| `deposit.exchange.memberPage` | `packageId, grant, leaseId, cursor, limit, token` | Page through package members. |
| `deposit.exchange.graphPage` | `packageId, grant, leaseId, collection, cursor, limit, token` | Page through a graph collection. |
| `deposit.exchange.readChunk` | `packageId, grant, leaseId, member, offset, length, token` | Read a byte range from a member. |
| `deposit.exchange.discard` | `packageId, grant, token` | Discard a staged package. |

## Result notes

`node` and `rel` are native Neo4j handles. Use `deposit.utils.toData(entity, token)` to convert an entity to portable Deposit data. Use `value` for a scalar result and `data` for a structured result. Query streams expose `data`, `columns`, and `presence` as separate result columns. Page procedures return their page inside `data`.




