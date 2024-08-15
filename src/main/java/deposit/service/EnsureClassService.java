package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.repository.CreateClassRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.stream.Stream;

public class EnsureClassService extends BaseService {

	private static EnsureClassService instance;
	private final GetClassRepository getClassRepository;
	private final CreateClassRepository createClassRepository;

	private EnsureClassService() {
		this.getClassRepository = GetClassRepository.getInstance();
		this.createClassRepository = CreateClassRepository.getInstance();
	}

	public static synchronized EnsureClassService getInstance() {
		if (instance == null) {
			instance = new EnsureClassService();
		}
		return instance;
	}
	
	public void ensureClass(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className) {
		Stream<Node> classStream = getClassRepository.getClass(tx, namespaceName, className);
		if (classStream.findAny().isEmpty()) {
			createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, className, -1L, Collections.emptyList(), "");
		}
	}
}
