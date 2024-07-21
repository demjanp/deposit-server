package deposit.common;

import java.text.MessageFormat;

public class Versioning {
	
	/**
	 * Generates Cypher code to append to a query used to create or update a Deposit element to handle versioning.
	 * Assumes that the existing query provides the namespace node and further elements depending on elementType.
	 * Assumes that the following parameters are passed to the query:
	 *	$timeStamp: ZonedDateTime, the current timestamp.
	 *	$username: String, the username of the active user.
	 *
	 * @param elementType type of Deposit element that is being updated. Defined as String. Possible values are Object, Class, Descriptor, ClassDescriptor, Membership, Relationship, ClassRelationship.
	 * @param deletion set to true if the operation is a deletion. Defined as boolean.
	 * @return the query string to be appended to handle the versioning logic.
	 * @throws RuntimeException if an invalid element type is specified.
	 */
	public static String getElementUpdateCode(String elementType, Boolean deletion) {
		
		String versioningType = SessionContext.getVersioningType();
		
		String updated = "";
		switch (elementType) {
			case "Object", "Class":
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
			WITH namespace, {0}
			SET namespace.updatedAt = $timeStamp, namespace.updatedBy = $username
		""";
		
		if (!versioningType.isEmpty()) {
			// If versioning is enabled, update Archive
			queryString += """
				WITH namespace, {0}
				MATCH (namespace)-[:CONTAINS]->(archive:Archive)
				
				// Check if archive has been restored and clean up if necessary
				CALL '{'
					WITH archive, {0}
					WHERE archive.restored IS NOT NULL
					OPTIONAL MATCH (archive)-[:CONTAINS]->(node)
					WHERE node.updatedAt >= archive.restored
					WITH archive, {0}, node
					OPTIONAL MATCH (node)-[:CONTAINS*0..]->(subNode)
					WITH archive, {0}, node, subNode
					WHERE node IS NOT NULL
					DETACH DELETE node
					WITH archive, {0}, subNode
					WHERE subNode IS NOT NULL
					DETACH DELETE subNode
					WITH archive, {0}
					SET archive.restored = NULL
					RETURN count(*) as _
				'}'
				WITH archive, {0}
				
				// Find the latest Version (or none)
				OPTIONAL MATCH (latest:Version)-[:NEXT*0..]->(v:Version)
				WHERE (archive)-[:CONTAINS]->(latest) AND NOT (v)-[:NEXT]->()
				WITH archive, latest, {0}
				
				// Create the new Version node
				CREATE (newVersionNode:Version '{'updatedAt: $timeStamp, updatedBy: $username, tag: NULL '}')
				CALL '{'
					WITH archive, latest, newVersionNode, {0}
					WHERE latest IS NULL
					MERGE (archive)-[:CONTAINS]->(newVersionNode)
					RETURN count(*) as _
				'}'
				CALL '{'
					WITH archive, latest, newVersionNode, {0}
					WHERE latest IS NOT NULL
					MERGE (latest)-[:NEXT]->(newVersionNode)
					RETURN count(*) as _
				'}'
				WITH archive, {0}
			""";
			
			// Find latest version of the updated node(s) in the archive
			switch (elementType) {
				case "Object", "Class":
					queryString += """
						
						// Find the latest version of the node in the Archive
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prev '{'id: updated.id'}')
						WITH prev, {0}, archive ORDER BY prev.updatedAt DESC LIMIT 1
					""";
					break;
				case "Descriptor", "ClassDescriptor":
					queryString += """
						
						// Find the latest parent
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevParent: '{'id: parent.id'}')
						WITH prevParent, {0}, archive ORDER BY prevParent.updatedAt DESC LIMIT 1
					""";
					break;
				case "Relationship", "ClassRelationship", "Membership":
					queryString += """
						
