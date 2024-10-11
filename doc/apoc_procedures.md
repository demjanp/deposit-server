# Deposit server APOC procedures

## deposit.create.class

Create a Class.

### Parameters:

- **className**: the name of the Class to create.
- **order**: an order of the Class when represented in a list, default is -1.
- **position**: position of the node in a hierarchical graph layout defined as [x, y], default is [].
- **comment**: annotation to provide additional information about the Class, default is an empty string.
- **descriptors**: Descriptors of the Class defined as a list of lists [[<name>, <type>] or [<name>, <type>, <comment>], ...], where <name> is the name of the Descriptor, <type> is any Neo4j type or DResource and comment is an annotation. Default is [].
- **token**: authentication token.

### Return:

the created Class node.

### Throws:

- **RuntimeException**: if a Class with that name already exists or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.class(className, order, position, comment, descriptors, token) YIELD node AS class
```

## deposit.create.classDescriptor

Create a Class Descriptor.

### Parameters:

- **className**: the name of the Class.
- **descriptorName**: the name of the Descriptor to create.
- **type**: can be the name of any Neo4j type or DResource.
- **order**: an order of the Descriptor when represented in a list, default is -1.
- **comment**: annotation to provide additional information about the Descriptor, default is an empty string.
- **token**: authentication token.

### Return:

the created Class Descriptor node.

### Throws:

- **RuntimeException**: if a Descriptor with that name already exists for the Class or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.classDescriptor(className, descriptorName, type, order, comment, token) YIELD node AS descriptor
```

## deposit.create.classRelationship

Create a Class Relationship.

### Parameters:

- **startClassName**: the name of the start Class.
- **endClassName**: the name of the end Class.
- **label**: the label of the Relationship.
- **comment**: annotation to provide additional information about the Relationship, default is an empty string.
- **token**: authentication token.

### Return:

the created Relationship.

### Throws:

- **RuntimeException**: if the Class Relationship already exists or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.classRelationship(startClassName, endClassName, label, comment, token) YIELD rel
```

## deposit.create.descriptor

Create a Descriptor.

### Parameters:

- **objectId**: the Id of the Object.
- **descriptorName**: the name of the Descriptor to create.
- **value**: can be of any Neo4j type or a DResource node.
- **token**: authentication token.

### Return:

the created Descriptor node.

### Throws:

- **RuntimeException**: if a Descriptor with that name already exists for the Object or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.descriptor(objectId, descriptorName, value, token) YIELD node AS descriptor
```

## deposit.create.namespace

Creates a new Namespace.

### Parameters:

- **namespaceName**: the name of the Namespace to create.
- **description**: annotation to provide additional information about the Namespace, default is an empty string.
- **versioningType**: type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.
- **token**: authentication token.

### Return:

the created Namespace node.

### Throws:

- **RuntimeException**: if a namespace with that name already exists or if there is an error during creation.

### Usage:

```cypher
CALL deposit.create.namespace(namespaceName, description, versioningType, token) YIELD node AS namespace
```

## deposit.create.object

Create an Object.

### Parameters:

- **className**: the name of the Object's Class.
- **descriptors**: Descriptors of the Object defined as a list of lists [[<name>, <value>], ...], where <name> is the name of the Descriptor, <value> is any Neo4j type or a resource or geometry node. Default is [].
- **position**: position of the node in a hierarchical graph layout defined as [x, y], default is [].
- **token**: authentication token.

### Return:

the created Object node.

### Throws:

- **RuntimeException**: if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.object(className, descriptors, position, token) YIELD node AS object
```

## deposit.create.relationship

Create a Relationship.

### Parameters:

- **startObjectId**: the Id of the start Object.
- **endObjectId**: the Id of the end Object.
- **label**: the label of the Relationship.
- **weight**: the weight of the Relationship, if null is passed it defaults to 0.
- **token**: authentication token.

### Return:

the created Relationship.

### Throws:

- **RuntimeException**: if the Relationship already exists or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.relationship(startObjectId, endObjectId, label, weight, token) YIELD rel
```

