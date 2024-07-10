package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.util.Collections;
import java.util.Map;
import java.util.List;

public class CreateRepository {

	private final GraphDatabaseService db;
	private final Log log;

	public CreateRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}

	public void createClassDescriptor(String className, String name, String type, Long order, String comment) {
        String createDescriptorQuery = """
            MATCH (a:Class {name: $className})
            MERGE (d:Descriptor {name: $name})
            MERGE (a)-[:HAS_DESCRIPTOR]->(d)
            SET d.type = $type
            SET d.order = $order
            SET d.comment = $comment
        """;

        Map<String, Object> params = Map.of(
                "className", className,
                "name", name,
                "type", type,
                "order", order,
                "comment", comment
        );

        try (Transaction tx = db.beginTx()) {
            tx.execute(createDescriptorQuery, params);
            tx.commit();
        } catch (Exception e) {
            log.error("Error setting descriptor for Class node: " + className, e);
            throw new RuntimeException("Failed to set descriptor for Class node: " + e.getMessage(), e);
        }
    }

	public Node createClass(String name, Long order, List<Double> position, String comment) {
		try (Transaction tx = db.beginTx()) {
			String createClassQuery = """
				MERGE (a:Class {name: $name})
				SET a.order = $order
				SET a.position = $position
				SET a.comment = $comment
				RETURN a
			""";

			Map<String, Object> params = Map.of(
					"name", name,
					"order", order,
					"position", position,
					"comment", comment
			);

			Result result = tx.execute(createClassQuery, params);
			tx.commit();

			if (result.hasNext()) {
				return (Node) result.next().get("a");
			} else {
				throw new RuntimeException("Failed to create Class node: No node returned");
			}
		} catch (Exception e) {
			log.error("Error creating Class node: " + name, e);
			throw new RuntimeException("Failed to create Class node: " + e.getMessage(), e);
		}
	}

	public void createDatabase(String databaseName) {
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
