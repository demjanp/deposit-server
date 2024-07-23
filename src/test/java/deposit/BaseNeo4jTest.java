package deposit;

import org.junit.After;
import org.junit.Before;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;
import org.neo4j.harness.Neo4jBuilders;
import apoc.refactor.GraphRefactoring;
import apoc.create.Create;
import apoc.util.Util;
import org.neo4j.server.security.auth.AuthProcedures;

public abstract class BaseNeo4jTest {

    protected Neo4j embeddedDatabaseServer;
    protected Driver driver;

    @Before
    public void setUp() {
        Neo4jBuilder builder = Neo4jBuilders.newInProcessBuilder();
        for (Class<?> procedureClass : getProcedureClasses()) {
            builder = builder.withProcedure(procedureClass);
        }
        builder = builder.withProcedure(GraphRefactoring.class);
        builder = builder.withProcedure(Util.class);
        builder = builder.withProcedure(Create.class);
        builder = builder.withProcedure(AuthProcedures.class);
        embeddedDatabaseServer = builder.build();
        driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI(), AuthTokens.none());
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.close();
        }
        embeddedDatabaseServer.close();
    }

    protected abstract Class<?>[] getProcedureClasses();
}