						// Find the latest source
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevSource: '{'id: source.id'}')
						WITH prevSource, {0}, archive ORDER BY prevSource.updatedAt DESC LIMIT 1
						
						// Find the latest target
						OPTIONAL MATCH (archive)-[:CONTAINS]->(prevTarget: '{'id: target.id'}')
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
					// Proceed if a previous version exists
					WITH prev, newVersion WHERE prev IS NOT NULL
					
					// Collect all incoming and outgoing relationships from prev
					OPTIONAL MATCH (prev)-[r]->(target)
					WITH prev, newVersion, COLLECT('{'r:r, target:target'}') as outgoingRels
					OPTIONAL MATCH (source)-[r]->(prev)
					WITH prev, newVersion, outgoingRels, COLLECT('{'r:r, source:source'}') as incomingRels
					
					// Re-create the relationships
					UNWIND outgoingRels as rel
						CREATE (newVersion)-[newR:TYPE(rel.r)]->(rel.target)
						SET newR += properties(rel.r)
					UNWIND incomingRels as rel
						CREATE (rel.source)-[newR:TYPE(rel.r)]->(newVersion)
						SET newR += properties(rel.r)
				""";
			}
		}
		
		queryString += """
			RETURN {0}
		""";
		
		queryString = MessageFormat.format(queryString, updated, deleted);
		
		return queryString;
	}
	
	/**
	 * Generates Cypher code to append to a query used to match a Namespace when setting the active Namespace to handle versioning.
	 * Assumes that the existing query provides the namespace node.
	 * Assumes that the following parameters are passed to the query:
	 *	$timeStamp: ZonedDateTime, the current timestamp.
	 *	$username: String, the username of the active user.
	 *
	 * @return the query string to be appended to handle the versioning logic.
	 */
	public static String getNamespaceUpdateCode(String versioningType) {
		
		String queryString = """
			MERGE (namespace)-[:CONTAINS]->(archive:Archive)
			WITH namespace, archive
		""";
		
		if (versioningType.equals("transient")) {
			// Delete the archive subtree, leaving only the Archive node.
			// TODO this should only be called with setActiveNamespace, not setVersioningType
			queryString += """
				CALL {
					WITH namespace, archive
					OPTIONAL MATCH (archive)-[:CONTAINS]->(node)
					WITH namespace, archive, node
					OPTIONAL MATCH (node)-[:CONTAINS*0..]->(subNode)
					WITH namespace, archive, node, subNode
					WHERE node IS NOT NULL
					DETACH DELETE node
					WITH namespace, archive, subNode
					WHERE subNode IS NOT NULL
					DETACH DELETE subNode
					WITH namespace, archive
					RETURN count(*) as _
				}
				WITH namespace, archive
			""";
		}

		if (!versioningType.isEmpty()) {
			// Check if the archive subtree exists and if not, create it by cloning all elements from the Namespace and placing them under the Archive node
			// TODO this should also be called with setVersioningType
			queryString += """
				CALL {
					WITH namespace, archive
					
					// If archive is empty
					OPTIONAL MATCH (archive)-[:CONTAINS]->(node)
					WITH namespace, archive, node
					WHERE node IS NULL
					
					// Find all nodes and relationships linked to the namespace by CONTAINS, excluding the archive node
					MATCH (namespace)-[:CONTAINS]->(n)
					WHERE n <> archive
					WITH namespace, archive, collect(n) AS nodesToClone
					
					// Clone the subgraph
					CALL apoc.refactor.cloneSubgraph(nodesToClone, []) YIELD input, output
					
					// Attach the cloned nodes to the archive
					WITH namespace, archive, output
					MERGE (archive)-[:CONTAINS]->(output)
					SET output.deleted = false
					WITH namespace, archive
					RETURN count(*) as _
				}
				WITH namespace, archive
				
				// Create a new Version node
				WITH namespace, archive
				CREATE (archive)-[:CONTAINS]->(version:Version {updatedAt: $timeStamp, updatedBy: $username, tag: NULL })
				WITH namespace
			""";
		}

		queryString += """
			RETURN namespace
		""";

		return queryString;
	}
}
