# Deposit server APOC procedures

## deposit.create.class

Create a Class.

### Parameters:

- **className**: the name of the Class to create.
- **order**: an order of the Class when represented in a list, default is -1.
- **position**: position of the node in a hierarchical graph layout defined as [x, y], default is [].
- **comment**: annotation to provide additional information about the Class, default is an empty string.
- **descriptors**: Descriptors of the Class defined as a list of lists [[<name>, <type>] or [<name>, <type>, <comment>], ...], where <name> is the name of the Descriptor, <type> is any Neo4j or Deposit type and comment is an annotation. Default is [].
- **token**: authentication token.

### Throws:

- **RuntimeException**: if a Class with that name already exists or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.class(className, order, position, comment, descriptors, token) YIELD node AS class
```

## deposit.create.classDescriptor

Create a Class Descriptor.

### Parameters:

- **className**: the name of the Class
- **descriptorName**: the name of the Descriptor to create
- **type**: can be the name of any Neo4j or Deposit type
- **order**: an order of the Descriptor when represented in a list, default is -1.
- **comment**: annotation to provide additional information about the Descriptor, default is an empty string.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if a Descriptor with that name already exists for the Class or if there is an error during the creation.

### Usage:

```cypher
CALL deposit.create.classDescriptor(className, descriptorName, type, order, comment, token) YIELD node AS descriptor
```

## deposit.create.namespace

Creates a new Namespace.

### Parameters:

- **namespaceName**: the name of the Namespace to create.
- **description**: annotation to provide additional information about the Namespace, default is an empty string.
- **versioningType**: type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if a namespace with that name already exists or if there is an error during creation.

### Usage:

```cypher
CALL deposit.create.namespace(namespaceName, description, versioningType, token) YIELD node AS namespace
```

## deposit.create.user

Brief description.

### Parameters:

- **username**: new username to create.
- **password**: password (unhashed).
- **token**: authentication token.

### Throws:

- **RuntimeException**: if User node couldn't be created.

### Usage:

```cypher
CALL deposit.create.user(username, password, token) YIELD node AS user
```

## deposit.get.activeNamespace

Returns the active Namespace node.

### Parameters:

- **token**: authentication token.

### Usage:

```cypher
CALL deposit.get.activeNamespace(token) YIELD node AS namespace
```

## deposit.get.class

Get existing Class nodes.

### Parameters:

- **className**: name of the Class to retrieve or empty for all classes.
- **token**: authentication token.

### Throws:

- **RuntimeException**: if there was an error retrieving the node(s).

### Usage:

```cypher
CALL deposit.get.class(className, token) YIELD node AS class
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

## deposit.utils.login

Brief description.

### Parameters:

- **username**: the username.
- **password**: the password (unhashed).

### Throws:

- **RuntimeException**: if error condition is fulfilled.

### Usage:

```cypher
CALL deposit.utils.login(username, password) YIELD value AS token
```

