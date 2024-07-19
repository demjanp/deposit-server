# Deposit server APOC procedures

## deposit.create.namespace

Creates a new Namespace.

### Parameters:

- **name**: the name of the Namespace to create.
- **description**: annotation to provide additional information about the Namespace, default is an empty string.
- **versioningType**: type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.

### Throws:

- **RuntimeException**: if a namespace with that name already exists or if there is an error during creation.

### Usage:

```cypher
CALL deposit.create.namespace(name) YIELD node
```

