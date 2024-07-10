# Deposit server APOC procedures

## deposit.create.database

Creates a new database and adds a Metadata node with specified properties.

### Parameters:

- **databaseName**: the name of the database to create.

### Throws:

- **RuntimeException**: if the database name ends with '_archive' or if there is an error during creation.

### Usage:

```cypher
CALL deposit.create.database(parameters)
```

