package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class SetClassOrderService extends BaseService {

	private static SetClassOrderService instance;
	private final EnsureClassOrderService ensureClassOrderService;

	private SetClassOrderService() {
		this.ensureClassOrderService = EnsureClassOrderService.getInstance();
	}

	public static synchronized SetClassOrderService getInstance() {
		if (instance == null) {
			instance = new SetClassOrderService();
		}
		return instance;
	}

	public void setClassOrder(Transaction tx, String className, Long order, String token) {

		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class order without an active Namespace.");
		}
		
		ensureClassOrderService.ensureClassOrder(tx, timeStamp, username, namespaceName, versioningType, className, order);
	}

}
