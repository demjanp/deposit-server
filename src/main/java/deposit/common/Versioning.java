package deposit.common;

import java.text.MessageFormat;

public class Versioning {
	
	/**
	 * Generates Cypher code to append to a query used to create or update a Deposit element to handle versioning.
	 * Assumes that the existing query ends with a specific statement depending on elementType:
	 *  Object / Class / UserTool: 'WITH namespace, updated'
	 *	Descriptor / ClassDescriptor: 'WITH namespace, parent, updated'
	 *	Membership / Relationship / ClassRelationship: 'WITH namespace, source, target, updated'
	 * Assumes that the following parameters are passed to the query:
	 *	$timeStamp: ZonedDateTime, the current timestamp.
	 *	$username: String, the username of the active user.
	 * Ends with a 'WITH updated' or 'WITH parent, updated' or 'WITH source, target, updated' statement so needs to be closed with a RETURN statement for the query to be valid.
	 *
	 * @param elementType type of Deposit element that is being updated. Defined as String. Possible values are Object, Class, Descriptor, ClassDescriptor, Membership, Relationship, ClassRelationship, UserTool.
	 * @param deletion set to true if the operation is a deletion. Defined as boolean.
	 * @return the query string to be appended to handle the versioning logic.
	 * @throws RuntimeException if an invalid element type is specified.
	 */
	public static String getElementUpdateCode(String elementType, Boolean deletion, String versioningType) {

		String updated = "";
		switch (elementType) {
			case "Object", "Class", "UserTool":
				updated = "updated";
				break;
			case "Descriptor", "ClassDescriptor":
				updated = "parent, updated";
				break;
			case "Membership", "Relationship", "ClassRelationship":
				updated = "source, target, updated";
				break;
			default:
				throw new RuntimeException("Invalid element type specified: " + elementType);
		}
		
		String deleted = deletion ? "true" : "false";

		// Update the Namespace node
		String queryString = """
			SET namespace.updatedAt = $timeStamp, namespace.updatedBy = $username
		""";
		
		if (!versioningType.isEmpty()) {
			// If versioning is enabled, update Archive
			queryString += """
				WITH namespace, {0}
				MATCH (namespace)-[:CONTAINS]->(archive:Archive)
				WITH archive, {0}
				
				// Check if archive has been restored and clean up if necessary
				CALL '{'
					WITH archive
					MATCH (archive)-[:CONTAINS]->(node)
					WHERE archive.restored IS NOT NULL AND node.updatedAt >= archive.restored
					OPTIONAL MATCH (node)-[*0..]->(subNode)
					DETACH DELETE node, subNode
					SET archive.restored = NULL
					RETURN count(*) AS count
				'}'
				WITH archive, {0}
				
				// Find the latest Version (or none)
				OPTIONAL MATCH (first:Version)-[:NEXT*0..]->(latest:Version)
				WHERE (archive)-[:CONTAINS]->(first) AND NOT (latest)-[:NEXT]->()
				WITH archive, latest, {0}

				// Create the new Version node
				CREATE (newVersionNode:Version '{'updatedAt: $timeStamp, updatedBy: $username, tag: NULL'}')
				FOREACH (_ IN CASE WHEN latest IS NULL THEN [1] ELSE [] END |
					MERGE (archive)-[:CONTAINS]->(newVersionNode)
				)
				FOREACH (_ IN CASE WHEN latest IS NOT NULL THEN [1] ELSE [] END |
					MERGE (latest)-[:NEXT]->(newVersionNode)
				)
				WITH archive, {0}
				
			""";
			
			// Find latest version of the updated node(s) in the archive
			switch (elementType) {
				case "Object", "Class", "UserTool":
					queryString += """
						
						// Find the latest version of the node in the Archive
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prev '{'id: updated.id'}')
						WITH prev, {0}, archive ORDER BY prev.updatedAt DESC LIMIT 1
					""";
					break;
				case "Descriptor", "ClassDescriptor":
					queryString += """
						
						// Find the latest parent
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevParent '{'id: parent.id'}')
						WITH prevParent, {0}, archive ORDER BY prevParent.updatedAt DESC LIMIT 1
					""";
					break;
				case "Relationship", "ClassRelationship", "Membership":
					queryString += """
						
						// Find the latest source
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevSource '{'id: source.id'}')
						WITH prevSource, {0}, archive ORDER BY prevSource.updatedAt DESC LIMIT 1
						
						// Find the latest target
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevTarget '{'id: target.id'}')
						WITH prevSource, prevTarget, {0}, archive ORDER BY prevTarget.updatedAt DESC LIMIT 1
					""";
					break;
			}
			
			// Create a new node in the Archive
			switch (elementType) {
				case "Object":
					queryString += """
						CREATE (archive)-[:CONTAINS]->(newVersion:Object)
					""";
					break;
				case "Class":
					queryString += """
						CREATE (archive)-[:CONTAINS]->(newVersion:Class)
					""";
					break;
				case "UserTool":
					queryString += """
						CREATE (archive)-[:CONTAINS]->(newVersion:UserTool)
					""";
					break;
				case "Descriptor":
				case "ClassDescriptor":
					queryString += """
						CREATE (newVersion:Descriptor)
						WITH prevParent, newVersion, {0}, archive
						CREATE (prevParent)-[:HAS_DESCRIPTOR]->(newVersion)
					""";
					break;
				case "Relationship":
				case "ClassRelationship":
					queryString += """
						CREATE (prevSource)-[:RS]->(newVersion:Relationship)-[:RE]->(prevTarget)
					""";
					break;
				case "Membership":
					queryString += """
						CREATE (prevSource)-[newVersion:MEMBER_OF]->(prevTarget)
					""";
					break;
			}
			
			// Copy properties from the updated node to the archive node
			queryString += """
				SET newVersion += properties(updated),
					newVersion.deleted = {1}
			""";
			
			if (elementType.equals("Object") || elementType.equals("Class")) {
				// Re-create relationships to the new version
				queryString += """
					WITH prev, newVersion, updated
					
					// Proceed if a previous version exists
					CALL '{'
						WITH prev, newVersion, updated
						WITH prev, newVersion, updated
						WHERE prev IS NOT NULL
						// Collect all incoming and outgoing relationships from prev
						OPTIONAL MATCH (prev)-[r]->(target)
						WITH prev, newVersion, updated, COLLECT('{'r:r, target:target'}') AS outgoingRels
						OPTIONAL MATCH (source)-[r]->(prev)
						WITH prev, newVersion, updated, outgoingRels, COLLECT('{'r:r, source:source'}') AS incomingRels
						// Re-create the relationships
						CALL '{'
							WITH newVersion, outgoingRels
							UNWIND outgoingRels AS rel
							WITH newVersion, rel WHERE rel.target IS NOT NULL
							CALL apoc.create.relationship(newVersion, type(rel.r), properties(rel.r), rel.target) YIELD rel AS newR
							RETURN count(*) AS count
						'}'
						WITH newVersion, updated, incomingRels
						CALL '{'
							WITH newVersion, incomingRels
							UNWIND incomingRels AS rel
							WITH newVersion, rel WHERE rel.source IS NOT NULL
							CALL apoc.create.relationship(rel.source, type(rel.r), properties(rel.r), newVersion) YIELD rel AS newR
							RETURN count(*) AS count
						'}'
						RETURN count(*) AS count
					'}'
				""";
			} else if (elementType.equals("Descriptor")) {
				// Re-create DResource node if it is present
				queryString += """
					WITH newVersion, {0}
					// If a DResource node is linked to updated, re-create it for the archived version
					CALL '{'
						WITH newVersion, updated
						OPTIONAL MATCH (updated)-[:HAS_RESOURCE]->(resource:DResource)
						WITH newVersion, updated, resource
						WHERE resource IS NOT NULL
						CREATE (newVersion)-[:HAS_RESOURCE]->(newResource:DResource)
						SET newResource += properties(resource)
						RETURN count(*) AS count
					'}'
				""";
			}
		}
		
		queryString += """
			WITH {0}
		""";
		
		queryString = MessageFormat.format(queryString, updated, deleted);

		return queryString;		
	}
	
