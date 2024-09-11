grammar DepositQuery;

@header {
package deposit.service.query;
}

query
    : selectClause whereClause? EOF
    ;

selectClause
    : 'SELECT' DISTINCT? selectItem (',' selectItem)*
    ;

selectItem
    : aggregationFunction
    | identifier ('.' identifier)? ('AS' alias)?
    ;

aggregationFunction
    : functionName '(' identifier ('.' identifier)? (',' NUMBER)? ')' ('AS' alias)?
    ;

whereClause
    : 'WHERE' logicalExpression
    ;

logicalExpression
    : logicalTerm (logicalOp logicalTerm)*
    ;

logicalTerm
    : 'NOT' logicalTerm                        // NOT can apply to any term
    | '(' 'NOT'? logicalExpression ')'         // NOT before a parenthesized expression
    | atomicCondition                         // Basic condition
    | '(' logicalExpression ')'               // Parenthesized logical expressions
    ;

condition
    : atomicCondition
    | '(' logicalExpression ')'
    ;

atomicCondition
    : identifier ('.' identifier)? comparator value  
    | relationship
    ;

relationship
    : identifier ('<-' | '-') label? ('*' minHops '..' maxHops)? '->' identifier
    | identifier '<-' label? ('*' minHops '..' maxHops)? '-' identifier
    | identifier '-' label? ('*' minHops '..' maxHops)? '-' identifier
    ;

minHops
    : NUMBER
    ;

maxHops
    : NUMBER
    ;

label
    : identifier
    ;

value
    : QUOTED_STRING | NUMBER | tuple  
    ;

comparator
    : '=' | 'IN' | '<>' | '!=' | '>' | '<'
    ;

logicalOp
    : 'AND' | 'OR'
    ;

alias
    : identifier
    ;

tuple
    : '(' value (',' value)* ')'
    ;

functionName
    : 'COUNT' | 'PERCENTILE' | 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COLLECT'
    ;

DISTINCT
    : 'DISTINCT'
    ;

identifier
    : ID | QUOTED_STRING
    ;

ID
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

QUOTED_STRING
    : ('"' ( ~["\\] | '\\' . )* '"')
    | ('\'' ( ~['\\] | '\\' . )* '\'')
    ;

NUMBER
    : [0-9]+ ('.' [0-9]+)?
    ;

WS
    : [ \t\r\n]+ -> skip
    ;
