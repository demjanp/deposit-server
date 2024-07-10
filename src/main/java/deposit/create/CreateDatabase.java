package deposit.create;

import deposit.core.*;
import org.neo4j.procedure.*;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import java.util.Collections;
import java.util.Map;

/**
 * This class contains procedures for creating a new database and adding metadata.
 */
public class CreateDatabase {
	
	@Context
	public org.neo4j.graphdb.GraphDatabaseService db;
	
	@Context
	public Log log;
	
	/**
	 * Creates a new database and adds a Metadata node with specified properties.
	 *
	 * @param databaseName the name of the database to create.
	 * @throws RuntimeException if the database name ends with '_archive' or if there is an error during creation.
	 */
	@Procedure(name = "deposit.create.database", mode = Mode.WRITE)
	@Description("CALL deposit.create.database(databaseName) - creates a new database")
	public void createDatabase(@Name("databaseName") String databaseName) {
		if (databaseName.endsWith("_archive")) {
			throw new RuntimeException("Database name cannot end with '_archive'");
		}
		
		try (Transaction tx = db.beginTx()) {
			String createDbQuery = String.format("CREATE DATABASE `%s`", databaseName);
			db.executeTransactionally(createDbQuery);
			
			String useDbQuery = String.format("USE `%s`", databaseName);
			db.executeTransactionally(useDbQuery);
			
			String depositVersion = DepositInfo.version;
			
			String createMetadataQuery = """
				CALL dbms.security.showCurrentUser() YIELD username
				WITH username
				CREATE (m:Metadata {
					depositVersion: $depositVersion,
					versioningType: null,
					createdBy: username,
					createdAt: datetime(),
					updatedAt: datetime(),
					description: ''
				})
			""";
			Map<String, Object> params = Collections.singletonMap("depositVersion", depositVersion);
			db.executeTransactionally(createMetadataQuery, params);
			
			tx.commit();
		} catch (Exception e) {
			log.error("Error creating database: " + databaseName, e);
			throw new RuntimeException("Failed to create database: " + e.getMessage(), e);
		}
	}
}