	/**
	 * Generates Cypher code to append to a query manipulating the Namespace to create the Archive
	 * Assumes that the existing query ends with the statement: 'WITH namespace, archive'.
	 * Assumes that the following parameters are passed to the query:
	 *	$timeStamp: ZonedDateTime, the current timestamp.
	 *	$username: String, the username of the active user.
	 * Ends with a 'WITH namespace, archive' statement so needs to be closed with a RETURN statement for the query to be valid.
	 *
	 * @return the query string to be appended to handle the versioning logic.
	 */
	public static String getCreateArchiveCode(String versioningType) {
		
		String queryString = "";
		
		if (!versioningType.isEmpty()) {
			// Check if the archive subtree exists and if not, create it by cloning all elements from the Namespace and placing them under the Archive node
			// 1. If archive is empty:
			//	a. Check if archive is empty
			// 	b. Find all nodes and relationships linked to the namespace by CONTAINS, excluding the archive node
			// 	c. Clone the subgraph
			// 	d. Attach the cloned nodes to the archive
			// 2. Create a new Version node
			queryString += """
				CALL {// 1
					WITH namespace, archive
					OPTIONAL MATCH (archive)-[:CONTAINS]->(node) // 1a
					WITH namespace, archive, node
					MATCH (namespace)-[:CONTAINS]->(n) // 1b
					WHERE node IS NULL AND n <> archive
					WITH namespace, archive, collect(n) AS nodesToClone
					CALL apoc.refactor.cloneSubgraph(nodesToClone, []) YIELD input, output // 1c
					WITH namespace, archive, output // 1d
					MERGE (archive)-[:CONTAINS]->(output)
					SET output.deleted = false
					WITH namespace, archive
					RETURN count(*) as _
				}
				WITH namespace, archive // 2
				CREATE (archive)-[:CONTAINS]->(version:Version {updatedAt: $timeStamp, updatedBy: $username, tag: NULL })
				WITH namespace, archive
			""";
		}
		
		return queryString;
	}
	
	/**
	 * Generates Cypher code to append to a query manipulating the Namespace to clear the Archive
	 * Assumes that the existing query ends with the statement: 'WITH namespace, archive'.
	 * Ends with a 'WITH namespace, archive' statement so needs to be closed with a RETURN statement for the query to be valid.
	 *
	 * @return the query string to be appended to handle the versioning logic.
	 */
	public static String getClearArchiveCode() {
		// Delete the archive subtree, leaving only the Archive node.
		return """
			CALL {
				WITH namespace, archive
				OPTIONAL MATCH (archive)-[:CONTAINS]->(node)
				WITH namespace, archive, node
				OPTIONAL MATCH (node)-[*0..]->(subNode)
				WITH namespace, archive, node, subNode
				DETACH DELETE node
				WITH namespace, archive, subNode
				DETACH DELETE subNode
				WITH namespace, archive
				RETURN count(*) as _
			}
			WITH namespace, archive			
		""";
	}

}
