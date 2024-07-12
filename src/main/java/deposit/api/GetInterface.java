package deposit.api;

import deposit.service.GetService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.stream.Stream;

public class GetInterface {

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    private GetService getService;

    public GetInterface(GraphDatabaseService db, Log log) {
        this.getService = new GetService(db, log);
    }

    public GetService getGetService() {
        return getService;
    }

    public void setGetService(GetService getService) {
        this.getService = getService;
    }

    /**
     * Retrieves the names of all databases.
     *
     * @return a stream of database names.
     */
    @Procedure(name = "deposit.get.databaseNames", mode = Mode.READ)
    @Description("CALL deposit.get.databaseNames() YIELD databaseName")
    public Stream<GetService.DatabaseNameResult> getDatabaseNames() {
        return getService.getDatabaseNames();
    }

    /**
     * Retrieves the version of Deposit.
     *
     * @return Deposit version as String.
     */
    @Procedure(name = "deposit.get.version", mode = Mode.READ)
    @Description("CALL deposit.get.version() YIELD version")
    public Stream<GetService.VersionResult> getVersion() {
        return getService.getVersion();
    }

    /**
     * Retrieves Class nodes based on the specified name. If no name is provided, all Class nodes are retrieved.
     *
     * @param name the name of the class to retrieve. If null or empty, all Class nodes are returned.
     * @return a stream of ClassNodeResult containing the retrieved Class nodes.
     * @throws RuntimeException if there is an error during retrieval.
     */
    @Procedure(name = "deposit.get.class", mode = Mode.READ)
    @Description("CALL deposit.get.class($name) YIELD node")
    public Stream<GetService.ClassNodeResult> getClass(@Name(value = "name", defaultValue = "") String name) {
        return getService.getClass(name);
    }
}
