package deposit.service;

import deposit.repository.GetObjectClassDescriptorRepository;
import deposit.repository.GetObjectClassRepository;
import deposit.repository.CreateClassDescriptorRepository;
import deposit.repository.CreateDescriptorRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class EnsureDescriptorService extends BaseService {

	private static EnsureDescriptorService instance;
	private final GetObjectClassDescriptorRepository getObjectClassDescriptorRepository;
	private final GetObjectClassRepository getObjectClassRepository;
	private final CreateClassDescriptorRepository createClassDescriptorRepository;
	private final CreateDescriptorRepository createDescriptorRepository;

	private EnsureDescriptorService() {
		this.getObjectClassDescriptorRepository = GetObjectClassDescriptorRepository.getInstance();
		this.getObjectClassRepository = GetObjectClassRepository.getInstance();
		this.createClassDescriptorRepository = CreateClassDescriptorRepository.getInstance();
		this.createDescriptorRepository = CreateDescriptorRepository.getInstance();
	}

	public static synchronized EnsureDescriptorService getInstance() {
		if (instance == null) {
			instance = new EnsureDescriptorService();
		}
		return instance;
	}

	public Node ensureDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId, String descriptorName, Object value, Boolean update) {
		Node classDescriptorNode = getObjectClassDescriptorRepository.getObjectClassDescriptor(tx, namespaceName, objectId, descriptorName);
		String valueTypeName = getTypeName(value);
		if (classDescriptorNode == null) {
			// If a Class Descriptor doesn't exist, create it
			Node classNode = getObjectClassRepository.getObjectClass(tx, namespaceName, objectId);
			String className = String.valueOf(classNode.getProperty("name"));
			createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, valueTypeName, (long) -1, "");
		} else {
			// Check if the type of the value matches the type of the Class Descriptor
			String type = String.valueOf(classDescriptorNode.getProperty("type"));
			if (!type.equals(valueTypeName)) {
				throwException("The type of the value (" + valueTypeName + ") does not match the type of the Class Descriptor (" + type + ").");
			}
		}
		
		return createDescriptorRepository.createDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value, update);
	}
}
