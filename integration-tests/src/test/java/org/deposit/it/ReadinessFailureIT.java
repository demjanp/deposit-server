package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.ClientException;

/** Negative gate for the pinned, experimentally incompatible Spatial candidate. */
class ReadinessFailureIT {
    @Test void rejectsIncompatibleSpatialBeforeReportingReady() {
        try(var driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),
            AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")),
            Config.builder().withConnectionTimeout(5,TimeUnit.SECONDS).build());var session=driver.session()) {
            assertEquals(1,session.run("RETURN 1 AS value").single().get("value").asInt());
            var failure=assertThrows(ClientException.class,()->session.run("CALL deposit.get.info()").consume());
            assertTrue(failure.getMessage().contains("IncompatibleClassChangeError"));
            assertTrue(failure.getMessage().contains("cannot inherit from final class"));
        }
    }
}
