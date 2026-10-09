package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.ClientException;

/** Installed normal distribution must expose discovery without test-only procedures. */
class DistributionIT {
    @Test void normalDistributionExcludesProbePermissionsAndProcedures() {
        try(var driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")),
                Config.builder().withConnectionTimeout(5,TimeUnit.SECONDS).build());var session=driver.session()) {
            assertEquals("0.0.0-stage0",session.run("CYPHER 25 CALL deposit.get.info() YIELD data RETURN data.version AS v").single().get("v").asString());
            assertEquals(0,session.run("SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'stage0.' RETURN count(*) AS n").single().get("n").asInt());
            assertEquals("",session.run("SHOW SETTINGS 'dbms.security.procedures.unrestricted' YIELD value RETURN value").single().get("value").asString());
            var error=assertThrows(ClientException.class,()->session.run("CALL stage0.geometry.inspect('unused',null)").consume());
            assertEquals("Neo.ClientError.Procedure.ProcedureNotFound",error.code());
        }
    }
}
