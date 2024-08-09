package deposit.service;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.stream.Stream;

public class EnsureClassService extends BaseService {

	private static EnsureClassService instance;
	private final GetClassService getClassService;
	private final CreateClassService createClassService;

	private EnsureClassService() {
		this.getClassService = GetClassService.getInstance();
		this.createClassService = CreateClassService.getInstance();
	}

	public static synchronized EnsureClassService getInstance() {
		if (instance == null) {
			instance = new EnsureClassService();
		}
		return instance;
	}
	
	public void ensureClass(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String token) {
		Stream<Node> classStream = getClassService.getClass(tx, className, token);
		if (classStream.findAny().isEmpty()) {
			createClassService.createClass(
				tx, className, -1L, Collections.emptyList(), "", Collections.emptyList(), token
			);
		}
	}
}
