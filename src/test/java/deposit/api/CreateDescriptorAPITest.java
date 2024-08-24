package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import deposit.service.BaseService;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CreateDescriptorAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String createNamespaceAndObject(Session session, String namespaceName, String className) {
		return session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));

			Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
								   Map.of("className", className, "token", token));
			String objectId = result.single().get("objectId").asString();

			tx.commit();

			return objectId;
		});
	}

	private void createDescriptor(Session session, String objectId, String descriptorName, Object value) {
		session.writeTransaction(tx -> {
			if (descriptorName.equals("PointDescriptor")) {
				tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, point({x: 40.785091, y: -73.968285, srid: 4326}), $token)",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "token", token));
			} else {
				tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token)",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			}
			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldCreateWKTDescriptor() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName = "GeometryDescriptor";
			List<Object> coordinates = List.of(12.9715987, 77.594566);

			String objectId = createNamespaceAndObject(session, namespaceName, className);

			Result result = session.run("""
							CALL deposit.utils.coordinatesToWKT('Point', $coordinates) YIELD value AS wkt
							CALL deposit.create.descriptor($objectId, $descriptorName, wkt, $token) YIELD node
							RETURN node.value AS storedValue
					""",
					Map.of("objectId", objectId, "descriptorName", descriptorName, "token", token, "coordinates", coordinates));

			String wkt = result.single().get("storedValue").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldCreateDescriptorAndClassDescriptorWithVariousValueTypes() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			String objectId = createNamespaceAndObject(session, namespaceName, className);

			Map<String, Object> valueTests = new HashMap<>();
			valueTests.put("PointDescriptor", "");
			valueTests.put("StringDescriptor", "StringValue");
			valueTests.put("IntegerDescriptor", 123);
			valueTests.put("LongDescriptor", 123L);
			valueTests.put("FloatDescriptor", 123.45f);
			valueTests.put("DoubleDescriptor", 123.45);
			valueTests.put("BooleanDescriptor", true);
			valueTests.put("LocalTimeDescriptor", LocalTime.now());
			valueTests.put("LocalDateDescriptor", LocalDate.now());
			valueTests.put("LocalDateTimeDescriptor", LocalDateTime.now());
			valueTests.put("ZonedDateTimeDescriptor", ZonedDateTime.now());
			valueTests.put("DurationDescriptor", Duration.ofHours(1));
			valueTests.put("StringListDescriptor", List.of("StringItem1", "StringItem2"));
			valueTests.put("IntegerListDescriptor", List.of(1, 2, 3));
			valueTests.put("BooleanListDescriptor", List.of(true, false, true));

			valueTests.forEach((descriptorName, value) -> {
				// Verify: Class Descriptor node does not exist before the operation
				session.writeTransaction(tx -> {
					Result result;
					result = tx.run(
							"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName}) RETURN d",
							Map.of("namespaceName", namespaceName, "className", className, "descriptorName", descriptorName)
					);
					assertTrue("Class Descriptor node should not exist yet", !result.hasNext());
					return null;
				});

				// Create each descriptor in a separate transaction
				createDescriptor(session, objectId, descriptorName, value);

				// Verify: Descriptor node was created
				session.writeTransaction(tx -> {
					Result result;
					if (descriptorName.equals("PointDescriptor")) {
						result = tx.run(
								"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName, value: point({x: 40.785091, y: -73.968285, srid: 4326})}) RETURN d",
								Map.of("namespaceName", namespaceName, "descriptorName", descriptorName)
						);
					} else {
						result = tx.run(
								"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName, value: $value}) RETURN d",
								Map.of("namespaceName", namespaceName, "descriptorName", descriptorName, "value", value)
						);
					}
					assertTrue("Descriptor node should exist with correct value", result.hasNext());
					return null;
				});

				// Verify: Class Descriptor node was created and matches the expected type
				session.writeTransaction(tx -> {
					Result result = tx.run(
							"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName}) RETURN d",
							Map.of("namespaceName", namespaceName, "className", className, "descriptorName", descriptorName)
					);
					assertTrue("Class Descriptor node should exist", result.hasNext());

					if (!descriptorName.equals("PointDescriptor")) {
						String valueTypeName = BaseService.getTypeName(value);
						String classDescriptorType = result.single().get("d").get("type").asString();
						assertTrue("Class Descriptor type should match the value type",
								classDescriptorType.equals(valueTypeName));
					}
					return null;
				});
			});
		}
	}

	@Test
	public void shouldCreateOrUpdateDescriptor() {
		try (Session session = driver.session()) {
			// Setup test data
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName = "TestDescriptor";
			String initialValue = "InitialValue";
			String updatedValue = "UpdatedValue";

			// Create Namespace and Object
			String objectId = createNamespaceAndObject(session, namespaceName, className);

			// Step 1: Create the Descriptor
			session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $initialValue, $token) YIELD node AS descriptor",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "initialValue", initialValue, "token", token));
				assertTrue("Descriptor node should be created", result.hasNext());

				String createdValue = result.single().get("descriptor").get("value").asString();
				assertEquals("Descriptor value should match the initial value", initialValue, createdValue);
				return null;
			});

			// Verify: Descriptor node was created
			session.writeTransaction(tx -> {
				Result result = tx.run(
						"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName}) RETURN d",
						Map.of("namespaceName", namespaceName, "objectId", objectId, "descriptorName", descriptorName)
				);
				assertTrue("Descriptor node should exist", result.hasNext());
				String descriptorValue = result.single().get("d").get("value").asString();
				assertEquals("Descriptor value should match the initial value", initialValue, descriptorValue);
				return null;
			});

			// Step 2: Update the Descriptor with a new value
			session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $updatedValue, $token) YIELD node AS descriptor",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "updatedValue", updatedValue, "token", token));
				assertTrue("Descriptor node should be updated", result.hasNext());

				String updatedDescriptorValue = result.single().get("descriptor").get("value").asString();
				assertEquals("Descriptor value should match the updated value", updatedValue, updatedDescriptorValue);
				return null;
			});

			// Verify: Descriptor node value was updated
			session.writeTransaction(tx -> {
				Result result = tx.run(
						"MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName}) RETURN d",
						Map.of("namespaceName", namespaceName, "objectId", objectId, "descriptorName", descriptorName)
				);
				assertTrue("Descriptor node should exist", result.hasNext());
				String descriptorValue = result.single().get("d").get("value").asString();
				assertEquals("Descriptor value should match the updated value", updatedValue, descriptorValue);
				return null;
			});
		}
	}
}
