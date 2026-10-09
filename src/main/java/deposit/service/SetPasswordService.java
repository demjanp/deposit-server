package deposit.service;

import deposit.repository.SetPasswordRepository;
import deposit.common.ServerContext;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetPasswordService extends BaseService {

	private static SetPasswordService instance;
	private final SetPasswordRepository setPasswordRepository;
	private final PasswordEncoder passwordEncoder;

	private SetPasswordService() {
		this.setPasswordRepository = SetPasswordRepository.getInstance();
		this.passwordEncoder = new BCryptPasswordEncoder();
	}

	public static synchronized SetPasswordService getInstance() {
		if (instance == null) {
			instance = new SetPasswordService();
		}
		return instance;
	}

	public void setPassword(Transaction tx, String updatedUsername, String rawPassword, String token) {
		String defaultUsername = ServerContext.getDefaultUsername();
		System.out.println("DEFAULT USER: " + defaultUsername); // DEBUG
		if (updatedUsername.equals(defaultUsername)) {
			throwException("Cannot change password for the default Neo4j username: " + defaultUsername);
		}
		validateToken(token);
		if (updatedUsername.isEmpty() || rawPassword.isEmpty()) {
			throwException("Username and password cannot be empty strings.");
		}
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String currentUsername = ServerContext.getActiveUsername(token);
		String hashedPassword = passwordEncoder.encode(rawPassword);
		setPasswordRepository.setPassword(tx, timeStamp, currentUsername, updatedUsername, hashedPassword);
	}
}