## deposit.create.user

Create a User node.

### Parameters:

- **username**: new username to create.
- **password**: password (unhashed).
- **token**: authentication token.

### Return:

the created User node.

### Throws:

- **RuntimeException**: if the User node couldn't be created.

### Usage:

```cypher
CALL deposit.create.user(username, password, token) YIELD node AS user
```

## deposit.create.userTool

Create a User Tool.

### Parameters:

- **userToolName**: name of the User Tool.
- **data**: JSON encoded string representing the definition of the User Tool.
- **comment**: annotation to provide additional information about the User Tool, default is an empty string.
- **token**: authentication token.

### Return:

the created User Tool node.

### Throws:

- **RuntimeException**: if User Tool with the same name already exists or there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.userTool(userToolName, data, comment, token) YIELD node AS userTool
```

## deposit.delete.all

Delete all nodes from the active Namespace.

### Parameters:

- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion.

### Usage:

```cypher
CALL deposit.delete.all(token)
```

## deposit.delete.class

Delete a Class.

### Parameters:

- **className**: the name of the Class to delete.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion.

### Usage:

```cypher
CALL deposit.delete.class(className, token)
```

## deposit.delete.classById

Delete a Class.

### Parameters:

- **classId**: the Id of the Class.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Class doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.classById(classId, token)
```

## deposit.delete.classDescriptor

Delete a Class Descriptor.

### Parameters:

- **className**: the name of the Class.
- **descriptorName**: the name of the Class Descriptor.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Class Descriptor doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.classDescriptor(className, descriptorName, token)
```

## deposit.delete.classDescriptorById

Delete a Class Descriptor.

### Parameters:

- **descriptorId**: the Id of the Class Descriptor.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Class Descriptor doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.classDescriptorById(descriptorId, token)
```

## deposit.delete.classRelationship

Delete a Class Relationship.

### Parameters:

- **startClassName**: the name of the start Class.
- **endClassName**: the name of the end Class.
- **label**: the label of the Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Class Relationship doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.classRelationship(startClassName, endClassName, label, token)
```

## deposit.delete.classRelationshipById

Delete a Class Relationship.

### Parameters:

- **relationshipId**: the Id of the Class Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Relationship doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.classRelationshipById(relationshipId, token)
```

## deposit.delete.descriptor

Delete a Descriptor.

### Parameters:

- **objectId**: the Id of the Object.
- **descriptorName**: the name of the Descriptor.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Descriptor doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.descriptor(objectId, descriptorName, token)
```

## deposit.delete.descriptorById

Delete a Descriptor.

### Parameters:

- **descriptorId**: the Id of the Descriptor.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Descriptor doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.descriptorById(descriptorId, token)
```

## deposit.delete.namespace

Deletes a Namespace.

### Parameters:

- **namespaceName**: the name of the Namespace to delete.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion.

### Usage:

```cypher
CALL deposit.delete.namespace(namespaceName, token)
```

## deposit.delete.object

Delete an Object.

### Parameters:

- **objectId**: the Id of the Object.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Object doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.object(objectId, token)
```

## deposit.delete.relationship

Delete a Relationship.

### Parameters:

- **startObjectId**: the Id of the start Object.
- **endObjectId**: the Id of the end Object.
- **label**: the label of the Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Relationship doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.relationship(startObjectId, endObjectId, label)
```

## deposit.delete.relationshipById

Delete a Relationship.

### Parameters:

- **relationshipId**: the Id of the Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the Relationship doesn't exist or there is an error during the deletion.

### Usage:

```cypher
CALL deposit.delete.relationshipById(relationshipId, token)
```

## deposit.delete.user

Delete a User node.

### Parameters:

- **username**: the username of the User to delete.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion

### Usage:

```cypher
CALL deposit.delete.user(username, token)
```

