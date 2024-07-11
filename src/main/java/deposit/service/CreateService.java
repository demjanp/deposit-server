package deposit.service;

import deposit.api.CreateInterface.*;
import deposit.repository.CreateRepository;
import deposit.utils.ValidationUtils;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
import java.util.List;
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

	public void createClassDescriptor(String className, String descriptorName, String type, Long order, String comment) {
        ValidationUtils.validateDescriptorTypeName(type);
		String escapedClassName = className.replace("/", "\\/");
        String escapedDescriptorName = descriptorName.replace("/", "\\/");
        String name = escapedClassName + "/" + escapedDescriptorName;
		ZonedDateTime timeStamp = ZonedDateTime.now();

        createRepository.createClassDescriptor(timeStamp, className, name, type, order, comment);
    }

	public Stream<ClassNodeResult> createClass(
			String name, Long order, List<Double> position, String comment, List<List<String>> descriptors) {
		
		// Validate position
		position = ValidationUtils.validatePosition(position);

		// Validate descriptors
		ValidationUtils.validateClassDescriptors(descriptors);

		ZonedDateTime timeStamp = ZonedDateTime.now();

		// Delegate to repository
		Node classNode = createRepository.createClass(timeStamp, name, order, position, comment);

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

	public void createDatabase(String databaseName) {
		// Business rules validation
		if (databaseName.endsWith("_archive")) {
			throw new RuntimeException("Database name cannot end with '_archive'");
		}

		ZonedDateTime timeStamp = ZonedDateTime.now();

		// Delegate to repository
		createRepository.createDatabase(timeStamp, databaseName);
	}
}
