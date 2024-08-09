package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Values;
import org.neo4j.driver.Value;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class CreateDescriptorAPITest extends BaseNeo4jTest {

    private String token;

    @Override
    protected Class<?>[] getProcedureClasses() {
        return new Class<?>[]{CreateDescriptorAPI.class, CreateNamespaceAPI.class, CreateObjectAPI.class, SetActiveNamespaceAPI.class};
    }

    @Before
    public void setUp() {
        super.setUp();
        token = ServerContext.getInstance().generateToken("testUser");
    }

    private String createNamespaceAndObject(Session session, String namespaceName, List<String> classes) {
        return session.writeTransaction(tx -> {
            tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
            ServerContext.setActiveNamespace(namespaceName, token);
            tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));

            Result result = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
                                   Map.of("classes", classes, "token", token));
            String objectId = result.single().get("objectId").asString();

            tx.commit();

            return objectId;
        });
    }

    private void createDescriptor(Session session, String objectId, String descriptorName, Object value) {
        session.writeTransaction(tx -> {
            tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token)",
                    Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
            tx.commit();
            return null;
        });
    }

    @Test
    public void shouldCreateDescriptorWithVariousValueTypes() {
        try (Session session = driver.session()) {
            String namespaceName = "TestNamespace";
            List<String> classes = List.of("TestClass");

            String objectId = createNamespaceAndObject(session, namespaceName, classes);

            // Create a Point using Neo4j's Values.point() method to ensure correct type
            Value point = Values.point(4326, 40.785091, -73.968285);

            Map<String, Object> valueTests = new HashMap<>();
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
            valueTests.put("PointDescriptor", point);
            valueTests.put("StringListDescriptor", List.of("StringItem1", "StringItem2"));
            valueTests.put("IntegerListDescriptor", List.of(1, 2, 3));
            valueTests.put("BooleanListDescriptor", List.of(true, false, true));

            valueTests.forEach((descriptorName, value) -> {
                createDescriptor(session, objectId, descriptorName, value);

                // Verify: Descriptor node was created
                Result result = session.run("MATCH (d:Descriptor {name: $descriptorName, value: $value}) RETURN d",
                                            Map.of("descriptorName", descriptorName, "value", value));
                assertTrue("Descriptor node should exist with correct value", result.hasNext());
            });
        }
    }
}
