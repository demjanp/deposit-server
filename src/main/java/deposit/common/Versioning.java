package deposit.common;

import java.text.MessageFormat;

public class Versioning {
	
	/**
	 * Generates Cypher code to append to a query used to create or update a Deposit element to handle versioning.
	 * Assumes that the following parameters are passed to the query:
	 *	$namespaceId: String, the id property of the active Namespace.
	 *	$timeStamp: ZonedDateTime, the current timestamp.
	 *	$username: String, the username of the active user.
	 *
	 * @param elementType type of Deposit element that is being updated. Defined as String. Possible values are Object, Class, Descriptor, ClassDescriptor, Membership, Relationship, ClassRelationship.
	 * @param versioningType type of versioning. Defined as String. Possible values are transient, permanent or an empty string.
	 * @param deletion set to true if the operation is a deletion. Defined as boolean.
	 * @return the query string to be appended to handle the versioning logic.
	 * @throws RuntimeException if an invalid element type is specified.
	 */
	public static String getVersioningCode(String elementType, String versioningType, Boolean deletion) {
		
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
		};
		
		String deleted = "false";
		if (deletion) {
			deleted = "true";
		};

		// Update the Namespace node
		String queryString = """
			WITH {0}
			MATCH (namespace:Namespace '{'id: $namespaceId'}')
			SET namespace.updatedAt = $timeStamp, namespace.updatedBy = $username
		""";
		
		if (!versioningType.isEmpty()) {
			// If versioning is enabled, update Archive
			queryString += """
				WITH namespace, {0}
				MATCH (namespace)-[:CONTAINS]->(archive:Archive)
				
				// Check if archive has been restored
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
				
				// Find the latest Version (or none)
				OPTIONAL MATCH (latest:Version)-[:NEXT*0..]->(v:Version)
				WHERE (archive)-[:CONTAINS]->(latest) AND NOT (v)-[:NEXT]->()
				WITH archive, latest, {0}
				
				// Create the new Version node
				CREATE (new:Version '{'updatedAt: $timeStamp, updatedBy: $username, tag: NULL '}')
				WITH archive, latest, new, {0}
				WHERE latest IS NULL
				MERGE (archive)-[:CONTAINS]->(new)
				WITH archive, latest, new, {0}
				WHERE latest IS NOT NULL
				MERGE (latest)-[:NEXT]->(new)
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
			};
			
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
			};
			
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
		
		queryString = MessageFormat.format(queryString, updated, deleted);
		
		return queryString;
	}
}
