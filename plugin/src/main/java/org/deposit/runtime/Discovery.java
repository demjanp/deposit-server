package org.deposit.runtime;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;

/** Core runtime discovery procedure; public metadata is server managed. */
public class Discovery {
    @org.neo4j.procedure.Context public org.neo4j.graphdb.GraphDatabaseService db;
    @Procedure(name = "deposit.get.info", mode = Mode.READ)
    @Description("Return the running Deposit version. No Deposit login is required.")
    public Stream<Info> info() {
        RuntimeReadiness.requireReady();
        var database=(org.neo4j.kernel.internal.GraphDatabaseAPI)db;
        var server=deposit.common.ServerContext.forDatabase(database.databaseId().databaseId().uuid());
        server.requireReady();
        return Stream.of(new Info(deposit.common.DepositInfo.version(),server.runId));
    }

    public static class Info {
        public final Map<String, Object> data;
        public Info(String version) {
            this(version,null);
        }
        Info(String version,String incarnation) {
            var capabilities=List.of("fullNamespaceClone","pagedImportResults","operationControl","exchangeIntake");
            data=incarnation==null?Map.of("version",version,"capabilities",capabilities)
                :Map.of("version",version,"capabilities",capabilities,"incarnation",incarnation);
        }
    }
}
