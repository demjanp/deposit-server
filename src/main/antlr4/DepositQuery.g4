grammar DepositQuery;
@header {
package deposit.service.query;
}

query
	: selectClause whereClause? orderByClause? EOF
	;

selectClause
	: 'SELECT' DISTINCT? selectItem (',' selectItem)*
	;

selectItem
	: aggregationFunction
	| identifier ('.' identifier)? ('AS' alias)?
	;

aggregationFunction
	: functionName '(' DISTINCT? identifier ('.' identifier)? (',' NUMBER)? ')' ('AS' alias)?
	;

whereClause
	: 'WHERE' logicalExpression
	;

orderByClause
	: 'ORDER' 'BY' orderItem (',' orderItem)*
	;

orderItem
	: identifier ('.' identifier)? orderDirection?
	;

orderDirection
	: 'ASC'
	| 'DESC'
	;

logicalExpression
	: logicalTerm (logicalOp logicalTerm)*
	;

logicalTerm
	: 'NOT' logicalTerm                        
	| '(' 'NOT'? logicalExpression ')'         
	| atomicCondition                         
	| '(' logicalExpression ')'               
	;

condition
	: atomicCondition
	| '(' logicalExpression ')'
	;

atomicCondition
	: identifier ('.' identifier)? comparator value  
	| GSRelationship
	;

GSRelationship
	: identifier ('<-' | '-') label? asterisk? (minHops? '..' maxHops?)? ('-' | '->') identifier
	;

asterisk
	: '*'
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
	: 'COUNT'
	| 'SUM'
	| 'AVG'
	| 'MIN'
	| 'MAX'
	| 'COLLECT'
	| 'STDEV'
	| 'PERCENTILE'
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
