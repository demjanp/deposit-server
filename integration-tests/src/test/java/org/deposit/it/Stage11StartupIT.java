package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Selected only while initialization is paused or has failed. */
@Timeout(30)
class Stage11StartupIT {
    @Test void failedInitializationRejectsAuthenticatedReadiness() {
        try(Driver driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")));
            var session=driver.session()) {
            var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->
                session.run("CYPHER 25 CALL deposit.get.info()",TransactionConfig.builder().withTimeout(Duration.ofSeconds(20)).build()).consume());
            String expected=System.getenv().getOrDefault("STAGE11_STARTUP_FAILURE","Deposit core initialization failed");
            assertTrue(failure.getMessage().contains(expected),failure.getMessage());
        }
    }
    @Test void incompleteInitializationExcludesReadinessAndDownloadListener()throws Exception {
        URI base=URI.create(System.getenv("STAGE0_HTTP_BASE"));
        HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for(String suffix:new String[]{"/login","/resources/00000000-0000-0000-0000-000000000000"}) {
            try {
                var response=http.send(HttpRequest.newBuilder(URI.create(base+suffix)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
                fail("Download listener served HTTP "+response.statusCode()+" before successful recovery");
            }catch(java.io.IOException expected) { /* Connection refusal or bounded transport timeout. */ }
        }
        Driver driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")),
            Config.builder().withConnectionTimeout(2,TimeUnit.SECONDS).withConnectionAcquisitionTimeout(2,TimeUnit.SECONDS).build());
        CompletableFuture<Boolean> ready=CompletableFuture.supplyAsync(()->{
            try(var session=driver.session()) {
                session.run("CYPHER 25 CALL deposit.get.info()",TransactionConfig.builder().withTimeout(Duration.ofSeconds(2)).build()).consume();
                return true;
            }catch(org.neo4j.driver.exceptions.Neo4jException expected){return false;}
        });
        try { assertFalse(ready.get(4,TimeUnit.SECONDS),"Deposit readiness succeeded before successful recovery"); }
        catch(TimeoutException expected) { /* Readiness remains blocked behind recovery. */ }
        finally { ready.cancel(true);driver.closeAsync(); }
    }
}