## deposit.delete.userTool

Delete a User Tool.

### Parameters:

- **userToolName**: the name of the User Tool to delete.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion.

### Usage:

```cypher
CALL deposit.delete.userTool(userToolName, token)
```

## deposit.delete.userToolById

Delete a User Tool.

### Parameters:

- **userToolId**: Id of the User Tool to delete.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during deletion.

### Usage:

```cypher
CALL deposit.delete.userToolById(userToolId, token)
```

## deposit.get.activeNamespace

Returns the active Namespace node.

### Parameters:

- **token**: authentication token.

### Return:

the active Namespace node or null if none is set.

### Usage:

```cypher
CALL deposit.get.activeNamespace(token) YIELD node AS namespace
```

## deposit.get.class

Get existing Class nodes.

### Parameters:

- **className**: name of the Class to retrieve or empty for all Classes.
- **token**: authentication token.

### Return:

the Class node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.class(className, token) YIELD node AS class
```

## deposit.get.classById

Get an existing Class node.

### Parameters:

- **classId**: Id of the Class to retrieve.
- **token**: authentication token.

### Return:

the Class node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.classById(classId, token) YIELD node AS class
```

## deposit.get.classDescriptor

Get existing Class Descriptor nodes.

### Parameters:

- **className**: the name of the Class.
- **descriptorName**: name of the Class Descriptor to retrieve or empty for all Descriptors of the Class.
- **token**: authentication token.

### Return:

the Descriptor node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.classDescriptor(className, descriptorName, token) YIELD node AS descriptor
```

## deposit.get.classDescriptorById

Get existing Class Descriptor nodes.

### Parameters:

- **descriptorId**: the Id of the Class Descriptor.
- **token**: authentication token.

### Return:

the Class Descriptor node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.classDescriptorById(descriptorId, token) YIELD node AS descriptor
```

## deposit.get.classMembers

Get Members of a Class.

### Parameters:

- **className**: the name of the Class.
- **token**: authentication token.

### Return:

the Object nodes.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.classMembers(className, token) YIELD node AS object
```

## deposit.get.classPosition

Get the position of a Class node in the graph.

### Parameters:

- **className**: the name of the Class.
- **token**: authentication token.

### Return:

the position.

### Throws:

- **RuntimeException**: if retrieving the position failed.

### Usage:

```cypher
CALL deposit.get.classPosition(className, token) YIELD x, y
```

## deposit.get.classRelationship

Get existing Class Relationship.

### Parameters:

- **startClassName**: the name of the start Class.
- **endClassName**: the name of the end Class or empty for all related Classes.
- **label**: the label of the Class Relationship or empty for all Relationships.
- **token**: authentication token.

### Return:

the source, target, relationship(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the relationship(s).

### Usage:

```cypher
CALL deposit.get.classRelationship(startClassName, endClassName, label, token) YIELD rel
```

## deposit.get.classRelationshipById

Get existing Class Relationship.

### Parameters:

- **relationshipId**: the Id of the Class Relationship.
- **token**: authentication token.

### Return:

the relationship or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the relationship.

### Usage:

```cypher
CALL deposit.get.classRelationshipById(relationshipId, token) YIELD rel
```

## deposit.get.data

Get nodes (except User Tools and Archive nodes), Relationships and resource filenames for the subtrees of the root Objects or the whole Namespace. The returned resources are copied to transient storage.

### Parameters:

- **rootIds**: a list of root Object ids or empty for the whole Namespace. Default is [].
- **token**: authentication token.

### Return:

a Map in the format:  {graph: {nodes: nodes, edges: edges}, resources: [<local filename of the resource>, ...]} where graph is in GraphSON format

### Throws:

- **RuntimeException**: if there is an error retrieving the data.

### Usage:

```cypher
CALL deposit.get.data(rootIds, token) YIELD data
```

## deposit.get.descriptor

Get existing Descriptor nodes.

### Parameters:

- **objectId**: the Id of the Object.
- **descriptorName**: name of the Descriptor to retrieve or empty for all Descriptors of the Object.
- **token**: authentication token.

### Return:

the Descriptor node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.descriptor(objectId, descriptorName, token) YIELD node AS descriptor
```

