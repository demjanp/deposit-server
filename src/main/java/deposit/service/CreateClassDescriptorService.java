package deposit.service;

import deposit.repository.CreateClassDescriptorRepository;
import deposit.common.SessionContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateClassDescriptorService extends BaseService {

	private static CreateClassDescriptorService instance;
	private final CreateClassDescriptorRepository createClassDescriptorRepository;

	private CreateClassDescriptorService() {
		this.createClassDescriptorRepository = CreateClassDescriptorRepository.getInstance();
	}

	public static synchronized CreateClassDescriptorService getInstance() {
		if (instance == null) {
			instance = new CreateClassDescriptorService();
		}
		return instance;
	}

	public Node createClassDescriptor(Transaction tx, String className, String name, String type, Long order, String comment) {
		
		validateName(className);
		validateName(name);
		validateDescriptorTypeName(type);
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = SessionContext.getActiveUsername();
		String namespaceName = SessionContext.getActiveNamespace();
		if (namespaceName == null) {
			throwException("Cannot create Class Descriptor without an active Namespace.");
		}
		return createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, className, name, type, order, comment);
	}
}
