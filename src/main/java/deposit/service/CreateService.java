package deposit.service;

import deposit.repository.CreateRepository;
import deposit.core.Validation;
import deposit.core.Versioning;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class CreateService {

	private CreateRepository createRepository;

	public CreateService(GraphDatabaseService db, Log log) {
		this.createRepository = new CreateRepository(db, log);
	}

    // Package-private setter for CreateRepository to be used in tests
    void setCreateRepository(CreateRepository createRepository) {
        this.createRepository = createRepository;
    }



	public void createDatabase(String databaseName) {
		// Business rules validation
		if (databaseName.endsWith("_archive")) {
			throw new RuntimeException("Database name cannot end with '_archive'");
		}

		ZonedDateTime timeStamp = ZonedDateTime.now();

		// Delegate to repository
		createRepository.createDatabase(timeStamp, databaseName);
	}

	public static class ClassNodeResult {
		public Node node;

		public ClassNodeResult(Node node) {
			this.node = node;
		}
	}

	public Stream<ClassNodeResult> createClass(
			String name, Long order, List<Double> position, String comment, List<List<String>> descriptors) {
		
		// Validate position
		position = Validation.validatePosition(position);

		// Validate descriptors
		Validation.validateClassDescriptors(descriptors);

		ZonedDateTime timeStamp = ZonedDateTime.now();

		// Delegate to repository
		Node classNode = createRepository.createClass(timeStamp, name, order, position, comment);

		Versioning.updateDatabase(timeStamp);

		// Create descriptors
		for (int i = 0; i < descriptors.size(); i++) {
			List<String> descriptor = descriptors.get(i);
			String descriptorName = descriptor.get(0);
			String type = descriptor.get(1);
			String descriptorComment = descriptor.size() > 2 ? descriptor.get(2) : "";
			Long descriptorOrder = (long) i; // Set the order based on the position in the list

			this.createClassDescriptor(name, descriptorName, type, descriptorOrder, descriptorComment);
		}

		return Stream.of(new ClassNodeResult(classNode));
	}

	public Node createObject(ZonedDateTime timeStamp, String objectName, String objectType, Map<String, Object> properties) {
		// Validate inputs
		// TODO: Add validation logic here

		// Delegate to repository
		Node objectNode = createRepository.createObject(timeStamp, objectName, objectType, properties);

		// Update database version
		Versioning.updateDatabase(timeStamp);

		return objectNode;
	}

	public void createDescriptor(ZonedDateTime timeStamp, String descriptorName, String descriptorType, Long order, String comment) {
		// Validate inputs
		// TODO: Add validation logic here

		// Delegate to repository
		createRepository.createDescriptor(timeStamp, descriptorName, descriptorType, order, comment);

		// Update database version
		Versioning.updateDatabase(timeStamp);
	}

	public void createClassDescriptor(String className, String descriptorName, String type, Long order, String comment) {
        Validation.validateDescriptorTypeName(type);
		String escapedClassName = className.replace("/", "\\/");
        String escapedDescriptorName = descriptorName.replace("/", "\\/");
        String name = escapedClassName + "/" + escapedDescriptorName;
		ZonedDateTime timeStamp = ZonedDateTime.now();

        createRepository.createClassDescriptor(timeStamp, className, name, type, order, comment);

		Versioning.updateDatabase(timeStamp);
    }

	public Relationship createRelationship(ZonedDateTime timeStamp, String startNode, String endNode, String relationshipType, Map<String, Object> properties) {
		// Validate inputs
		// TODO: Add validation logic here

		// Delegate to repository
		Relationship relationship = createRepository.createRelationship(timeStamp, startNode, endNode, relationshipType, properties);

		// Update database version
		Versioning.updateDatabase(timeStamp);

		return relationship;
	}

	public Relationship createClassRelationship(ZonedDateTime timeStamp, String startClass, String endClass, String relationshipType, Map<String, Object> properties) {
		// Validate inputs
		// TODO: Add validation logic here

		// Delegate to repository
		Relationship relationship = createRepository.createClassRelationship(timeStamp, startClass, endClass, relationshipType, properties);

		// Update database version
		Versioning.updateDatabase(timeStamp);

		return relationship;
	}

	public void createUserTool(ZonedDateTime timeStamp, String toolName, String toolType, String description, String author) {
		// Validate inputs
		// TODO: Add validation logic here

		// Delegate to repository
		createRepository.createUserTool(timeStamp, toolName, toolType, description, author);

		// Update database version
		Versioning.updateDatabase(timeStamp);
	}
}