## deposit.get.descriptorById

Get an existing Descriptor node.

### Parameters:

- **descriptorId**: the Id of the Descriptor.
- **token**: authentication token.

### Return:

the Descriptor node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.descriptorById(descriptorId, token) YIELD node AS descriptor
```

## deposit.get.namespace

Brief description.

### Parameters:

- **namespaceName**: name of the Namespace to retrieve or empty for all namespaces.
- **token**: authentication token.

### Return:

the Namespace node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.namespace(namespaceName, token) YIELD node AS namespace
```

## deposit.get.namespaceData

Get nodes, Relationships and resource filenames for the whole Namespace.

### Parameters:

- **token**: authentication token.

### Return:

a Map in the format:  {graph: {nodes: nodes, edges: edges}, resources: [<local filename of the resource>, ...]} where graph is in GraphSON format

### Throws:

- **RuntimeException**: if there is an error retrieving the data.

### Usage:

```cypher
CALL deposit.get.namespaceData(token) YIELD data
```

## deposit.get.object

Get existing Object nodes which match the parameters (all if no parameters are given).

### Parameters:

- **className**: a list of Class names, default is [].
- **descriptors**: Descriptors of the Object defined as a list of lists [[<name>, <value>], ...], where <name> is the name of the Descriptor, <value> is any Neo4j type. Default is [].
- **token**: authentication token.

### Return:

the Object node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.object(className, descriptors, token) YIELD node AS object
```

## deposit.get.objectById

Get an existing Object node.

### Parameters:

- **objectId**: Id of the Object to retrieve.
- **token**: authentication token.

### Return:

the Object node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.objectById(objectId, token) YIELD node AS object
```

## deposit.get.objectClass

Get Classes of an Object.

### Parameters:

- **objectId**: the Id of the Object.
- **token**: authentication token.

### Return:

the Class nodes.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.objectClass(objectId, token) YIELD node AS class
```

## deposit.get.objectClassDescriptor

Get the ClassDescriptor of the Object's Class.

### Parameters:

- **objectId**: the Id of the Object.
- **descriptorName**: name of the Descriptor to retrieve or empty for all Descriptors of the Object.
- **token**: authentication token.

### Return:

the Class Descriptor node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.objectClassDescriptor(objectId, descriptorName, token) YIELD node AS descriptor
```

## deposit.get.objectClassDescriptorById

Get the corresponding ClassDescriptor to the Descriptor of an Object.

### Parameters:

- **descriptorId**: the Id of the Object's Descriptor.
- **token**: authentication token.

### Return:

