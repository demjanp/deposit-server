package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.schema.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Exact prepared-upload selector: online composite index, seek plan and receipt semantics. */
class SchemaPendingActionIndexTest {
    private static final String INDEX="deposit_prepared_upload_action";
    private static final String UPLOAD="deposit.resource.uploadChunk";
    private static final String FINISH="deposit.resource.finishUpload";
    private static final String ABORT="deposit.resource.abortUpload";
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig;
    private final String actor="actor",otherActor="other-actor",scope="scope",otherScope="other-scope",upload="upload",otherUpload="other-upload";

    @BeforeEach void setUp() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path resources=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports"));
        Path config=temporary.resolve("deposit-test.properties");var properties=new Properties();
        properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"prepared action index");}
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);
        server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        try(var tx=database.beginTx()){
            tx.schema().awaitIndexOnline(INDEX,1,TimeUnit.MINUTES);
            IndexDefinition index=tx.schema().getIndexByName(INDEX);
            assertEquals(Schema.IndexState.ONLINE,tx.schema().getIndexState(index));
            assertIterableEquals(List.of(Label.label("DepositPreparedAction")),index.getLabels());
            assertIterableEquals(List.of("actorId","scope","resourceUploadId"),index.getPropertyKeys());
            assertTrue(index.isNodeIndex());
            assertTrue(index.isCompositeIndex());
            assertFalse(index.isConstraintIndex());
            assertEquals(IndexType.RANGE,index.getIndexType());
            var matchingIndexes=StreamSupport.stream(tx.schema().getIndexes(Label.label("DepositPreparedAction")).spliterator(),false)
                    .filter(candidate->candidate.getIndexType()==IndexType.RANGE
                            && List.of("actorId","scope","resourceUploadId").equals(
                                    StreamSupport.stream(candidate.getPropertyKeys().spliterator(),false).toList()))
                    .map(IndexDefinition::getName).toList();
            assertEquals(List.of(INDEX),matchingIndexes);
            tx.rollback();
        }
    }
    @AfterEach void tearDown() {
        try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally{try{if(server!=null)server.shutdown();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }
    @Test void exactSelectorSeeksAndPreservesScopedReceiptSemantics() {
        String otherRecoveryKey;
        try(var tx=database.beginTx()){
            var prepared=new PreparedActionRepository(tx);
            // Eight decoys differ from the exact selector in one or more properties.
            action(prepared,otherActor,scope,upload,UPLOAD);
            action(prepared,actor,otherScope,upload,UPLOAD);
            Node other=action(prepared,actor,scope,otherUpload,UPLOAD);
            otherRecoveryKey=(String)other.getProperty("recoveryKey");
            action(prepared,otherActor,otherScope,upload,FINISH);
            action(prepared,otherActor,scope,otherUpload,ABORT);
            action(prepared,actor,otherScope,otherUpload,UPLOAD);
            action(prepared,otherActor,otherScope,otherUpload,FINISH);
            action(prepared,otherActor,otherScope,otherUpload,ABORT);
            action(prepared,actor,scope,upload,"deposit.resource.download");
            try(var nodes=core(tx).scanNodes("DepositPreparedAction",Map.of())){
                assertEquals(9,nodes.stream().count(),"fixture must retain every prepared-action selector decoy");
            }
            tx.commit();
        }
        try(var tx=database.beginTx()){
            String query="""
                    PROFILE MATCH (n:DepositPreparedAction {actorId:$actor, scope:$scope, resourceUploadId:$upload})
                    RETURN n
                    """;
            try(var result=tx.execute(query,Map.of("actor",actor,"scope",scope,"upload",upload))){
                assertTrue(result.getQueryExecutionType().isProfiled());
                assertEquals(List.of("deposit.resource.download"),
                        result.<Node>columnAs("n").stream().map(node->node.getProperty("procedure")).toList());
                var plan=result.getExecutionPlanDescription();
                System.out.println("PREPARED_ACTION_PLAN\n"+plan+"END_PREPARED_ACTION_PLAN");
                assertEquals(1,plan.getChildren().size(),plan::toString);
                var seek=plan.getChildren().getFirst();
                assertEquals("NodeIndexSeek",seek.getName(),plan::toString);
                var details=String.valueOf(seek.getArguments().get("Details"));
                assertTrue(details.contains("RANGE INDEX n:DepositPreparedAction(actorId, scope, resourceUploadId)"),plan::toString);
                assertEquals(1,seek.getProfilerStatistics().getRows(),plan::toString);
            }
            tx.rollback();
        }
        String pendingKey,pendingOperation;
        try(var tx=database.beginTx()){
            var prepared=new PreparedActionRepository(tx);
            var core=core(tx);
            Node pending=action(prepared,actor,scope,upload,FINISH);
            pendingKey=(String)pending.getProperty("recoveryKey");
            pendingOperation=(String)pending.getProperty("operationId");
            assertEquals("prepared",prepared.state(pending,core).get("state"));
            Node completed=action(prepared,actor,scope,upload,ABORT);
            receipt(tx,scope,(String)completed.getProperty("operationId"));
            assertEquals("committed",prepared.state(completed,core).get("state"));
            receipt(tx,otherScope,pendingOperation);
            tx.commit();
        }
        try(var tx=database.beginTx()){
            var prepared=new PreparedActionRepository(tx);
            var core=core(tx);
            Node pending=prepared.find(actor,pendingKey);
            assertEquals("prepared",prepared.state(pending,core).get("state"),
                    "a receipt in another scope cannot resolve the pending action");
            receipt(tx,scope,pendingOperation);
            assertEquals("committed",prepared.state(pending,core).get("state"));
            assertEquals("prepared",prepared.state(prepared.find(actor,otherRecoveryKey),core).get("state"),
                    "another upload's receipt must not resolve this prepared action");
            tx.commit();
        }
    }
    private Node action(PreparedActionRepository prepared,String actor,String scope,String upload,String procedure) {
        return prepared.create(actor,UUID.randomUUID().toString(),Json.map("operationId",UUID.randomUUID().toString(),
                "scope",scope,"resourceUploadId",upload,"procedure",procedure));
    }
    private void receipt(Transaction tx,String scope,String operation) {
        Node receipt=tx.createNode(Label.label("DepositReceipt"));
        receipt.setProperty("scope",scope);
        receipt.setProperty("operationId",operation);
        receipt.setProperty("outcome","{}");
    }
    private CoreRepository core(Transaction tx){
        return new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }
}
