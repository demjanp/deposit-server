package deposit.service;

import deposit.repository.UtilsLoginRepository;
import deposit.common.ServerContext;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class UtilsLoginService extends BaseService {

	private static UtilsLoginService instance;
	private final UtilsLoginRepository utilsLoginRepository;
	private static PasswordEncoder passwordEncoder;

	private UtilsLoginService() {
		this.utilsLoginRepository = UtilsLoginRepository.getInstance();
		initialize();
	}

	public static synchronized UtilsLoginService getInstance() {
		if (instance == null) {
			instance = new UtilsLoginService();
			instance.initialize();
		}
		return instance;
	}
	
	public synchronized void initialize() {
		if (passwordEncoder == null) {
			passwordEncoder = new BCryptPasswordEncoder();
		}
	}
	
	public String utilsLogin(Transaction tx, String username, String password) {

		if (username.isEmpty() && password.isEmpty()) {
			return ServerContext.generateToken(ServerContext.getDefaultUsername());
		}

		String storedHash = utilsLoginRepository.utilsLogin(tx, username);
		if (passwordEncoder.matches(password, storedHash)) {
			return ServerContext.generateToken(username);
		} else {
			throwException("Could not authenticate username: " + username + ".");
		}
		return "";
	}
}
