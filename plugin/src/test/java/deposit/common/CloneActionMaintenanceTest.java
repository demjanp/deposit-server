package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Direct clone-action retention predicate: streaming early exit without ordering or mutation. */
class CloneActionMaintenanceTest {
    private static final String CLONE="deposit.import.cloneNamespace";
    private static final String BEGIN="deposit.import.beginCloneUpload";
    private static final String CHUNK="deposit.import.cloneUploadChunk";
    private static final String FINISH="deposit.import.finishCloneUpload";
    private static final String ABORT="deposit.import.abortCloneUpload";
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,actor,namespace;

    @BeforeEach void setUp() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit-test.properties");
        var properties=new Properties();
        properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"clone action maintenance");}
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");
        server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);
        server.storage=new StorageManager(server,database);
        SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            var repo=core(tx);repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            Node ns=repo.create("Namespace",null,actor,Json.map("name","Clone action maintenance","description","","sequence",0L,"historyMode","disabled"));
            namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");tx.commit();
        }
    }

    @AfterEach void tearDown() {
        try {
            if(database!=null&&server!=null){
                management.unregisterTransactionEventListener("neo4j",server);
                database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);
            }
        } finally {
            try {if(server!=null)server.shutdown();}
            finally {
                try {if(management!=null)management.shutdown();}
                finally {
                    if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());
                    if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);
                }
            }
        }
    }

    @Test void earlierResolvedAndNoncloneActionsCannotHideUnresolvedClone() {
        String completedOperation;
        String unresolvedOperation;
        try(var tx=database.beginTx()){
            completedOperation=(String)action(tx,actor,CLONE,"00000000-").getProperty("operationId");
            receipt(tx,"server",completedOperation);
            action(tx,actor,"deposit.resource.uploadChunk",null).setProperty("id", 42L);
            unresolvedOperation=(String)action(tx,actor,BEGIN,null).getProperty("operationId");
            receipt(tx,namespace,unresolvedOperation);
            receipt(tx,"server",UUID.randomUUID().toString());
            tx.commit();
        }
        assertTrue(pending(actor),"any unresolved clone action must retain the upload regardless of record order");
        try(var tx=database.beginTx()) {
            var label=Label.label("DepositPreparedAction");
            Node completed=tx.findNode(label,"operationId",completedOperation);
            Node unresolved=tx.findNode(label,"operationId",unresolvedOperation);
            var closed=new AtomicBoolean();
            var ordered=orderedSelector(List.of(completed,unresolved),closed);
            assertTrue(pending(ordered,core(tx),actor));
            assertTrue(closed.get(),"the selector must close on early return");
        }
    }

    @Test void everyCloneProcedureIsPendingWithoutServerReceipt() {
        for(String procedure:List.of(CLONE,BEGIN,CHUNK,FINISH,ABORT)){
            String otherActor=UUID.randomUUID().toString();
            try(var tx=database.beginTx()){
                // No id property: actor binding/receipt semantics do not depend on record ordering.
                action(tx,otherActor,procedure,null);tx.commit();
            }
            assertTrue(pending(otherActor),procedure+" must be unresolved without a server receipt");
        }
    }

    @Test void wrongActorAndNoncloneProcedureDoNotPin() {
        try(var tx=database.beginTx()){
            action(tx,UUID.randomUUID().toString(),CLONE,"00000000-");
            action(tx,actor,"deposit.resource.uploadChunk","10000000-");
            tx.commit();
        }
        assertFalse(pending(actor),"actions for another actor or a nonclone procedure must not pin clone maintenance");
    }

    @Test void serverReceiptResolvesCloneAndPredicateDoesNotMutate() {
        try(var tx=database.beginTx()){
            String operation=action(tx,actor,CLONE,null).getProperty("operationId").toString();
            assertNull(core(tx).receipt("server",operation));
            receipt(tx,"server",operation);tx.commit();
        }
        try(var tx=database.beginTx()){
            long before=nodeCount(tx);
            assertFalse(pending(tx,actor),"an exact server receipt resolves the clone action");
            assertEquals(before,nodeCount(tx));tx.rollback();
        }
    }

    @Test void actorSelectorUsesExistingConstraintPrefixWithoutRedundantSchema() {
        try(var tx=database.beginTx()){
            action(tx,actor,"deposit.resource.uploadChunk","00000000-");
            action(tx,actor,CLONE,"ffffffff-");
            for(int i=0;i<16;i++)action(tx,UUID.randomUUID().toString(),CLONE,UUID.randomUUID().toString());
            tx.commit();
        }
        try(var tx=database.beginTx()){
            tx.schema().awaitIndexOnline("deposit_prepared_action", 1, TimeUnit.MINUTES);
            assertTrue(tx.schema().getIndexByName("deposit_prepared_action").isConstraintIndex());
            assertEquals(18, nodeCount(tx, "DepositPreparedAction"));
            try(var rows=tx.execute("PROFILE " + StorageMaintenanceJobs.CLONE_ACTIONS_FOR_ACTOR,Map.of("actor",actor))){
                assertEquals(2,rows.stream().count());
                assertFalse(rows.getQueryStatistics().containsUpdates());
                var plan=rows.getExecutionPlanDescription();
                System.out.println("CLONE_ACTION_PLAN\n" + plan + "END_CLONE_ACTION_PLAN");
                assertEquals(1, plan.getChildren().size(), plan::toString);
                var seek=plan.getChildren().getFirst();
                assertEquals("NodeUniqueIndexSeek",seek.getName(),plan::toString);
                String details=String.valueOf(seek.getArguments().get("Details"));
                assertTrue(details.contains("UNIQUE n:DepositPreparedAction(actorId, recoveryKey)"),plan::toString);
                assertEquals(2, seek.getProfilerStatistics().getRows(), plan::toString);
            }
            tx.rollback();
        }
    }

    private Node action(Transaction tx,String actor,String procedure,String idPrefix) {
        Node action=tx.createNode(Label.label("DepositPreparedAction"));
        if(idPrefix!=null)action.setProperty("id",idPrefix+UUID.randomUUID());
        action.setProperty("recoveryKey",UUID.randomUUID().toString());
        action.setProperty("operationId",UUID.randomUUID().toString());
        action.setProperty("actorId",actor);action.setProperty("scope",namespace);
        action.setProperty("procedure",procedure);return action;
    }
    private boolean pending(String actor) {
        try(var tx=database.beginTx()){boolean result=pending(tx,actor);tx.rollback();return result;}
    }
    private boolean pending(Transaction tx,String actor) {
        return pending(tx,core(tx),actor);
    }
    private boolean pending(Transaction tx,CoreRepository core,String actor) {
        try {
            var jobs=new StorageMaintenanceJobs(server,database,null,null,message->{});
            var method=StorageMaintenanceJobs.class.getDeclaredMethod("hasUnresolvedCloneAction",Transaction.class,CoreRepository.class,String.class);
            method.setAccessible(true);
            return (boolean)method.invoke(jobs,tx,core,actor);
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }

    // Control the row order independently of the database while using real receipt lookups.
    private Transaction orderedSelector(List<Node> rows, AtomicBoolean closed) {
        var iterator=rows.iterator();
        var actions=new ResourceIterator<Node>() {
            @Override public boolean hasNext() {
                assertTrue(iterator.hasNext(),"the selector must stop at the first unresolved action");
                return true;
            }
            @Override public Node next() {return iterator.next();}
            @Override public void close() {}
        };
        var result=(Result)Proxy.newProxyInstance(Result.class.getClassLoader(),new Class<?>[]{Result.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "columnAs" -> actions;
                    case "close" -> {closed.set(true);yield null;}
                    default -> throw new AssertionError(method);
                });
        return (Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},
                (proxy,method,args)->{
                    assertEquals("execute",method.getName());
                    assertEquals(StorageMaintenanceJobs.CLONE_ACTIONS_FOR_ACTOR,args[0]);
                    assertEquals(Map.of("actor",actor),args[1]);
                    return result;
                });
    }
    private void receipt(Transaction tx,String scope,String operation) {
        Node receipt=tx.createNode(Label.label("DepositReceipt"));receipt.setProperty("scope",scope);receipt.setProperty("operationId",operation);
    }
    private long nodeCount(Transaction tx) {try(var nodes=tx.getAllNodes()){return nodes.stream().count();}}
    private long nodeCount(Transaction tx, String label) {
        try (var nodes = tx.findNodes(Label.label(label))) { return nodes.stream().count(); }
    }
    private CoreRepository core(Transaction tx) {return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));}
}
