CREATE (updated:Class {id: randomUUID()})
SET	updated.name = $name,
	updated.order = $order,
	updated.position = $position,
	updated.comment = $comment,
	updated.createdAt = $timeStamp,
	updated.createdBy = $username,
	updated.updatedAt = $timeStamp,
	updated.updatedBy = $username
WITH updated
MERGE (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated)
SET namespace.updatedAt = $timeStamp, namespace.updatedBy = $username
WITH namespace, updated
MATCH (namespace)-[:CONTAINS]->(archive:Archive)
WITH archive, updated

// Check if archive has been restored and clean up if necessary
CALL {
	WITH archive, updated
	MATCH (archive)-[:CONTAINS]->(node)
	WHERE archive.restored IS NOT NULL AND node.updatedAt >= archive.restored
	OPTIONAL MATCH (node)-[*0..]->(subNode)
	DETACH DELETE node, subNode
	SET archive.restored = NULL
	RETURN count(*) AS count
}
WITH archive, updated

// Find the latest Version (or none)
OPTIONAL MATCH (latest:Version)-[:NEXT*0..]->(v:Version)
WHERE (archive)-[:CONTAINS]->(latest) AND NOT (v)-[:NEXT]->()
WITH archive, latest, updated

// Create the new Version node
CREATE (newVersionNode:Version {updatedAt: $timeStamp, updatedBy: $username, tag: NULL })
FOREACH (_ IN CASE WHEN latest IS NULL THEN [1] ELSE [] END |
	MERGE (archive)-[:CONTAINS]->(newVersionNode)
)
FOREACH (_ IN CASE WHEN latest IS NOT NULL THEN [1] ELSE [] END |
	MERGE (latest)-[:NEXT]->(newVersionNode)
)
WITH archive, updated

// Find the latest version of the node in the Archive
OPTIONAL MATCH (archive)-[:CONTAINS]->(prev {id: updated.id})
WITH prev, updated, archive ORDER BY prev.updatedAt DESC LIMIT 1
CREATE (archive)-[:CONTAINS]->(newVersion:Class)
SET newVersion += properties(updated),
	newVersion.deleted = false
// Proceed if a previous version exists
WITH prev, newVersion, updated
WHERE prev IS NOT NULL

// Collect all incoming and outgoing relationships from prev
OPTIONAL MATCH (prev)-[r]->(target)
WITH prev, newVersion, updated, COLLECT({r:r, target:target}) AS outgoingRels
OPTIONAL MATCH (source)-[r]->(prev)
WITH newVersion, updated, outgoingRels, COLLECT({r:r, source:source}) AS incomingRels

// Re-create the relationships
CALL {
	WITH newVersion, outgoingRels
	UNWIND outgoingRels AS rel
	CALL apoc.create.relationship(newVersion, type(rel.r), properties(rel.r), rel.target) YIELD rel AS newR
	RETURN count(*) AS count
}
WITH newVersion, updated, incomingRels
CALL {
	WITH newVersion, incomingRels
	UNWIND incomingRels AS rel
	CALL apoc.create.relationship(rel.source, type(rel.r), properties(rel.r), newVersion) YIELD rel AS newR
	RETURN count(*) AS count
}

RETURN updated