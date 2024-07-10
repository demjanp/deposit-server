# Deposit server APOC procedures

## deposit.create.classDescriptor

Creates a descriptor for a Class node with specified properties.

### Parameters:

- **className**:      the name of the class.
- **descriptorName**: the name of the descriptor.
- **type**:           the type of the descriptor.
- **order**:          the order of the descriptor, default is 0.
- **comment**:        the comment for the descriptor, default is an empty string.

### Throws:

- **RuntimeException**: if there is an error during setting the descriptor.

### Usage:

```cypher
CALL deposit.create.classDescriptor(parameters)
```

## deposit.create.class

Creates a Class node with specified properties.

### Parameters:

- **name		**:  the name of the class.
- **order		**:  the order of the class, default is 0.
- **position	**:  the position of the class as a list of two floats [x, y], default is [0.0, 0.0].
- **comment	**:  the comment for the class, default is an empty string.
- **descriptors**: the descriptors for the class in the format [['<name>', '<type>'] or ['<name>', '<type>', '<comment>'], ...], default is an empty list.

### Throws:

- **RuntimeException**: if there is an error during creation or if position is invalid.

### Usage:

```cypher
CALL deposit.create.classDescriptor(className, descriptorName, type, order, comment)
```

## deposit.create.database

Creates a new database and adds a Metadata node with specified properties.

### Parameters:

- **databaseName**: the name of the database to create.

### Throws:

- **RuntimeException**: if the database name ends with '_archive' or if there is an error during creation.

### Usage:

```cypher
CALL deposit.create.class(name, order, position, comment, descriptors) YIELD node
```

