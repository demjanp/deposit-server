package deposit.service;

import deposit.repository.CreateUserRepository;
import deposit.common.ServerContext;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateUserService extends BaseService {

	private static CreateUserService instance;
	private final CreateUserRepository createUserRepository;
	private static PasswordEncoder passwordEncoder;

	private CreateUserService() {
		this.createUserRepository = CreateUserRepository.getInstance();
	}

	public static synchronized CreateUserService getInstance() {
		if (instance == null) {
			instance = new CreateUserService();
		}
		if (passwordEncoder == null) {
			passwordEncoder = new BCryptPasswordEncoder();
		}
		return instance;
	}

	public Node createUser(Transaction tx, String newUsername, String rawPassword, String token) {
		
		validateToken(token);
		if (newUsername.equals(ServerContext.getDefaultUsername())) {
			throwException("Username cannot be the same as the default Neo4j username: " + newUsername + ".");
		}
		if (newUsername.isEmpty() || rawPassword.isEmpty()) {
			throwException("Username and password cannot be empty strings.");
		}
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String currentUsername = ServerContext.getActiveUsername(token);
		String hashedPassword = passwordEncoder.encode(rawPassword);
		
		return createUserRepository.createUser(
			tx, timeStamp, currentUsername, newUsername, hashedPassword
		);
	}
}
