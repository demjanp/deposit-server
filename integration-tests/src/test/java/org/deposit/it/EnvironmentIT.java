package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ClientException;

/** Runtime assertions independent of Deposit implementation and Spatial helpers. */
@Timeout(60)
class EnvironmentIT {
    static final Config CONFIG=Config.builder().withConnectionTimeout(5,TimeUnit.SECONDS)
        .withConnectionAcquisitionTimeout(10,TimeUnit.SECONDS).build();
    static final TransactionConfig TX=TransactionConfig.builder().withTimeout(Duration.ofSeconds(15)).build();
    static Driver connect(String uri,String password) {
        return GraphDatabase.driver(uri,AuthTokens.basic("neo4j",password),CONFIG);
    }
    static Driver primary() {return connect(System.getenv("STAGE0_URI"),System.getenv("STAGE0_PASSWORD"));}
    static List<org.neo4j.driver.Record> query(Driver driver,String cypher,Map<String,Object> parameters) {
        try(var session=driver.session()) {return session.run("CYPHER 25 "+cypher,parameters,TX).list();}
    }
    static List<org.neo4j.driver.Record> query(Driver driver,String cypher) {return query(driver,cypher,Map.of());}

    @Test void nativeRuntimeAndApoc() {
        try(var driver=primary()) {
            assertEquals(System.getenv("STAGE0_NEO4J_VERSION"),query(driver,"CALL dbms.components() YIELD versions RETURN versions[0] AS v").getFirst().get("v").asString());
            assertEquals(System.getenv("STAGE0_NEO4J_VERSION"),query(driver,"RETURN apoc.version() AS v").getFirst().get("v").asString());
            assertEquals("CYPHER_25",query(driver,"SHOW SETTINGS 'db.query.default_language' YIELD value RETURN value").getFirst().get("value").asString());
            for(String setting:List.of("server.http.enabled","server.https.enabled")) {
                assertEquals("false",query(driver,"SHOW SETTINGS $name YIELD value RETURN value",Map.of("name",setting)).getFirst().get("value").asString());
            }
        }
    }

    @Test void rejectsUnavailableDependenciesAndConfiguration() {
        String expected=Objects.requireNonNull(System.getenv("STAGE0_EXPECTED_FAILURE"));
        try(var driver=primary()) {
            assertEquals(1,query(driver,"RETURN 1 AS n").getFirst().get("n").asInt());
            var error=assertThrows(ClientException.class,()->query(driver,"CALL deposit.get.info()"));
            switch(expected) {
                case "incompatible-spatial" -> assertTrue(error.getMessage().contains("cannot inherit from final class"),error.getMessage());
                case "missing-spatial" -> assertTrue(error.getMessage().contains("ClassNotFoundException"),error.getMessage());
                case "invalid-deposit-config" -> assertTrue(error.getMessage().contains("Invalid Deposit configuration keys"),error.getMessage());
                default -> fail("Unknown failure fixture: "+expected);
            }
        }
    }

    @Test void independentClientSessions() {
        try(var driver=primary();var first=driver.session();var second=driver.session()) {
            try(var tx=first.beginTransaction(TX)) {
                tx.run("CYPHER 25 CREATE (:Stage0SessionIsolation {namespace:'A',name:'pending'})").consume();
                assertEquals(0,second.run("CYPHER 25 MATCH (n:Stage0SessionIsolation) RETURN count(n) AS n",Map.of(),TX).single().get("n").asInt());
                tx.commit();
            }
            assertEquals(1,second.run("CYPHER 25 MATCH (n:Stage0SessionIsolation {namespace:'A'}) RETURN count(n) AS n",Map.of(),TX).single().get("n").asInt());
            try(var tx=first.beginTransaction(TX)) {
                tx.run("CYPHER 25 CREATE (:Stage0SessionIsolation {namespace:'B',name:'rolled-back'})").consume();
                tx.rollback();
            }
            assertEquals(0,second.run("CYPHER 25 MATCH (n:Stage0SessionIsolation {namespace:'B'}) RETURN count(n) AS n",Map.of(),TX).single().get("n").asInt());
        } finally {
            try(var driver=primary()) {query(driver,"MATCH (n:Stage0SessionIsolation) DELETE n");}
        }
    }
}
