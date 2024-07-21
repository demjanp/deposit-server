package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SampleTest extends BaseNeo4jTest {

    @Override
    protected Class<?>[] getProcedureClasses() {
        return new Class<?>[]{};
    }

    @Test
    public void shouldCloneNode() {
        try (Session session = driver.session()) {

            String queryStr = """
                    // Step 1: Create a sample Person node
                    CREATE (p:Person {name: 'Alice', age: 30, city: 'London'})
                    // Step 2: Define additional information and merge it using apoc.map.merge
                    WITH p, {country: 'UK', profession: 'Engineer'} AS additionalInfo
                    SET p += apoc.map.merge(properties(p), additionalInfo)
                    RETURN p;
                    """;

            // Create a sample node
            session.writeTransaction(tx -> {
                tx.run(queryStr);
                tx.commit();
                return null;
            });
        }
    }
}
