grammar DepositQuery;

@header {
package deposit.service.query;
}

// Entry point
query          : selectClause whereClause? EOF;

// SELECT clause
selectClause   : 'SELECT' DISTINCT? selectItem (',' selectItem)*;
selectItem     : aggregationFunction | identifier ('.' identifier)? ('AS' alias)?;

// Aggregation functions
aggregationFunction
               : functionName '(' identifier ('.' identifier)? (',' NUMBER)? ')' ('AS' alias)?;

// WHERE clause
whereClause    : 'WHERE' condition (logicalOp condition)*;

// Conditions for both relationships and expressions
condition      : 'NOT'? relationship
               | 'NOT'? expression comparator value;

// Expressions for descriptors
expression     : identifier ('.' identifier)?;

// Relationship pattern, e.g., C1-contains->C2
relationship   : identifier ('<-' | '-') label? ('*' minHops '..' maxHops)? '->' identifier
               | identifier '<-' label? ('*' minHops '..' maxHops)? '-' identifier
               | identifier '-' label? ('*' minHops '..' maxHops)? '-' identifier;

minHops        : NUMBER;
maxHops        : NUMBER;

// Label is the relationship label like 'contains'
label          : identifier;

// Values and comparators
value          : QUOTED_STRING | NUMBER | tuple;
comparator     : '=' | 'IN' | '<>' | '!=' | '>' | '<';

// Logical operators for WHERE conditions
logicalOp      : 'AND' | 'OR';

// Aliases for SELECT items
alias          : identifier;

// Tuple support for multiple values
tuple          : '(' value (',' value)* ')';

// Supported aggregation functions
functionName   : 'COUNT' | 'PERCENTILE' | 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COLLECT';

// Identifiers and keywords
DISTINCT       : 'DISTINCT';
identifier     : ID | QUOTED_STRING;

// Basic tokens (identifiers, strings, numbers, and whitespace)
ID             : [a-zA-Z_][a-zA-Z0-9_]*;
QUOTED_STRING  : '"' ( ~["\\] | '\\' . )* '"' ;
NUMBER         : [0-9]+ ('.' [0-9]+)?;
WS             : [ \t\r\n]+ -> skip;