the Class Descriptor node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.objectClassDescriptorById(descriptorId, token) YIELD node AS descriptor
```

## deposit.get.objectPosition

Get the position of an Object node in the graph.

### Parameters:

- **objectId**: Id of the Object to retrieve.
- **token**: authentication token.

### Return:

the position.

### Throws:

- **RuntimeException**: if retrieving the position failed.

### Usage:

```cypher
CALL deposit.get.objectPosition(objectId, token) YIELD x, y
```

## deposit.get.objectsByData

Try to retrieve Objects from an identical data structure in the active Namespace as the one supplied as GraphSON data.

### Parameters:

- **data**: Map in the GraphSON format: {nodes: nodes, edges: edges}, where nodes = [{id, label, properties: {name: value, ...}}, ...], edges = [{id, type, outV: start_node_id, inV: end_node_id, properties: {name: value, ...}}, ...]
- **overwrite**: List of ids of Objects existing both in the data and the current Namespace where Descriptors should be ignored when matching (they are about to be overwritten).
- **matchEmpty**: boolean, if true, ensure all Descriptors of the matched Objects are present and equal those in data. Otherwise only match Descriptors present in the Namespace and with non-null values in the data.
- **token**: authentication token.

### Return:

a map of the original Object ids and lists of ids of the matched Objects in the current Namespace

### Throws:

- **RuntimeException**: if there was an error retrieving the nodes.

### Usage:

```cypher
CALL deposit.get.objectsByData(data, overwrite, matchEmpty, token) YIELD data
```

## deposit.get.query

Executes a Deposit query and returns the result as MapResult.

### Parameters:

- **queryStr**: the Deposit query string
- **token**: the authentication token

### Return:

a stream of MapResult objects. Use result.get(key) to retrieve a returned node. Keys are identical to the identifiers used in the SELECT clause.

### Throws:

- **RuntimeException**: if there is an error executing the query

### Usage:

```cypher
CALL deposit.get.query(queryStr, token) YIELD result
```

## deposit.get.relationship

Get existing Relationship.

### Parameters:

- **startObjectId**: the Id of the start Object.
- **endObjectId**: the Id of the end Object or empty for all related Objects.
- **label**: the label of the Relationship or empty for all Relationships.
- **token**: authentication token.

### Return:

the relationship(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the relationship(s).

### Usage:

```cypher
CALL deposit.get.relationship(startObjectId, endObjectId, label, token) YIELD rel
```

## deposit.get.relationshipById

Get existing Relationship.

### Parameters:

- **relationshipId**: the Id of the Relationship.
- **token**: authentication token.

### Return:

the relationship or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the relationship.

### Usage:

```cypher
CALL deposit.get.relationshipById(relationshipId, token) YIELD rel
```

## deposit.get.user

Get existing User node(s).

### Parameters:

- **username**: the username of the User to retrieve or empty for all User nodes.
- **token**: authentication token.

### Return:

the User node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.user(username, token) YIELD node AS user
```

## deposit.get.userById

Get an existing User node.

### Parameters:

- **userId**: the Id of the User node to retrieve.
- **token**: authentication token.

### Return:

the User node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.userById(userId, token) YIELD node AS user
```

## deposit.get.userTool

Get existing User Tool nodes.

### Parameters:

- **userToolName**: name of the User Tool to retrieve or empty for all User Tools.
- **token**: authentication token.

### Return:

the User Tool node(s) or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.userTool(userToolName, token) YIELD node AS userTool
```

## deposit.get.userToolById

Get an existing User Tool node.

### Parameters:

- **userToolId**: Id of the User Tool to retrieve.
- **token**: authentication token.

### Return:

the User Tool node or null if it doesn't exist.

### Throws:

- **RuntimeException**: if there was an error retrieving the node.

### Usage:

```cypher
CALL deposit.get.userToolById(userToolId, token) YIELD node AS userTool
```

## deposit.get.version

Get the version of the Deposit server.

### Return:

the version as a string.

### Throws:

- **RuntimeException**: if there was an error retrieving the version.

### Usage:

```cypher
CALL deposit.get.version() YIELD value AS version
```

## deposit.import.data

Import GraphSON data into the database.

### Parameters:

- **data**: Map in the GraphSON format: {nodes: nodes, edges: edges, resources: resources}, where nodes = [{id, label, properties: {name: value, ...}}, ...], edges = [{id, type, outV: start_node_id, inV: end_node_id, properties: {name: value, ...}}, ...], resources: [<local filename of the resource>, ...]
- **unique**: List of Class names where new Objects should always be added, rather than reusing existing Objects with identical Descriptors. This ensures unique Objects for these Classes.
- **overwrite**: List of ids of Objects existing both in the data and the current Namespace where existing Descriptors should be deleted and new ones created.
- **matchEmpty**: boolean, if true, ensure all Descriptors of the matched Objects are present and equal those in data. Otherwise only match Descriptors present in the Namespace and with non-null values in the data.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there was an error importing the data.

