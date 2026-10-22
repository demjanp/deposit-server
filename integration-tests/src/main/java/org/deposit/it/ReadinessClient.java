package org.deposit.it;

import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.TransactionConfig;
import java.time.Duration;

/** Process readiness requires authenticated discovery, not merely a listening port. */
public final class ReadinessClient {
    public static void main(String[] args) {
        var config=Config.builder().withConnectionTimeout(5,TimeUnit.SECONDS)
            .withConnectionAcquisitionTimeout(10,TimeUnit.SECONDS).build();
        try(var driver=GraphDatabase.driver(args[0],AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")),config);
            var session=driver.session()) {
            var info=session.run("CYPHER 25 CALL deposit.get.info() YIELD data RETURN data.version AS version",
                TransactionConfig.builder().withTimeout(Duration.ofSeconds(60)).build()).single();
            if(!"0.0.0-dev".equals(info.get("version").asString())) throw new IllegalStateException("Unexpected Deposit version");
            System.out.println("Authenticated Deposit discovery ready: " + info.get("version").asString());
        } catch(Exception failure) {
            System.err.println("Deposit readiness rejected: " + failure.getMessage());
            System.exit(1);
        }
    }
}
