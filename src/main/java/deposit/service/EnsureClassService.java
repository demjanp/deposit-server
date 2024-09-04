package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.repository.CreateClassRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
		List<Node> classNodes = getClassRepository.getClass(tx, namespaceName, className).collect(Collectors.toList());
		
		if (classNodes.isEmpty()) {
			Stream<Node> classStream2 = getClassRepository.getClass(tx, namespaceName, "");
			Optional<Long> maxOrder = classStream2
				.map(node -> (Long) node.getProperty("order", 0L))
				.max(Long::compare);
			
			Long order = maxOrder.orElse(0L) + 1;
			createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, className, order, Collections.emptyList(), "");
		}
	}

}