### Usage:

```cypher
CALL deposit.import.data(data, unique, overwrite, matchEmpty, token)
```

## deposit.set.activeNamespace

Sets the active Namespace.

### Parameters:

- **namespaceName**: name of the Namespace.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if Namespace doesn't exist.

### Usage:

```cypher
CALL deposit.set.activeNamespace(namespaceName, token)
```

## deposit.set.classComment

Set comment of a Class.

### Parameters:

- **className**: the name of the Class.
- **comment**: annotation to provide additional information about the Class.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the comment.

### Usage:

```cypher
CALL deposit.set.classComment(className, comment, token)
```

## deposit.set.classDescriptorComment

Set comment of a Class Descriptor.

### Parameters:

- **className**: the name of the Class.
- **descriptorName**: the name of the Class Descriptor.
- **comment**: annotation to provide additional information about the Class Descriptor.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the comment.

### Usage:

```cypher
CALL deposit.set.classDescriptorComment(className, descriptorName, comment, token)
```

## deposit.set.classDescriptorOrder

Set order of a Class Descriptor.

### Parameters:

- **className**: the name of the Class.
- **descriptorName**: the name of the Descriptor.
- **order**: an order of the Class when represented in a list, -1 indicates the end of the list.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the order.

### Usage:

```cypher
CALL deposit.set.classDescriptorOrder(className, descriptorName, order, token)
```

## deposit.set.classOrder

Set order of a Class.

### Parameters:

- **className**: the name of the Class.
- **order**: an order of the Class when represented in a list, -1 indicates the end of the list.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the order.

### Usage:

```cypher
CALL deposit.set.classOrder(className, order, token)
```

## deposit.set.classPosition

Set the position of a Class node in the graph.

### Parameters:

- **className**: the name of the Class.
- **position**: position of the node in a hierarchical graph layout defined as [x, y] or [] to unset position.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if setting the position failed.

### Usage:

```cypher
CALL deposit.set.classPosition(className, position, token)
```

## deposit.set.classRelationship

Create or Update a Class Relationship.

### Parameters:

- **startClassName**: the name of the start Class.
- **endClassName**: the name of the end Class.
- **label**: the label of the Relationship.
- **comment**: annotation to provide additional information about the Relationship, default is an empty string.
- **token**: authentication token.

### Return:

the Relationship.

### Throws:

- **RuntimeException**: if there is an error during the update.

### Usage:

```cypher
CALL deposit.set.classRelationship(startClassName, endClassName, label, comment, token) YIELD rel
```

## deposit.set.classRelationshipComment

Set comment of a Class Relationship.

### Parameters:

- **startClassName**: the name of the start Class.
- **endClassName**: the name of the end Class.
- **label**: the label of the Relationship.
- **comment**: annotation to provide additional information about the Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the comment.

### Usage:

```cypher
CALL deposit.set.classRelationshipComment(startClassName, endClassName, label, comment, token)
```

## deposit.set.descriptor

Create or update a Descriptor.

### Parameters:

- **objectId**: the Id of the Object.
- **descriptorName**: the name of the Descriptor to set.
- **value**: can be of any Neo4j type or a DResource node.
- **token**: authentication token.

### Return:

the Descriptor node.

### Throws:

- **RuntimeException**: if there is an error creating or updating the Descriptor.

### Usage:

```cypher
CALL deposit.set.descriptor(objectId, descriptorName, value, token) YIELD node AS descriptor
```

## deposit.set.membership

Set the Membership of an Object in a Class.

### Parameters:

- **className**: the name of the Class.
- **objectId**: the Id of the Object.
- **token**: authentication token.

### Return:

the created Membership relationship.

### Throws:

- **RuntimeException**: if the Membership already exists or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.set.membership(className, objectId, token) YIELD rel
```

## deposit.set.namespaceDescription

Set description of the active Namespace.

### Parameters:

- **description**: annotation to provide additional information about the Namespace.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the description.

### Usage:

```cypher
CALL deposit.set.namespaceDescription(description, token)
```

## deposit.set.objectPosition

Set the position of an Object node in the graph.

### Parameters:

- **objectId**: Id of the Object to update.
- **position**: position of the node in a hierarchical graph layout defined as [x, y] or [] to unset position.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if setting the position failed.

### Usage:

```cypher
CALL deposit.set.objectPosition(objectId, position, token)
```

## deposit.set.password

Set password for a User node.

### Parameters:

- **username**: the username.
- **password**: the new password (unhashed).
- **token**: authentication token.

### Throws:

- **RuntimeException**: if the User node couldn't be updated.

### Usage:

```cypher
CALL deposit.set.password(username, password, token)
```

## deposit.set.relationship

Create or Update a Relationship.

### Parameters:

- **startObjectId**: the Id of the start Object.
- **endObjectId**: the Id of the end Object.
- **label**: the label of the Relationship.
- **weight**: the weight of the Relationship, if null is passed it defaults to 0.
- **token**: authentication token.

### Return:

the Relationship node.

### Throws:

- **RuntimeException**: if there is an error during the update.

### Usage:

```cypher
CALL deposit.set.relationship(startObjectId, endObjectId, label, weight, token) YIELD rel
```

## deposit.set.relationshipWeight

Update the weight of a Relationship.

### Parameters:

- **startObjectId**: the Id of the start Object.
- **endObjectId**: the Id of the end Object.
- **label**: the label of the Relationship.
- **weight**: the weight of the Relationship.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error setting the weight.

### Usage:

```cypher
CALL deposit.set.relationshipWeight(startObjectId, endObjectId, label, weight, token)
```

## deposit.set.versioningType

Sets the versioning type.

### Parameters:

- **versioningType**: type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there is an error during the transaction.

### Usage:

```cypher
CALL deposit.set.versioningType(versioningType, token)
```

## deposit.utils.coordinatesToWKT

Convert a list of coordinates into a WKT.

### Parameters:

- **type**: geometry type.
- **coordinates**: a list or list of lists of coordinates.

### Return:

the WKT.

### Throws:

- **RuntimeException**: if there is an error converting the value.

### Usage:

```cypher
CALL deposit.utils.coordinatesToWKT(type, coordinates) YIELD value AS wkt
```

## deposit.utils.download

Download a file resource attached to the Descriptor.

### Parameters:

- **descriptor**: the Descriptor with the DResource value. Can be a Descriptor node or Id.
- **token**: authentication token.

### Return:

the content of the file as a Base64 encoded string.

### Throws:

- **RuntimeException**: if there was an error retrieving the file resource.

### Usage:

```cypher
CALL deposit.utils.download(descriptor, token) YIELD stream
```

## deposit.utils.login

Brief description.

### Parameters:

- **username**: the username.
- **password**: the password (unhashed).

### Return:

the value.

### Throws:

- **RuntimeException**: if error condition is fulfilled.

### Usage:

```cypher
CALL deposit.utils.login(username, password) YIELD value AS token
```

## deposit.utils.upload

Upload a file resource to the transient storage on the server and return a DResource node.

### Parameters:

- **stream**: the content of the file as a Base64 encoded string.
- **filename**: the name of the file.
- **token**: authentication token.

### Return:

the created DResource node.

### Throws:

- **RuntimeException**: if there was an error uploading the file.

### Usage:

```cypher
CALL deposit.utils.upload(stream, filename, token) YIELD resource
```

## deposit.utils.wktToCoordinates

Convert a WKT to a list of coordinates.

### Parameters:

- **wkt**: well-known-text string.

### Return:

the coordinates.

### Throws:

- **RuntimeException**: if there is an error converting the value.

### Usage:

```cypher
CALL deposit.utils.wktToCoordinates(wkt) YIELD value AS coordinates
```

