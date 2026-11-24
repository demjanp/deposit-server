package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import deposit.common.Results.DataRow;
import deposit.repository.CoreRepository;
import deposit.repository.GeometryRepository;
import deposit.repository.HistoryRepository;
import deposit.repository.OperationAttemptRepository;
import deposit.repository.OperationalCommitRepository;
import deposit.repository.ResourceRepository;
import deposit.repository.SchemaRepository;
import deposit.service.ProcedureService;
import deposit.service.SessionActionService;
import java.net.SocketAddress;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.kernel.api.net.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.monitoring.Monitors;

/** Real transactions with deterministic, simulated Bolt connection identities and disconnects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationAttemptTest {
    @TempDir static Path directory;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private NetworkConnectionTracker connections;
    private String previousConfig;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private record Actor(String id, String token, String namespace, Connection execution, Connection control) {}

    @BeforeAll void setUp() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        var root=Files.createDirectory(directory.resolve("resources"));
        var output=Files.createDirectory(directory.resolve("exports"));
        var secrets=Files.createDirectory(directory.resolve("secrets"));
        var config=directory.resolve("deposit.properties");
        var props=new Properties();
        props.setProperty("resourceRoot",root.toString());
        props.setProperty("exportRoot",output.toString());
        props.setProperty("secretRoot",secrets.toString());
        try(var stream=Files.newOutputStream(config)){props.store(stream,"Focused operation tests");}
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(directory.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");
        server=ServerContext.attach(database.databaseId().databaseId().uuid());
        server.sessionClockForTesting(clock::get);
        var field=ServerContext.class.getDeclaredField("database");
        field.setAccessible(true);
        field.set(server,database);
        server.storage=new StorageManager(server,database);
        SchemaRepository.initialize(database);
        server.markReadyForTesting();
        database.getDependencyResolver().resolveDependency(Monitors.class).addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        connections=database.getDependencyResolver().resolveDependency(NetworkConnectionTracker.class);
        database.getDependencyResolver().resolveDependency(org.neo4j.kernel.api.procedure.GlobalProcedures.class)
            .registerProcedure(deposit.api.DepositAPI.class);
    }

    @AfterAll void close() {
        if (server!=null) server.shutdown();
        if (database!=null) {
            management.unregisterTransactionEventListener("neo4j",server);
            database.getDependencyResolver().resolveDependency(Monitors.class).removeMonitorListener(server);
            ServerContext.detach(database.databaseId().databaseId().uuid());
        }
        if (management!=null) management.shutdown();
        if (previousConfig==null) System.clearProperty("deposit.config");
        else System.setProperty("deposit.config",previousConfig);
    }

    @Test void identitiesAreActorScopedAndStatusSurvivesTheExecutionReply() throws Exception {
        Actor a=actor(), b=actor();
        String id=operationId(clock.get());
        String name="one-"+id, procedure="deposit.create.namespace";
        var arguments=List.<Object>of(name,Map.of("historyMode","disabled"));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new CountDownLatch(1);
            var cancel=executor.submit(()->{waitFor(ready);return call(a,a.control,"deposit.action.cancel",List.of(id,"server"),null);});
            var begin=executor.submit(()->{waitFor(ready);return begin(b,id,"server",procedure,arguments);});
            ready.countDown();
            assertEquals("CANCELLED_ROLLED_BACK",state(cancel.get(10,TimeUnit.SECONDS)));
            assertEquals("REGISTERED",state(begin.get(10,TimeUnit.SECONDS)));
        }
        assertEquals("CANCELLED_ROLLED_BACK",state(begin(a,id,"server",procedure,arguments)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,procedure,arguments,id));
        call(b,b.execution,procedure,arguments,id);
        assertEquals("COMMITTED",state(status(b,id,"server")));
        assertEquals("COMMITTED",state(call(b,b.control,"deposit.action.cancel",List.of(id,"server"),null)));
        assertEquals("CANCELLED_ROLLED_BACK",state(status(a,id,"server")));
        assertThrows(ExceptionHandler.DepositException.class,()->call(b,b.execution,procedure,arguments,id));
        try (var tx=database.beginTx()) {
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNotNull(core.find("Namespace","name",name));
            var receipt=core.receipt(b.id,"server",id);
            assertNotNull(receipt);

        }
    }

    @Test void repeatedSettledOperationsReuseSqlitePagesAfterCollection() {
        Actor initial = actor();
        var created = tracked(initial, initial.namespace, "deposit.create.class", List.of("Retention probe", Map.of()));
        var arguments = List.<Object>of(Map.of("id", created.get("id")), Map.of());
        var maintenance = new OperationMaintenance(server, database);
        List<Long> firstCleanup = null;
        for (int cycle = 0; cycle < 2; cycle++) {
            Actor actor = renew(initial);
            call(actor, actor.control, "deposit.utils.refresh", List.of("namespace"), null);
            for (int i = 0; i < 24; i++) {
                var result = tracked(actor, actor.namespace, "deposit.set.class", arguments);
                assertEquals(created.get("id"), result.get("id"));
            }
            var filled = retentionSample(actor.id);
            long expected = cycle == 0 ? 25 : 24;
            assertEquals(List.of(expected, expected), filled.subList(0, 2));
            if (firstCleanup != null)
                assertTrue(filled.get(3) < firstCleanup.get(3), "New operations should consume free pages");
            clock.addAndGet(OperationAttemptRepository.RETENTION + 1);
            for (int pass = 0; pass < 16 && retentionSample(actor.id).getFirst() > 0; pass++) maintenance.tick();
            var collected = retentionSample(actor.id);
            assertEquals(List.of(0L, 0L), collected.subList(0, 2));
            assertTrue(collected.get(3) > filled.get(3), "Collection should free SQLite pages");
            if (firstCleanup == null) firstCleanup = collected;
            System.out.printf("Retention cycle %d, attempts/receipts/pages/free = %s -> %s%n", cycle + 1, filled, collected);
        }
    }

    private List<Long> retentionSample(String actor) {
        return server.storage.operational.read(connection -> {
            try (var query = connection.prepareStatement("""
                SELECT (SELECT count(*) FROM operation_attempts WHERE actor_id=?),
                       (SELECT count(*) FROM operation_receipts WHERE actor_id=?),
                       (SELECT page_count FROM pragma_page_count),
                       (SELECT freelist_count FROM pragma_freelist_count)
                """)) {
                query.setString(1, actor);
                query.setString(2, actor);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    return List.of(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
                }
            }
        });
    }

    @Test void identicalBeginDoesNotMoveTheExecutionBinding() {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Class "+id,Map.of());
        assertEquals("REGISTERED",state(begin(a,id,a.namespace,procedure,args)));
        assertEquals("REGISTERED",state(call(a,a.control,"deposit.action.begin",List.of(id,a.namespace,procedure,args),null)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.control,procedure,args,id));
        assertEquals("REGISTERED",state(status(a,id,a.namespace)));
        call(a,a.execution,procedure,args,id);
        assertEquals("COMMITTED",state(status(a,id,a.namespace)));
    }

    @Test void registeredCypherProceduresProvideIndependentStatus() {
        Actor a=actor();
        String id=operationId(clock.get());
        var parameters=Json.map("id",id,"scope",a.namespace,"name","Cypher "+id,"patch",Map.of(),"token",a.token,
            "procedure","deposit.create.class","arguments",List.of("Cypher "+id,Map.of()));
        assertEquals("REGISTERED",state(ValueCodec.map(cypher(a.execution,
            "CALL deposit.action.begin($id,$scope,$procedure,$arguments,$token) YIELD data RETURN data",parameters).get("data"))));
        var result=ValueCodec.map(cypher(a.execution,"""
            CALL deposit.create.class($name,$patch,$id,$token) YIELD node
            CALL deposit.utils.toData(node,$token) YIELD data
            RETURN data
            """,parameters).get("data"));
        assertEquals("Cypher "+id,result.get("name"));
        assertEquals("COMMITTED",state(ValueCodec.map(cypher(a.control,
            "CALL deposit.get.operation($id,$scope,$token) YIELD data RETURN data",parameters).get("data"))));
    }

    @Test void sessionPreflightAndRegistrationUseTheSameBaseline() {
        Actor actor=actor();
        Actor writer=renew(new Actor(actor.id,null,actor.namespace,addConnection(),addConnection()));
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Pinned class",Map.of());
        var parameters=Json.map("token",actor.token,"scope",actor.namespace,"id",id,
            "procedure",procedure,"arguments",args);
        try(var tx=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,actor.execution.info())){
            try(var rows=tx.execute("CYPHER 25 CALL deposit.get.session($token) YIELD data RETURN data",parameters)){
                assertEquals(0L,ValueCodec.map(ValueCodec.map(rows.next().get("data")).get("observed")).get(actor.namespace));
                assertFalse(rows.hasNext());
            }
            tracked(writer,actor.namespace,"deposit.create.class",List.of("Intervening class",Map.of()));
            call(actor,actor.control,"deposit.utils.refresh",List.of("namespace"),null);
            try(var rows=tx.execute("""
                CYPHER 25 CALL deposit.action.begin($id,$scope,$procedure,$arguments,$token) YIELD data RETURN data
                """,parameters)){
                assertEquals("REGISTERED",state(ValueCodec.map(rows.next().get("data"))));
                assertFalse(rows.hasNext());
            }
            tx.commit();
        }
        var conflict=assertThrows(RuntimeException.class,()->call(actor,actor.execution,procedure,args,id));
        assertTrue(conflict.getMessage().contains("REVISION_CONFLICT"),conflict.toString());
        assertEquals("FAILED_ROLLED_BACK",state(status(actor,id,actor.namespace)));
    }

    @Test void checkedCompositionDetachesPagesAndRejectsAnInterveningCommit() throws Exception {
        Actor actor=actor();
        Map<String,Object> clazz=tracked(actor,actor.namespace,"deposit.create.class",
            List.of("ReadFixture",Json.map("descriptors",List.of(Json.map("name","Score","type","Integer")))));
        String classId=(String)clazz.get("id");
        Map<String,Object> object=tracked(actor,actor.namespace,"deposit.create.object",
            List.of(Map.of("id",classId),Map.of("descriptors",Map.of("Score",7L))));
        String objectId=(String)object.get("id");
        String snapshot="""
            CYPHER 25 CALL deposit.get.revision($token) YIELD data AS start
            CALL (start) {
                WITH start WHERE $expected IS NULL OR start.sequence = $expected
                CALL deposit.get.objectPage($selection, {pageSize: 1}, $token) YIELD data AS page
                WITH page.items[0] AS entity
                CALL deposit.get.classPage({id: entity.classId}, {pageSize: 1}, $token) YIELD data AS classPage
                RETURN collect({entity: entity, schema: classPage.items[0]}) AS rows
            }
            CALL deposit.get.revision($token) YIELD data AS end
            RETURN start, rows, end
            """;
        var parameters=Json.map("token",actor.token,"expected",null,"selection",Map.of("id",objectId));
        var checked=cypher(actor.control,snapshot,parameters);
        assertEquals(checked.get("start"),checked.get("end"));
        var item=ValueCodec.map(((List<?>)checked.get("rows")).getFirst());
        assertEquals(Map.of("Score",7L),ValueCodec.map(item.get("entity")).get("descriptors"));
        assertEquals(classId,ValueCodec.map(item.get("schema")).get("id"));
        var declaration=ValueCodec.map(((List<?>)ValueCodec.map(item.get("schema")).get("descriptors")).getFirst());
        assertEquals("Integer",declaration.get("type"));

        var objectClass=cypher(actor.control,"""
            CALL deposit.get.revision($token) YIELD data AS start
            CALL (start) {
                WITH start
                CALL deposit.get.objectClass($object,$token) YIELD node
                CALL deposit.utils.toData(node,$token) YIELD data
                RETURN collect(data) AS rows
            }
            CALL deposit.get.revision($token) YIELD data AS end
            RETURN start,rows,end
            """,Map.of("token",actor.token,"object",objectId));
        assertEquals(List.of(clazz),objectClass.get("rows"));
        assertEquals(objectClass.get("start"),objectClass.get("end"));

        parameters.put("expected",0L);
        assertEquals(List.of(),cypher(actor.control,snapshot,parameters).get("rows"));
        parameters.put("expected",null);
        String emptyPage="""
            CYPHER 25 CALL deposit.get.revision($token) YIELD data AS start
            CALL (start) {
                WITH start WHERE $expected IS NULL OR start.sequence = $expected
                CALL deposit.get.objectPage({descriptors: {Score: 99}}, {pageSize: 1}, $token) YIELD data
                RETURN collect(data) AS rows
            }
            CALL deposit.get.revision($token) YIELD data AS end
            RETURN start, rows, end
            """;
        var empty=cypher(actor.control,emptyPage,parameters);
        assertEquals(empty.get("start"),empty.get("end"));
        assertEquals(List.of(),ValueCodec.map(((List<?>)empty.get("rows")).getFirst()).get("items"));

        var grouped=cypher(actor.control,"""
            CYPHER 25 CALL deposit.get.revision($token) YIELD data AS start
            CALL (start) {
                WITH start WHERE $expected IS NULL OR start.sequence = $expected
                UNWIND $ids AS id
                CALL deposit.get.classPage({id: id}, {pageSize: 1}, $token) YIELD data AS page
                RETURN collect(page.items[0]) AS rows
            }
            CALL deposit.get.revision($token) YIELD data AS end
            RETURN start, rows, end
            """,Json.map("token",actor.token,"expected",ValueCodec.map(checked.get("start")).get("sequence"),"ids",List.of(classId)));
        assertEquals(List.of(clazz),grouped.get("rows"));
        assertEquals(grouped.get("start"),grouped.get("end"));

        // Commit after the second page has detached its data but before the snapshot can return.
        var pages=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            server.storageObserverForTesting(point->{
                if(!point.equals("read-page-materialized")||pages.incrementAndGet()!=2)return;
                try {
                    executor.submit(()->tracked(actor,actor.namespace,"deposit.set.descriptor",List.of(objectId,"Score",8L)))
                        .get(10,TimeUnit.SECONDS);
                } catch(Exception error) {throw new AssertionError("The ordinary write must commit during the checked read",error);}
            });
            var conflict=assertThrows(RuntimeException.class,()->cypher(actor.control,snapshot,parameters));
            assertTrue(conflict.getMessage().contains("REVISION_CONFLICT"),conflict.toString());
        } finally {server.storageObserverForTesting(ignored->{});}
        assertEquals(2,pages.get());
        var after=cypher(actor.control,snapshot,parameters);
        assertEquals(after.get("start"),after.get("end"));
        var updated=ValueCodec.map(((List<?>)after.get("rows")).getFirst());
        assertEquals(Map.of("Score",8L),ValueCodec.map(updated.get("entity")).get("descriptors"));
    }

    @Test void typedQueryPagesBindTheCursorAndTerminateAtTheDeadline() throws Exception {
        Actor actor=actor();
        tracked(actor,actor.namespace,"deposit.create.class",
            List.of("QueryFixture",Map.of("descriptors",List.of(Map.of("name","Score","type","Integer")))));
        for(long score:List.of(7L,2L,9L))tracked(actor,actor.namespace,"deposit.create.object",
            List.of("QueryFixture",Map.of("descriptors",Map.of("Score",score))));
        String query="SELECT QueryFixture.Score AS score ORDER BY score DESC";
        String page="CYPHER 25 CALL deposit.get.queryPage($query, {}, $options, $token) YIELD data RETURN data";
        var params=Json.map("query",query,"options",Map.of("pageSize",2L),"token",actor.token);
        var first=ValueCodec.map(cypher(actor.control,page,params).get("data"));
        var scores=new ArrayList<Long>();
        for(Object row:(List<?>)first.get("rows"))scores.add((Long)ValueCodec.map(ValueCodec.map(row).get("values")).get("score"));
        assertEquals(List.of(9L,7L),scores);
        params.put("options",Map.of("pageSize",2L,"cursor",first.get("nextCursor")));
        var second=ValueCodec.map(cypher(actor.control,page,params).get("data"));
        assertEquals(2L,ValueCodec.map(ValueCodec.map(((List<?>)second.get("rows")).getFirst()).get("values")).get("score"));
        assertNull(second.get("nextCursor"));
        params.put("query","SELECT QueryFixture.Score");
        assertTrue(assertThrows(RuntimeException.class,()->cypher(actor.control,page,params))
            .getMessage().contains("VALIDATION_FAILED"));

        try(var tx=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,actor.control.info())) {
            var c=new RequestContext(tx,Map.of("token",actor.token));
            var timeout=assertThrows(ExceptionHandler.DepositException.class,()->{
                try(var rows=new deposit.service.GetQueryService(c).getQuery(Map.of("query",query,"timeoutMs",1L))) {
                    Thread.sleep(20);
                    rows.findFirst();
                }
            });
            assertTrue(timeout.getMessage().contains("LIMIT_EXCEEDED"),timeout.toString());
        }
    }

    @Test void patternSearchReusesTypedProbesAndKeepsNativeBindings() {
        Actor actor=actor();
        tracked(actor,actor.namespace,"deposit.create.class",List.of("PatternFixture",
            Map.of("descriptors",List.of(Map.of("name","Score","type","Integer")))));
        var matching=tracked(actor,actor.namespace,"deposit.create.object",
            List.of("PatternFixture",Map.of("descriptors",Map.of("Score",7L))));
        var missing=tracked(actor,actor.namespace,"deposit.create.object",List.of("PatternFixture",Map.of()));
        tracked(actor,actor.namespace,"deposit.create.pattern",List.of("ScorePattern",Json.map("objects",
            Json.map("root",Json.map("root",true,"className","PatternFixture","descriptors",List.of(Map.of("name","Score"))))),Map.of()));
        var parameters=Json.map("token",actor.token,"values",Map.of("root",Map.of("Score",7L)));
        var page=ValueCodec.map(cypher(actor.control,"""
            CALL deposit.get.objectsByPatternPage('ScorePattern',$values,{matchEmpty:true},{pageSize:1},$token)
            YIELD data RETURN data
            """,parameters).get("data"));
        assertNull(page.get("nextCursor"));
        assertEquals(List.of(Json.map("rootName","root","bindings",Map.of("root",matching.get("id")))),page.get("items"));
        parameters.put("values",Map.of("root",Map.of()));
        assertEquals(missing.get("id"),cypher(actor.control,"""
            CALL deposit.get.objectsByPattern('ScorePattern',$values,{matchEmpty:true},$token)
            YIELD data RETURN data.bindings.root.id AS id
            """,parameters).get("id"));
    }

    @Test void otherProceduresStillCannotShareATrackedTransaction() {
        Actor a=actor();
        String id=operationId(clock.get());
        var arguments=List.<Object>of("Mixed "+id,Map.of());
        assertEquals("REGISTERED",state(begin(a,id,a.namespace,"deposit.create.class",arguments)));
        var parameters=Json.map("id",id,"name",arguments.getFirst(),"patch",Map.of(),"token",a.token);
        assertThrows(RuntimeException.class,()->cypher(a.execution,"""
            CALL deposit.create.class($name,$patch,$id,$token) YIELD node
            CALL deposit.get.session($token) YIELD data
            RETURN node.id AS id,data
            """,parameters));
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
    }

    @Test void publicStoragePressureReportsBackpressureAndRollback() {
        Actor a=actor();
        String id=operationId(clock.get());
        String name="Pressure "+id;
        assertEquals("REGISTERED",state(begin(a,id,a.namespace,"deposit.create.class",List.of(name,Map.of()))));
        server.storageObserverForTesting(point->{
            if(point.equals("operation-before-final-fence"))
                throw new StorageAdmission.StoragePressureException("Storage admission is busy");
        });
        try {
            var failure=assertThrows(RuntimeException.class,()->cypher(a.execution,
                "CALL deposit.create.class($name,$patch,$id,$token) YIELD node RETURN node.id AS id",
                Json.map("id",id,"name",name,"patch",Map.of(),"token",a.token)));
            assertTrue(failure.getMessage().contains("LIMIT_EXCEEDED"),failure.getMessage());
            assertTrue(failure.getMessage().contains("Storage admission is busy"),failure.getMessage());
        } finally {
            server.storageObserverForTesting(point->{});
        }
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
    }

    @Test void cancellationBeforeTheFinalGateRollsBackTheGraph() throws Exception {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Cancelled "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        var reached=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        server.storageObserverForTesting(point->{if(point.equals("operation-before-final-fence")){reached.countDown();waitFor(release);}});
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var run=executor.submit(()->call(a,a.execution,procedure,args,id));
            try {
                assertTrue(reached.await(10,TimeUnit.SECONDS));
                assertEquals("RUNNING",state(status(a,id,a.namespace)));
                assertEquals("CANCEL_REQUESTED",state(call(a,a.control,"deposit.action.cancel",List.of(id,a.namespace),null)));
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class,()->run.get(10,TimeUnit.SECONDS));
        } finally { server.storageObserverForTesting(point->{}); }
        assertEquals("CANCELLED_ROLLED_BACK",state(status(a,id,a.namespace)));
        assertNull(server.storage.operationalCommits.completion(a.id, a.namespace, id));
        try (var tx=database.beginTx()) {
            var core = new CoreRepository(tx, ZonedDateTime.now());
            assertNull(core.find("Class",Map.of("namespaceId",a.namespace,"name",args.getFirst())));
            assertFalse(core.namespace(a.namespace).hasProperty(OperationalCommitRepository.MARKER));
        }
        String registered=operationId(clock.get());
        assertEquals("REGISTERED",state(begin(a,registered,a.namespace,procedure,args)));
        assertThrows(IllegalStateException.class,()->call(a,a.control,"deposit.action.cancel",
            List.of(registered,a.namespace),null,()->{throw new IllegalStateException("Roll back control transaction");}));
        assertEquals("REGISTERED",state(status(a,registered,a.namespace)));
        String preCancelled=operationId(clock.get());
        assertEquals("CANCELLED_ROLLED_BACK",state(call(a,a.control,"deposit.action.cancel",
            List.of(preCancelled,a.namespace),null)));
        assertEquals("CANCELLED_ROLLED_BACK",state(begin(a,preCancelled,a.namespace,procedure,args)));
    }

    @Test void aLostExecutionConnectionStopsLiveWorkBeforeCompletion() throws Exception {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Disconnected "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        var reached=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        server.storageObserverForTesting(point->{if(point.equals("operation-before-final-fence")){reached.countDown();waitFor(release);}});
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var work=workers.submit(()->call(a,a.execution,procedure,args,id));
            try{
                assertTrue(reached.await(10,TimeUnit.SECONDS));
                connections.remove(a.execution);
                server.checkRequestLiveness();
                assertEquals("CANCEL_REQUESTED",state(status(a,id,a.namespace)));
            }finally{release.countDown();}
            assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));
        }finally{server.storageObserverForTesting(point->{});}
        assertEquals("CANCELLED_ROLLED_BACK",state(status(a,id,a.namespace)));
    }

    @Test void lostExportAndAnalysisRepliesLeaveNoDomainWriteReceipts() {
        Actor a=actor();
        String export="deposit.exchange.export", exportId=operationId(clock.get());
        var exportArgs=Arrays.<Object>asList(null,Map.of());
        begin(a,exportId,a.namespace,export,exportArgs);
        call(a,a.execution,export,exportArgs,exportId);
        var exported=call(a,a.control,"deposit.action.cancel",List.of(exportId,a.namespace),null);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(exported));
        String analyze="deposit.import.analyze",analysisId=operationId(clock.get());
        var analysis=List.<Object>of(packageSource(ValueCodec.map(exported.get("result"))),Map.of());
        begin(a,analysisId,a.namespace,analyze,analysis);
        call(a,a.execution,analyze,analysis,analysisId);
        var analyzed=status(a,analysisId,a.namespace);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(analyzed));
        assertNotNull(ValueCodec.map(analyzed.get("result")).get("planId"));
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(call(a,a.control,"deposit.action.cancel",List.of(analysisId,a.namespace),null)));
    }

    @Test void lateCancellationReturnsTheCommittedResultAndCannotStartASecondMutation() throws Exception {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Committed "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        var finalGate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var run=executor.submit(()->call(a,a.execution,procedure,args,id,()->{finalGate.countDown();waitFor(release);}));
            Future<Map<String,Object>> cancel;
            try {
                assertTrue(finalGate.await(10,TimeUnit.SECONDS));
                var other=operationId(clock.get());
                var busy=assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.control,procedure,args,other));
                assertTrue(busy.getMessage().contains("SESSION_BUSY"));
                cancel=executor.submit(()->call(a,a.control,"deposit.action.cancel",List.of(id,a.namespace),null));
            } finally {release.countDown();}
            run.get(10,TimeUnit.SECONDS);
            assertEquals("COMMITTED",state(cancel.get(10,TimeUnit.SECONDS)));
        }
    }

    @Test void cancellationWhileWaitingForNamespaceLockExcludesDelayedExecution() throws Exception {
        Actor a=actor(), independent=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Waiting "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();var blocker=database.beginTx()){
            var core=new CoreRepository(blocker,ZonedDateTime.now());
            core.lockNamespace(a.namespace);
            core.lockNamespace(a.namespace);
            var run=workers.submit(()->call(a,a.execution,procedure,args,id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!"RUNNING".equals(status(a,id,a.namespace).get("state"))&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals("RUNNING",state(status(a,id,a.namespace)));
            assertNotNull(workers.submit(()->tracked(independent,independent.namespace,procedure,args)).get(10,TimeUnit.SECONDS).get("id"));
            var cancelled=state(call(a,a.control,"deposit.action.cancel",List.of(id,a.namespace),null));
            assertTrue(Set.of("CANCEL_REQUESTED","CANCELLED_ROLLED_BACK").contains(cancelled));
            assertThrows(ExecutionException.class,()->run.get(10,TimeUnit.SECONDS));

            var waiting=new CountDownLatch(1);
            var next=workers.submit(()->{
                try(var tx=database.beginTx()){
                    waiting.countDown();
                    new CoreRepository(tx,ZonedDateTime.now()).lockNamespace(a.namespace);
                    tx.commit();
                }
            });
            assertTrue(waiting.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->next.get(100,TimeUnit.MILLISECONDS));
            blocker.rollback();
            next.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,server.transactionCountForTesting());
        assertEquals("CANCELLED_ROLLED_BACK",state(status(a,id,a.namespace)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,procedure,args,id));
    }

    @Test void accountDisableOrdersAfterAnOperationAlreadyInItsCommitBoundary() throws Exception {
        Actor a=actor(), administrator=actor();
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.find("User","id",administrator.id).setProperty("serverRole","administrator");
            core.setRole(administrator.id,a.namespace,"owner");
            tx.commit();
        }
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Authorized "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        String disableId=operationId(clock.get());
        var disableArgs=List.<Object>of(Map.of("id",a.id),Map.of("enabled",false));
        begin(administrator,disableId,"server","deposit.set.user",disableArgs);
        var gate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var run=workers.submit(()->call(a,a.execution,procedure,args,id,()->{gate.countDown();waitFor(release);}));
            Future<Map<String,Object>> disabled;
            Future<?> actorLock;
            try{
                assertTrue(gate.await(10,TimeUnit.SECONDS));
                var waiting=new CountDownLatch(1);
                actorLock=workers.submit(()->{
                    try(var tx=database.beginTx()){
                        waiting.countDown();
                        new CoreRepository(tx,ZonedDateTime.now()).lockActor(a.id);
                        tx.commit();
                    }
                });
                assertTrue(waiting.await(10,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->actorLock.get(100,TimeUnit.MILLISECONDS));
                disabled=workers.submit(()->call(administrator,administrator.execution,"deposit.set.user",disableArgs,disableId));
                assertThrows(TimeoutException.class,()->disabled.get(100,TimeUnit.MILLISECONDS));
            }finally{release.countDown();}
            run.get(10,TimeUnit.SECONDS);
            actorLock.get(10,TimeUnit.SECONDS);
            disabled.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,server.transactionCountForTesting());
        assertThrows(ExceptionHandler.DepositException.class,()->status(a,id,a.namespace));
        try(var tx=database.beginTx()){
            assertEquals("COMMITTED",server.storage.attempts.find(a.id,a.namespace,id).state());
        }
    }

    @Test void logoutFencesAnUnclaimedRegistration() {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Log out "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        call(a,a.control,"deposit.utils.logout",List.of(),null);
        var replacement=renew(a);
        assertEquals("CANCELLED_ROLLED_BACK",state(status(replacement,id,a.namespace)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(replacement,a.execution,procedure,args,id));
    }

    @Test void revokedActorCannotClaimOrInspectTheNamespaceAttempt() {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Revoked "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(a.id,a.namespace,"viewer");
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,procedure,args,id));
        assertThrows(ExceptionHandler.DepositException.class,()->status(a,id,a.namespace));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.control,"deposit.action.cancel",List.of(id,a.namespace),null));
    }

    @Test void operationIdMayBeUsedIndependentlyInServerAndNamespaceScopes() {
        Actor a=actor();
        String id=operationId(clock.get());
        String createNamespace="deposit.create.namespace", createClass="deposit.create.class";
        var namespaceArgs=List.<Object>of("Namespace "+id,Map.of("historyMode","disabled"));
        var classArgs=List.<Object>of("Class "+id,Map.of());
        begin(a,id,"server",createNamespace,namespaceArgs);
        begin(a,id,a.namespace,createClass,classArgs);
        call(a,a.execution,createNamespace,namespaceArgs,id);
        call(a,a.execution,createClass,classArgs,id);
        assertEquals("COMMITTED",state(status(a,id,"server")));
        assertEquals("COMMITTED",state(status(a,id,a.namespace)));
    }

    @Test void deletedNamespaceLeavesAnActorOnlySanitizedServerReceipt() throws Exception {
        Actor a=actor(), b=actor();
        String upload=beginResource(a);writeResource(a,upload);
        String resource=(String)tracked(a,a.namespace,"deposit.resource.finishUpload",List.of(upload)).get("resourceId");
        try(var tx=database.beginTx()){
            var resources=new ResourceRepository(tx,new CoreRepository(tx,ZonedDateTime.now()));
            Node completed=resources.resource(a.namespace,resource);
            assertNotNull(completed);assertNull(resources.location(resource));
            assertTrue(resources.accessible(completed,a.id,"owner",false));
        }
        String id=operationId(clock.get()), procedure="deposit.delete.namespace";
        var args=List.<Object>of(a.id);
        begin(a,id,"server",procedure,args);
        call(a,a.execution,procedure,args,id);
        var proof=status(a,id,"server");
        assertEquals("COMMITTED",state(proof));
        assertNull(proof.get("result"));
        assertNull(proof.get("code"));
        assertEquals("OUTCOME_UNKNOWN",state(status(b,id,"server")));
        String job=UUID.randomUUID().toString(),item=job+"/"+resource;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var resources=new ResourceRepository(tx,core);
            assertNull(resources.resource(a.namespace,resource));
            Node detached=resources.state(resource).completed();assertNotNull(detached);
            assertEquals(a.namespace,detached.getProperty("namespaceId"));
            assertEquals(true,detached.getProperty("detached"));
            assertFalse(detached.hasRelationship());
            assertFalse(resources.accessible(detached,a.id,"owner",false));
            assertTrue(resources.collectionCandidates(a.namespace).isEmpty());
            assertTrue(resources.collectionCandidates(null).containsKey(resource));
            assertTrue(server.storage.content.exists(resource));
            server.storage.pruning.create(new deposit.repository.PruneRepository.Job(job,"server",a.id,0,clock.get(),server.runId),
                List.of(resource),null);
            tx.commit();
        }
        var jobs=new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,msg->{});
        jobs.collect(item);jobs.collect(item);
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNull(core.find("DResource","id",resource));
            assertEquals("completed",server.storage.pruning.item(item).state());
            assertFalse(server.storage.content.exists(resource));
        }
    }

    @Test void aRevokedSourceGrantBlocksClaimStatusAndIdenticalRegistration() {
        Actor source=actor(), receiver=receivingNamespace(source);
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        String id=operationId(clock.get()), procedure="deposit.import.analyze";
        var args=List.<Object>of(packageSource(reference),Map.of());
        assertEquals("REGISTERED",state(begin(receiver,id,receiver.namespace,procedure,args)));
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(source.id,source.namespace,null);
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->begin(receiver,id,receiver.namespace,procedure,args));
        assertThrows(ExceptionHandler.DepositException.class,()->status(receiver,id,receiver.namespace));
        assertThrows(ExceptionHandler.DepositException.class,()->call(receiver,receiver.execution,procedure,args,id));
    }

    @Test void claimedWorkerCanCommitAfterItsOriginalDeadline() throws Exception {
        Actor a=actor();
        long initial=clock.get();
        String id=operationId(initial), procedure="deposit.create.class";
        var args=List.<Object>of("Long worker "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        var finalGate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var run=executor.submit(()->call(a,a.execution,procedure,args,id,()->{finalGate.countDown();waitFor(release);}));
            try{
                assertTrue(finalGate.await(10,TimeUnit.SECONDS));
                clock.set(initial+OperationAttemptRepository.RETENTION+1);
                server.checkRequestLiveness();
            }finally{release.countDown();}
            run.get(10,TimeUnit.SECONDS);
        }
        assertEquals("COMMITTED",state(status(renew(a),id,a.namespace)));
    }

    @Test void tableReadAcrossExpiryKeepsTheFilesAndPairedLeaseUntilClose() throws Exception {
        Actor a=actor();
        String table=UUID.randomUUID().toString();
        Path rows=server.storage.files.path("staging/tables/"+table+".json");
        Path index=server.storage.files.path("staging/tables/"+table+".idx");
        Files.createDirectories(rows.getParent());
        String body="{\"rows\":[{\"rowId\":\"r\",\"values\":{\"x\":\"v\"},\"presence\":{\"x\":true}}]}";
        Files.writeString(rows,body);
        try(var offsets=new java.io.DataOutputStream(Files.newOutputStream(index))){offsets.writeLong(body.indexOf("{\"rowId\""));}
        long expires=clock.get()+1_000L;
        String source=UUID.randomUUID().toString();
        server.storage.tables.create(new deposit.repository.StagedTableRepository.Table(table,a.namespace,a.id,source,
            "staging/tables/"+table+".json","staging/tables/"+table+".idx",clock.get(),expires,"[]",1,true,"[]",false),
            UUID.randomUUID().toString(),null);
        server.storage.importPlans.save(new deposit.repository.ImportPlanRepository.Plan(table,a.namespace,a.id,table,null,
            table,null,null,0,"0".repeat(64),0,clock.get(),clock.get(),expires+60_000L,"ready","{}",
            UUID.randomUUID().toString(),false),"");
        call(a,a.control,"deposit.get.tablePage",List.of(table,Map.of()),null);
        var jobs=new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,message->{});
        try(var reader=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,a.execution.info());
            var threads=Executors.newVirtualThreadPerTaskExecutor()){
            var ctx=new RequestContext(reader,Map.of("token",a.token));
            var actions=new SessionActionService(ctx);
            actions.beginPublic("deposit.get.table",List.of(table),null);
            try(var stream=new ProcedureService(ctx).execute()){
                clock.set(expires);
                var cleanup=threads.submit(()->{jobs.expireTables();return null;});
                try {
                    assertEquals(1,stream.count());
                    assertTrue(Files.exists(rows));
                    assertTrue(Files.exists(index));
                    assertThrows(TimeoutException.class,()->cleanup.get(100,TimeUnit.MILLISECONDS));
                } finally { reader.commit(); }
                cleanup.get(10,TimeUnit.SECONDS);
            }
        }
        assertFalse(Files.exists(rows));
        assertFalse(Files.exists(index));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.control,"deposit.get.tablePage",List.of(table,Map.of()),null));
        assertFalse(server.storage.tables.protects(source,clock.get()));
        assertNull(server.storage.importPlans.plan(table).tableId());
    }

    @Test void operationExpiresUnclaimedAndCannotBeAdmittedAfterClockRollbackOrRestore() {
        Actor a=actor();
        long original=clock.get();
        String id=operationId(original), procedure="deposit.create.class";
        var args=List.<Object>of("Expired "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        clock.set(original+OperationAttemptRepository.RETENTION+1);
        new OperationMaintenance(server,database).tick();
        a=renew(a);
        assertEquals("CANCELLED_ROLLED_BACK",state(status(a,id,a.namespace)));
        clock.addAndGet(OperationAttemptRepository.RETENTION+1);
        new OperationMaintenance(server,database).tick();
        clock.set(original+10);
        Actor fresh=renew(a);
        var backward = assertThrows(ExceptionHandler.DepositException.class, () -> begin(fresh,id,fresh.namespace,procedure,args));
        assertTrue(backward.getMessage().contains("OUTCOME_UNKNOWN"));
        clock.set(original+2*OperationAttemptRepository.RETENTION+100);
        String missing=operationId(clock.get()+OperationAttemptRepository.FUTURE_SKEW-1);
        server.storage.operationalState.advanceAdmission(clock.get()+OperationAttemptRepository.FUTURE_SKEW,-1);
        Actor restored=renew(a);
        var restore = assertThrows(ExceptionHandler.DepositException.class, () -> begin(restored,missing,restored.namespace,procedure,args));
        assertTrue(restore.getMessage().contains("OPERATION_ID_EXPIRED"));
        assertEquals("OUTCOME_UNKNOWN",state(status(restored,missing,restored.namespace)));
        clock.addAndGet(OperationAttemptRepository.FUTURE_SKEW+1);
    }

    @Test void finishRejectsBytesThatDifferFromTheDeclaredResourceDigest() throws Exception {
        Actor actor=actor();
        byte[] expected={1,2}, received={1,3};
        String upload=(String)tracked(actor,actor.namespace,"deposit.resource.beginUpload",
            List.of("payload.bin",2L,ResourceFiles.checksum(expected),"application/octet-stream")).get("uploadId");
        var acknowledged=tracked(actor,actor.namespace,"deposit.resource.uploadChunk",
            List.of(upload,0L,Base64.getEncoder().encodeToString(received)));
        assertEquals(2L,acknowledged.get("nextOffset"));
        String finish="deposit.resource.finishUpload", operation=operationId(clock.get());
        var arguments=List.<Object>of(upload);
        begin(actor,operation,actor.namespace,finish,arguments);
        assertTrue(assertThrows(ExceptionHandler.DepositException.class,()->call(actor,actor.execution,finish,arguments,operation))
            .getMessage().contains("RESOURCE_CORRUPT"));
        assertEquals("FAILED_ROLLED_BACK",state(status(actor,operation,actor.namespace)));
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now());
            assertTrue(repo.nodes("DResource",Map.of("namespaceId",actor.namespace)).isEmpty());
        }
    }

    @Test void disconnectedUploadCannotAppendAndReplacementStartsAtZero() {
        Actor a=actor();
        String begin="deposit.resource.beginUpload", chunk="deposit.resource.uploadChunk";
        byte[] bytes={1,2};
        var args=List.<Object>of("payload.bin",2L,ResourceFiles.checksum(bytes),"application/octet-stream");
        String id=operationId(clock.get());
        begin(a,id,a.namespace,begin,args);
        var result=call(a,a.execution,begin,args,id);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(status(a,id,a.namespace)));
        String upload=(String)result.get("uploadId");
        byte[] first={1};
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(first));
        String step=operationId(clock.get());
        begin(a,step,a.namespace,chunk,part);
        assertEquals(1L,call(a,a.execution,chunk,part,step).get("nextOffset"));
        connections.remove(a.execution);
        var newConnection=addConnection();
        var replacement=new Actor(a.id,a.token,a.namespace,newConnection,a.control);
        byte[] second={2};
        var append=List.<Object>of(upload,1L,Base64.getEncoder().encodeToString(second));
        assertThrows(ExceptionHandler.DepositException.class,()->begin(replacement,operationId(clock.get()),a.namespace,chunk,append));
        String newId=operationId(clock.get());
        begin(replacement,newId,a.namespace,begin,args);
        assertEquals(0L,call(replacement,newConnection,begin,args,newId).get("nextOffset"));
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(call(a,a.control,"deposit.action.cancel",List.of(step,a.namespace),null)));
    }

    @Test void interruptedStagingIsReclaimedAndCommittedOcflSurvivesReconciliation() throws Exception {
        Actor a=actor();
        String upload=beginResource(a);
        writeResource(a,upload);
        var finishArgs=List.<Object>of(upload);
        String finish="deposit.resource.finishUpload", id=operationId(clock.get());
        begin(a,id,a.namespace,finish,finishArgs);
        var published=call(a,a.execution,finish,finishArgs,id);
        String resource=(String)published.get("resourceId");
        assertTrue(server.storage.content.exists(resource));
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(status(a,id,a.namespace)));

        String partial=beginResource(a);
        writeResource(a,partial);
        Path staged=server.storage.files.path("staging/"+a.namespace+"/"+partial+".part");
        assertTrue(Files.exists(staged));
        connections.remove(a.execution);
        server.storage.recover();
        server.storage.recover();
        assertFalse(Files.exists(staged));
        assertTrue(server.storage.content.exists(resource));
    }

    @Test void liveWriterCanFinishPastTheOriginalAdmissionWindow() {
        Actor a=actor();
        String upload=beginResource(a);
        clock.addAndGet(OperationAttemptRepository.RETENTION+1);
        writeResource(a,upload);
        String procedure="deposit.resource.finishUpload", id=operationId(clock.get());
        var args=List.<Object>of(upload);
        begin(a,id,a.namespace,procedure,args);
        assertEquals("ready",call(a,a.execution,procedure,args,id).get("state"));
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(status(renew(a),id,a.namespace)));
    }

    @Test void interruptedServerScopeExchangeUploadDiscardsItsIncompleteZip() throws Exception {
        Actor a=actor();
        String upload=beginExchange(a,"server","zip",Map.of());
        exchangeChunk(a,"server",upload,"package",0L,new byte[]{1});
        Path incomplete=server.storage.files.path("staging/exchange/"+upload+"/package.zip");
        assertTrue(Files.exists(incomplete));
        connections.remove(a.execution);
        server.storage.recover();
        assertFalse(Files.exists(incomplete));
    }

    @Test void startupPreservesCommitAndFencesAbandonedWork() throws Exception {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Committed before restart",Map.of());
        begin(a,id,a.namespace,procedure,args);
        String committed=(String)call(a,a.execution,procedure,args,id).get("id");
        String pending=operationId(clock.get());
        var incomplete=List.<Object>of("Abandoned before restart",Map.of());
        begin(a,pending,a.namespace,procedure,incomplete);
        String running=operationId(clock.get());
        begin(a,running,a.namespace,procedure,incomplete);
        var attempt=server.storage.attempts.find(a.id,a.namespace,running);
        var abandoned=new OperationalCommitRepository.Unit(UUID.randomUUID().toString(),a.id,a.namespace,running,
            attempt.inputHash(),"Namespace",a.namespace);
        assertTrue(server.storage.operationalCommits.begin(abandoned,clock.get()));
        String upload=beginResource(a);
        writeResource(a,upload);
        Path staged=server.storage.files.path("staging/"+a.namespace+"/"+upload+".part");
        assertTrue(Files.isRegularFile(staged));
        var completedChunk=server.storage.attempts.uploadAttempts(upload).stream()
            .filter(step->"deposit.resource.uploadChunk".equals(step.procedure())).findFirst().orElseThrow().key();

        Actor b = actor();
        String noOp = operationId(clock.get()), rolledBack = operationId(clock.get());
        var unchanged = List.<Object>of(Map.of("id", committed), Map.of());
        var rolledBackArgs = List.<Object>of("Never committed", Map.of());
        begin(a, noOp, a.namespace, "deposit.set.class", unchanged);
        begin(b, rolledBack, b.namespace, procedure, rolledBackArgs);
        var faults = new AtomicLong();
        server.storageObserverForTesting(point -> {
            if (point.equals("operational-before-settlement")) {
                faults.incrementAndGet();
                throw new IllegalStateException("Injected settlement failure");
            }
        });
        try {
            assertEquals(false, call(a, a.execution, "deposit.set.class", unchanged, noOp, () -> {
                assertEquals(new OperationalCommitRepository.Completion(null, null, false),
                    server.storage.operationalCommits.completion(a.id, a.namespace, noOp));
            }).get("changed"));
            assertThrows(IllegalStateException.class, () -> call(b, b.execution, procedure, rolledBackArgs, rolledBack,
                () -> { throw new IllegalStateException("Test requests outer rollback"); }));
        } finally {
            server.storageObserverForTesting(point -> {});
        }
        assertEquals(2, faults.get());
        assertEquals("OUTCOME_UNKNOWN", state(status(a, noOp, a.namespace)));
        assertNull(status(a, noOp, a.namespace).get("result"));
        assertEquals("OUTCOME_UNKNOWN", state(status(b, rolledBack, b.namespace)));
        var pendingUnits = server.storage.operationalCommits.pending();
        assertEquals(3, pendingUnits.size());
        Actor independent = actor();
        try (var tx = database.beginTx()) {
            var core = new CoreRepository(tx, ZonedDateTime.now());
            assertEquals(pendingUnits.stream().filter(unit -> unit.prepared() && unit.ownerId().equals(a.namespace)).findFirst().orElseThrow().id(),
                core.namespace(a.namespace).getProperty(OperationalCommitRepository.MARKER));
            assertFalse(core.namespace(b.namespace).hasProperty(OperationalCommitRepository.MARKER));
            assertFalse(core.data(core.namespace(a.namespace)).containsKey(OperationalCommitRepository.MARKER));
            assertNull(core.find("Class", Map.of("namespaceId", b.namespace, "name", rolledBackArgs.getFirst())));
            var fenced = assertThrows(ExceptionHandler.DepositException.class, () -> core.lockNamespace(a.namespace));
            assertTrue(fenced.getMessage().contains("OUTCOME_UNKNOWN"));
            core.lockNamespace(independent.namespace);
        }

        var monitors=database.getDependencyResolver().resolveDependency(Monitors.class);
        management.unregisterTransactionEventListener("neo4j",server);
        monitors.removeMonitorListener(server);
        server.shutdown();
        UUID databaseId=database.databaseId().databaseId().uuid();
        ServerContext.detach(databaseId);
        server=ServerContext.attach(databaseId);
        server.sessionClockForTesting(clock::get);
        var field=ServerContext.class.getDeclaredField("database");
        field.setAccessible(true);
        field.set(server,database);
        server.storage=new StorageManager(server,database);
        monitors.addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        server.storage.recover();
        server.markReadyForTesting();

        Actor renewed=renew(a);
        var status=status(renewed,id,a.namespace);
        assertEquals("COMMITTED",state(status));
        assertEquals(committed,ValueCodec.map(status.get("result")).get("id"));
        assertEquals("CANCELLED_ROLLED_BACK",state(status(renewed,pending,a.namespace)));
        assertEquals("FAILED_ROLLED_BACK",state(status(renewed,running,a.namespace)));
        assertEquals("COMMITTED", state(status(renewed, noOp, a.namespace)));
        assertEquals(false, ValueCodec.map(status(renewed, noOp, a.namespace).get("result")).get("changed"));
        assertEquals("FAILED_ROLLED_BACK", state(status(renew(b), rolledBack, b.namespace)));
        assertNull(server.storage.operationalCommits.completion(b.id, b.namespace, rolledBack));
        server.storage.recoverCommitBoundary();
        assertTrue(server.storage.operationalCommits.pending().isEmpty());
        assertThrows(ExceptionHandler.DepositException.class,()->call(renewed,renewed.execution,procedure,incomplete,pending));
        assertFalse(Files.exists(staged));
        assertEquals("none",server.storage.attempts.find(completedChunk).cleanup());
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNotNull(core.entity("Class",a.namespace,committed));
            assertNull(core.find("Class",Map.of("namespaceId",a.namespace,"name",incomplete.getFirst())));
        }
    }

    @Test void uncertainOcflUploadOwnershipIsQuarantined() throws Exception {
        Actor a=actor();
        String upload=beginResource(a);
        writeResource(a,upload);
        String pending=operationId(clock.get());
        begin(a,pending,a.namespace,"deposit.resource.finishUpload",List.of(upload));
        assertEquals("CANCELLED_ROLLED_BACK",state(call(a,a.control,"deposit.action.cancel",List.of(pending,a.namespace),null)));
        String resource, admission;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var transfer=server.storage.transfers.transfer(upload);
            resource=transfer.resource();
            admission=transfer.admissionId();
            Path staged=server.storage.files.path(transfer.stagingPath());
            server.storage.content.publish(transfer.content(),staged);
            server.storage.transfers.save(transfer.cleanup("staging/unowned.part",null,false,false,null),"");
            tx.commit();
        }
        connections.remove(a.execution);
        server.storage.recover();
        server.storage.recover();
        assertTrue(server.storage.content.exists(resource));
        assertTrue(Files.isRegularFile(server.storage.files.root.resolve("admission").resolve(admission+".json")));
        assertEquals("failed",status(a,pending,a.namespace).get("cleanupState"));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var transfer=server.storage.transfers.transfer(upload);
            assertTrue(transfer.quarantined());
            assertEquals("expired",transfer.state());
        }
    }

    @Test void formerExecutionResumeProcedureIsUnregistered() {
        try(var tx=database.beginTx();var rows=tx.execute("SHOW PROCEDURES YIELD name WHERE name IN $retired RETURN name",
                Map.of("retired",List.of("deposit.action.prepare","deposit.action.resume","deposit.action.release",
                    "deposit.action.status","deposit.import.beginCloneUpload","deposit.import.cloneUploadChunk",
                    "deposit.import.finishCloneUpload","deposit.import.abortCloneUpload","deposit.save.data","deposit.load.data")))){
            assertFalse(rows.hasNext());
        }
    }

    @Test void orderedBatchUsesExplicitTypesAndRollsBackOnDeclarationMismatch() {
        Actor a=actor();
        var commands=List.of(
            batch("class","deposit.create.class",List.of("Typed batch",Map.of())),
            batch("declaration","deposit.create.classDescriptor",List.of(ref("class"),"Code","Integer",Map.of())),
            Json.map("key","object","procedure","deposit.create.object","arguments",
                List.of(ref("class"),Map.of("descriptors",Map.of("Code",9007199254740993L))),"descriptorTypes",Map.of("Code","Integer")),
            batch("dictionary","deposit.create.classDescriptor",List.of(ref("class"),"Data","DDict",Map.of())),
            batch("set","deposit.set.descriptor",List.of(ref("object"),"Data",
                Map.of("type",Map.of("type","String","value","scientific"),"value",Map.of("type","Integer","value",1L)))));
        var result=tracked(a,a.namespace,"deposit.apply.batch",List.of(commands));
        assertEquals(5,ValueCodec.map(result.get("results")).size());

        var versioning=tracked(a,a.namespace,"deposit.set.versioningType",List.of("persistent"));
        var before=historyState(a);
        var rejected=List.of(
            batch("class","deposit.create.class",List.of("Rollback batch",Map.of())),
            batch("declaration","deposit.create.classDescriptor",List.of(ref("class"),"Code","Integer",Map.of())),
            Json.map("key","object","procedure","deposit.create.object","arguments",
                List.of(ref("class"),Map.of("descriptors",Map.of("Code","bad"))),"descriptorTypes",Map.of("Code","String")));
        String id=operationId(clock.get());
        var args=List.<Object>of(rejected);
        begin(a,id,a.namespace,"deposit.apply.batch",args);
        var failure=assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,"deposit.apply.batch",args,id));
        assertTrue(failure.getMessage().contains("VALIDATION_FAILED"),failure.getMessage());
        assertTrue(failure.getMessage().contains("commandIndex"),failure.getMessage());
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNull(core.find("Class",Map.of("namespaceId",a.namespace,"name","Rollback batch")));
            assertEquals(versioning.get("revisionId"),core.namespace(a.namespace).getProperty("headRevisionId"));
        }
        assertEquals(before,historyState(a));
    }

    @Test void incrementalDescriptorHistoryMatchesCaptureAcrossModesAndUndoRedo() {
        Actor a=actor();
        Object clazz=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Sample",
            Map.of("descriptors",List.of(Map.of("name","Score","type","Integer"))))).get("id"));
        var object=tracked(a,a.namespace,"deposit.create.object",List.of(clazz,Map.of("descriptors",Map.of("Score",10L))));
        Object objectId=object.get("id");
        tracked(a,a.namespace,"deposit.create.pattern",List.of("By score",pattern("Sample","Score",null),Map.of()));

        var points=new CopyOnWriteArrayList<String>();
        server.storageObserverForTesting(points::add);
        Map<String,Object> disabled,edit,unchanged,batch;
        Map<String,Map<String,Object>> beforeEdit,afterEdit;
        try {
            disabled=tracked(a,a.namespace,"deposit.set.descriptor",List.of(objectId,"Score",20L));
            assertFalse(disabled.containsKey("revisionId"));
            assertEquals(20L,disabled.get("value"));
            assertNoFullWriteScan(points);

            tracked(a,a.namespace,"deposit.set.versioningType",List.of("persistent"));
            beforeEdit=historyState(a);
            points.clear();
            edit=tracked(a,a.namespace,"deposit.set.descriptor",List.of(objectId,"Score",30L));
            assertNotNull(edit.get("revisionId"));
            unchanged=tracked(a,a.namespace,"deposit.set.descriptor",List.of(objectId,"Score",30L));
            assertEquals(false,unchanged.get("changed"));
            assertFalse(unchanged.containsKey("revisionId"));
            afterEdit=historyState(a);

            batch=tracked(a,a.namespace,"deposit.apply.batch",List.of(List.of(
                batch("change","deposit.set.descriptor",List.of(objectId,"Score",40L)),
                batch("revert","deposit.set.descriptor",List.of(objectId,"Score",30L)))));
            assertNoFullWriteScan(points);
        } finally {server.storageObserverForTesting(ignored->{});}

        assertHistoryParity(a,edit,new ChangeSet(beforeEdit),afterEdit);
        var reversed=historyRevision(a,batch);
        var intermediate=HistoryCodec.copy(afterEdit);
        intermediate.get(HistoryCodec.key("Descriptor",(String)edit.get("id"))).put("value",40L);
        var oracle=new ChangeSet(afterEdit);
        oracle.checkpoint(intermediate);
        assertHistoryParity(a,batch,oracle,historyState(a));
        assertEquals(historyRevision(a,edit).sequence()+1,reversed.sequence());
        assertEquals(Set.of("value"),reversed.touches().get(HistoryCodec.key("Descriptor",(String)edit.get("id"))));

        var undo=tracked(a,a.namespace,"deposit.utils.undo",List.of(batch.get("revisionId")));
        assertEquals("undo",historyRevision(a,undo).kind());
        var firstUndo=tracked(a,a.namespace,"deposit.utils.undo",List.of(edit.get("revisionId")));
        assertEquals(20L,historyState(a).get(HistoryCodec.key("Descriptor",(String)edit.get("id"))).get("value"));
        var redo=tracked(a,a.namespace,"deposit.utils.redo",List.of(firstUndo.get("revisionId")));
        assertEquals("redo",historyRevision(a,redo).kind());
        var beforeConversion=historyState(a);
        String descriptorKey=HistoryCodec.key("Descriptor",(String)edit.get("id"));
        assertEquals(30L,beforeConversion.get(descriptorKey).get("value"));
        Object declarationId=beforeConversion.values().stream().filter(image->"ClassDescriptor".equals(image.get("kind")))
            .findFirst().orElseThrow().get("id");
        var conversion=tracked(a,a.namespace,"deposit.set.classDescriptor",List.of(selector(declarationId),
            Map.of("type","Float","conversion","integerToFloat")));
        var afterConversion=historyState(a);
        assertHistoryParity(a,conversion,new ChangeSet(beforeConversion),afterConversion);
        assertEquals(30.0,afterConversion.get(descriptorKey).get("value"));
    }

    @Test void affectedPatternsUseFinalBatchSchemaAndPreserveReferencedDeletionAcrossRename() {
        Actor a=actor();
        tracked(a,a.namespace,"deposit.set.versioningType",List.of("persistent"));
        var clazz=tracked(a,a.namespace,"deposit.create.class",List.of("Sample",Map.of()));
        Object classSelector=selector(clazz.get("id"));
        var created=tracked(a,a.namespace,"deposit.create.pattern",List.of("By score",pattern("Sample","Score","Integer"),Map.of()));
        Object patternSelector=selector(created.get("id"));
        var before=historyState(a);
        var mismatch=assertThrows(ExceptionHandler.DepositException.class,()->tracked(a,a.namespace,"deposit.create.classDescriptor",
            List.of(classSelector,"Score","Float",Map.of())));
        assertTrue(mismatch.getMessage().contains("VALIDATION_FAILED"),mismatch.getMessage());
        assertEquals(before,historyState(a));

        var resolved=tracked(a,a.namespace,"deposit.create.classDescriptor",List.of(classSelector,"Score","Integer",Map.of()));
        var after=historyState(a);
        assertHistoryParity(a,resolved,new ChangeSet(before),after);
        String patternKey=HistoryCodec.key("Pattern",(String)created.get("id"));
        assertFalse(Json.write(after.get(patternKey).get("definition")).contains("\"type\""));

        Object declarationSelector=selector(resolved.get("id"));
        var rename=batch("rename","deposit.set.class",List.of(classSelector,Map.of("name","Renamed")));
        var delete=batch("delete","deposit.delete.classDescriptor",List.of(declarationSelector));
        var rejected=assertThrows(ExceptionHandler.DepositException.class,()->tracked(a,a.namespace,"deposit.apply.batch",
            List.of(List.of(rename,delete,batch("proposal","deposit.set.pattern",List.of(patternSelector,
                Map.of("definition",pattern("Renamed","Score","Integer"))))))));
        assertTrue(rejected.getMessage().contains("Class declaration is referenced by a Pattern"),rejected.getMessage());
        assertEquals(after,historyState(a));

        var accepted=tracked(a,a.namespace,"deposit.apply.batch",List.of(List.of(rename,delete,
            batch("remove","deposit.delete.pattern",List.of(patternSelector)))));
        var finalState=historyState(a);
        var renamed=HistoryCodec.copy(after);
        renamed.get(HistoryCodec.key("Class",(String)clazz.get("id"))).put("name","Renamed");
        renamed.get(patternKey).put("definition",PatternCodec.normalize(pattern("Renamed","Score",null)));
        var withoutDeclaration=HistoryCodec.copy(renamed);
        withoutDeclaration.remove(HistoryCodec.key("ClassDescriptor",(String)resolved.get("id")));
        var oracle=new ChangeSet(after);
        oracle.checkpoint(renamed);
        oracle.checkpoint(withoutDeclaration);
        assertHistoryParity(a,accepted,oracle,finalState);
        assertFalse(finalState.containsKey(patternKey));
        assertEquals("Renamed",finalState.get(HistoryCodec.key("Class",(String)clazz.get("id"))).get("name"));
    }

    @Test void incrementalHistoryMatchesCaptureForOwnedGeometryAndCascadingEdges() throws Exception {
        Actor a=actor(),other=actor();
        tracked(a,a.namespace,"deposit.set.versioningType",List.of("persistent"));
        var clazz=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Specimen",Map.of())).get("id"));
        var destination=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Destination",Map.of())).get("id"));
        Object target=tracked(a,a.namespace,"deposit.create.object",List.of(clazz,Map.of())).get("id");
        var before=historyState(a);
        var created=tracked(a,a.namespace,"deposit.create.object",List.of(clazz,Map.of("descriptors",Map.of(
            "Shape",Map.of("type","DGeometry","value",Map.of("wkt","POINT (1 2)","axisOrder","xy","dimensions",2L))))));
        var current=historyState(a);
        assertHistoryParity(a,created,new ChangeSet(before),current);

        Object source=created.get("id");
        String batchId=operationId(clock.get()),procedure="deposit.apply.batch";
        var args=List.<Object>of(List.of(
            batch("relate","deposit.create.relationship",List.of(source,target,"near",Map.of("weight",1.0))),
            batch("move","deposit.set.descriptor",List.of(source,"Shape",Map.of("wkt","POINT (3 4)","axisOrder","xy","dimensions",2L)))));
        assertEquals("REGISTERED",state(begin(a,batchId,a.namespace,procedure,args)));
        var gate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        Map<String,Object> links;
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var run=workers.submit(()->call(a,a.execution,procedure,args,batchId,()->{gate.countDown();waitFor(release);}));
            Future<?> rebuild;
            try {
                assertTrue(gate.await(10,TimeUnit.SECONDS));
                var waiting=new CountDownLatch(1);
                rebuild=workers.submit(()->{
                    try(var tx=database.beginTx()){
                        waiting.countDown();
                        new GeometryRepository(tx,new CoreRepository(tx,ZonedDateTime.now())).rebuild(other.namespace);
                        tx.commit();
                    }
                });
                assertTrue(waiting.await(10,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->rebuild.get(100,TimeUnit.MILLISECONDS));
            } finally {release.countDown();}
            links=run.get(10,TimeUnit.SECONDS);
            rebuild.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,server.transactionCountForTesting());
        var linked=historyState(a);
        assertHistoryParity(a,links,new ChangeSet(current),linked);

        var moved=tracked(a,a.namespace,"deposit.apply.batch",List.of(List.of(
            batch("move","deposit.set.membership",List.of(source,destination)))));
        var beforeDelete=historyState(a);
        assertHistoryParity(a,moved,new ChangeSet(linked),beforeDelete);
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var membership=core.membership(core.entity("Object",a.namespace,(String)source));
            assertEquals(destination.get("id"),CoreRepository.id(membership.getEndNode()));
            assertEquals(Json.write(List.of(a.namespace,source)),membership.getProperty("identityKey"));
            assertTrue(linked.containsKey(HistoryCodec.key("Membership",CoreRepository.id(membership))));
            assertFalse(core.data(membership).containsKey("identityKey"));
        }

        var removed=tracked(a,a.namespace,"deposit.delete.object",List.of(source));
        var finalState=historyState(a);
        assertHistoryParity(a,removed,new ChangeSet(beforeDelete),finalState);
        assertFalse(finalState.keySet().stream().anyMatch(key->Set.of("DGeometry","Descriptor","Relationship").contains(HistoryCodec.kindFromKey(key))));
    }

    @Test void relationshipMutationsUseScopedIdsAndCheckCurrentClassMembers() {
        Actor a=actor(),foreign=actor();
        tracked(a,a.namespace,"deposit.set.versioningType",List.of("persistent"));
        var source=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Source",Map.of())).get("id"));
        var target=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Target",Map.of())).get("id"));
        var other=selector(tracked(a,a.namespace,"deposit.create.class",List.of("Other",Map.of())).get("id"));
        Object from=tracked(a,a.namespace,"deposit.create.object",List.of(source,Map.of())).get("id");
        Object to=tracked(a,a.namespace,"deposit.create.object",List.of(target,Map.of())).get("id");
        Object self=tracked(a,a.namespace,"deposit.create.object",List.of(other,Map.of())).get("id");
        Object unrelated=tracked(a,a.namespace,"deposit.create.object",List.of(other,Map.of())).get("id");
        var create=List.of(from,to,"link",Map.of("weight",1.0));
        Object link=tracked(a,a.namespace,"deposit.create.relationship",create).get("id");
        Object reverse=tracked(a,a.namespace,"deposit.create.relationship",List.of(to,from,"link",Map.of("weight",1.0))).get("id");
        Object differentLabel=tracked(a,a.namespace,"deposit.create.relationship",List.of(from,to,"other/\"label\\",Map.of("weight",1.0))).get("id");
        Object selfLink=tracked(a,a.namespace,"deposit.create.relationship",List.of(self,self,"link",Map.of("weight",1.0))).get("id");
        for(String label:List.of("side one","side two","side three"))
            tracked(a,a.namespace,"deposit.create.relationship",List.of(self,unrelated,label,Map.of("weight",1.0)));
        Object classLink=call(a,a.control,"deposit.get.classRelationship",
            List.of(Map.of("sourceId",source.get("id"),"targetId",target.get("id"),"label","link")),null).get("id");
        Object selfClassLink=call(a,a.control,"deposit.get.classRelationship",
            List.of(Map.of("sourceId",other.get("id"),"targetId",other.get("id"),"label","link")),null).get("id");
        assertDepositError("DUPLICATE",()->tracked(a,a.namespace,"deposit.create.relationship",create));
        for(String type:List.of("RELATED_TO","MEMBER_OF")){
            assertThrows(ConstraintViolationException.class,()->{
                try(var tx=database.beginTx()){
                    var core=new CoreRepository(tx,ZonedDateTime.now());
                    var edge=type.equals("MEMBER_OF")?core.membership(core.entity("Object",a.namespace,(String)from)):
                        core.readRelationship(a.namespace,"Relationship",(String)link);
                    var original=core.readRelationship(a.namespace,"Relationship",(String)link);
                    original.setProperty("weight",99.0);
                    edge.getStartNode().createRelationshipTo(edge.getEndNode(),RelationshipType.withName(type))
                        .setProperty("identityKey",edge.getProperty("identityKey"));
                    tx.commit();
                }
            });
        }
        var projected=call(a,a.control,"deposit.get.relationship",List.of(selector(link)),null);
        assertEquals(1.0,projected.get("weight"));
        assertFalse(projected.containsKey("identityKey"));
        long revision;
        try(var tx=database.beginTx()){
            var index=tx.schema().getIndexByName("deposit_related_identity");
            tx.schema().awaitIndexOnline(index,10,TimeUnit.SECONDS);
            assertEquals(Schema.IndexState.ONLINE,tx.schema().getIndexState(index));
            try(var explained=tx.execute("""
                EXPLAIN MATCH ()-[edge:RELATED_TO {namespaceId:$namespace,id:$id}]->()
                USING RANGE INDEX SEEK edge:RELATED_TO(namespaceId,id)
                RETURN edge
                """,Map.of("namespace",a.namespace,"id",link))){
                String plan=explained.getExecutionPlanDescription().toString();
                assertTrue(plan.contains("RelationshipIndexSeek")&&plan.contains("namespaceId, id"),plan);
            }
            revision=new CoreRepository(tx,ZonedDateTime.now()).sequence(a.namespace);
        }

        var points=new ArrayList<String>();
        server.storageObserverForTesting(points::add);
        try {
            assertEquals(link,call(a,a.control,"deposit.get.relationship",List.of(selector(link)),null).get("id"));
            assertEquals(classLink,call(a,a.control,"deposit.get.classRelationship",List.of(selector(classLink)),null).get("id"));
            assertEquals(2.0,tracked(a,a.namespace,"deposit.set.relationship",List.of(link,Map.of("weight",2.0))).get("weight"));
            assertEquals("updated",tracked(a,a.namespace,"deposit.set.classRelationship",
                List.of(classLink,Map.of("comment","updated"))).get("comment"));
            assertEquals(4,Collections.frequency(points,"relationship-composite-lookup"),points.toString());

            String unknown=UUID.randomUUID().toString();
            for(var candidate:List.of(classLink,unknown))
                assertDepositError("NOT_FOUND",()->tracked(a,a.namespace,"deposit.set.relationship",List.of(candidate,Map.of("weight",9.0))));
            for(var candidate:List.of(link,unknown))
                assertDepositError("NOT_FOUND",()->tracked(a,a.namespace,"deposit.delete.classRelationship",List.of(candidate)));
            assertDepositError("NOT_FOUND",()->tracked(foreign,foreign.namespace,"deposit.delete.relationship",List.of(link)));
            assertDepositError("NOT_FOUND",()->tracked(foreign,foreign.namespace,"deposit.set.classRelationship",
                List.of(classLink,Map.of("comment","foreign"))));
            assertDepositError("VALIDATION_FAILED",()->tracked(a,a.namespace,"deposit.delete.relationship",List.of("bad-id")));

            for(var used:List.of(classLink,selfClassLink))
                assertDepositError("VALIDATION_FAILED",()->tracked(a,a.namespace,"deposit.delete.classRelationship",List.of(used)));
            var update=batch("set","deposit.set.relationship",List.of(link,Map.of("weight",3.0)));
            var removeClass=batch("removeClass","deposit.delete.classRelationship",List.of(classLink));
            var removeLink=batch("removeLink","deposit.delete.relationship",List.of(link));
            assertDepositError("VALIDATION_FAILED",()->tracked(a,a.namespace,"deposit.apply.batch",
                List.of(List.of(update,removeClass,removeLink))));
            assertDepositError("NOT_FOUND",()->tracked(a,a.namespace,"deposit.apply.batch",List.of(List.of(
                update,removeLink,removeClass,batch("invalid","deposit.delete.relationship",List.of(unknown))))));
            assertEquals(2.0,call(a,a.control,"deposit.get.relationship",List.of(selector(link)),null).get("weight"));
            assertEquals("updated",call(a,a.control,"deposit.get.classRelationship",List.of(selector(classLink)),null).get("comment"));
            assertEquals(selfLink,call(a,a.control,"deposit.get.relationship",List.of(selector(selfLink)),null).get("id"));
            assertEquals(selfClassLink,call(a,a.control,"deposit.get.classRelationship",List.of(selector(selfClassLink)),null).get("id"));
            try(var tx=database.beginTx()){
                assertEquals(revision+2,new CoreRepository(tx,ZonedDateTime.now()).sequence(a.namespace));
            }
            assertFalse(points.contains("relationship-collection"),points.toString());
            assertFalse(points.contains("history-capture"),points.toString());
            points.clear();

            var result=tracked(a,a.namespace,"deposit.apply.batch",List.of(List.of(update,removeLink,removeClass)));
            assertNotNull(result.get("revisionId"));
            var results=ValueCodec.map(result.get("results"));
            assertEquals(3.0,ValueCodec.map(results.get("set")).get("weight"));
            assertEquals(List.of(link),ValueCodec.map(results.get("removeLink")).get("deletedIds"));
            assertEquals(List.of(classLink),ValueCodec.map(results.get("removeClass")).get("deletedIds"));
            assertEquals(3,Collections.frequency(points,"relationship-composite-lookup"),points.toString());
            assertFalse(points.contains("relationship-collection"),points.toString());
            assertFalse(points.contains("history-capture"),points.toString());

            var undo=tracked(a,a.namespace,"deposit.utils.undo",List.of(result.get("revisionId")));
            assertEquals(2.0,call(a,a.control,"deposit.get.relationship",List.of(selector(link)),null).get("weight"));
            assertEquals("updated",call(a,a.control,"deposit.get.classRelationship",List.of(selector(classLink)),null).get("comment"));
            points.clear();
            var redo=tracked(a,a.namespace,"deposit.utils.redo",List.of(undo.get("revisionId")));
            assertEquals("redo",historyRevision(a,redo).kind());
            assertEquals(2,Collections.frequency(points,"relationship-composite-lookup"),points.toString());
            assertFalse(points.contains("relationship-collection"),points.toString());
            points.clear();

            assertEquals(List.of(selfLink),tracked(a,a.namespace,"deposit.delete.relationship",List.of(selfLink)).get("deletedIds"));
            assertEquals(List.of(selfClassLink),tracked(a,a.namespace,"deposit.delete.classRelationship",List.of(selfClassLink)).get("deletedIds"));
            assertFalse(points.contains("relationship-collection"),points.toString());
            assertFalse(points.contains("history-capture"),points.toString());
        } finally {server.storageObserverForTesting(ignored->{});}
        assertDepositError("NOT_FOUND",()->call(a,a.control,"deposit.get.relationship",List.of(selector(link)),null));
        assertDepositError("NOT_FOUND",()->call(a,a.control,"deposit.get.classRelationship",List.of(selector(classLink)),null));
        assertEquals(reverse,call(a,a.control,"deposit.get.relationship",List.of(selector(reverse)),null).get("id"));
        assertEquals(differentLabel,call(a,a.control,"deposit.get.relationship",List.of(selector(differentLabel)),null).get("id"));
        try(var tx=database.beginTx()){
            assertEquals(revision+7,new CoreRepository(tx,ZonedDateTime.now()).sequence(a.namespace));
        }
    }

    private static void assertDepositError(String code,org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(ExceptionHandler.DepositException.class,action);
        assertEquals(code,Json.read(failure.getMessage().substring("DEPOSIT_ERROR ".length())).get("code"));
    }

    private static Map<String,Object> selector(Object id) {return Map.of("id",id);}

    private static Map<String,Object> pattern(String clazz,String field,String proposal) {
        Map<String,Object> descriptor=proposal==null?Map.of("name",field):Map.of("name",field,"type",proposal);
        return Map.of("objects",Map.of("root",Map.of("className",clazz,"root",true,"descriptors",List.of(descriptor))));
    }

    private static void assertNoFullWriteScan(List<String> points) {
        assertTrue(points.contains("history-before-receipt"));
        assertFalse(points.contains("history-capture"),points.toString());
        assertFalse(points.contains("pattern-definitions-scan"),points.toString());
    }

    private Map<String,Map<String,Object>> historyState(Actor actor) {
        try(var tx=database.beginTx()) {
            return new HistoryRepository(tx,new CoreRepository(tx,ZonedDateTime.now())).capture(actor.namespace);
        }
    }

    private Revision historyRevision(Actor actor,Map<String,Object> result) {
        assertNotNull(result.get("revisionId"),result.toString());
        try(var tx=database.beginTx()) {
            return new HistoryRepository(tx,new CoreRepository(tx,ZonedDateTime.now())).revision(actor.namespace,(String)result.get("revisionId"));
        }
    }

    private void assertHistoryParity(Actor actor,Map<String,Object> result,ChangeSet oracle,Map<String,Map<String,Object>> finalState) {
        oracle.checkpoint(finalState);
        var stored=historyRevision(actor,result);
        assertEquals(oracle.touches,stored.touches());
        assertEquals(oracle.dependencies,stored.dependencies());
        var before=new TreeMap<String,Map<String,Object>>();
        var after=new TreeMap<String,Map<String,Object>>();
        for(String key:oracle.touches.keySet()) {
            if(oracle.before.containsKey(key))before.put(key,oracle.before.get(key));
            if(oracle.after.containsKey(key))after.put(key,oracle.after.get(key));
        }
        assertEquals(Json.write(before),Json.write(stored.before()));
        assertEquals(Json.write(after),Json.write(stored.after()));
    }

    @Test void strongestBatchRoleAndFinalAuthorizationUseCurrentGrants() {
        Actor a=actor(), otherOwner=actor();
        try(var tx=database.beginTx()) {
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.setRole(otherOwner.id,a.namespace,"owner");
            core.setRole(a.id,a.namespace,"editor");
            tx.commit();
        }
        var commands=List.of(
            batch("class","deposit.create.class",List.of("Allowed to edit",Map.of())),
            batch("namespace","deposit.set.namespace",List.of(Map.of("description","Owner only"))));
        var denied=assertThrows(ExceptionHandler.DepositException.class,()->begin(a,operationId(clock.get()),a.namespace,
            "deposit.apply.batch",List.of(commands)));
        assertTrue(denied.getMessage().contains("NOT_FOUND"),denied.getMessage());
        var result=tracked(a,a.namespace,"deposit.apply.batch",List.of(commands.subList(0,1)));
        assertEquals("Allowed to edit",ValueCodec.map(ValueCodec.map(result.get("results")).get("class")).get("name"));

        try(var tx=database.beginTx()) {
            new CoreRepository(tx,ZonedDateTime.now()).setRole(a.id,a.namespace,"owner");
            tx.commit();
        }
        var granted=List.of(batch("namespace","deposit.set.namespace",List.of(Map.of("description","Owner only"))));
        tracked(a,a.namespace,"deposit.apply.batch",List.of(granted));

        String id=operationId(clock.get());
        String route="deposit.set.namespaceRole";
        var arguments=List.<Object>of(Map.of("id",a.id),"editor");
        begin(a,id,a.namespace,route,arguments);
        var revoked=assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,route,arguments,id));
        assertTrue(revoked.getMessage().contains("NOT_FOUND"),revoked.getMessage());
        try(var tx=database.beginTx()) {
            assertEquals("owner",new CoreRepository(tx,ZonedDateTime.now()).role(a.id,a.namespace));
        }
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
    }

    @Test void selfDisableCommitsAndRevokesTheSession() {
        Actor a=actor(), otherOwner=actor();
        try(var tx=database.beginTx()) {
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.find("User","id",a.id).setProperty("serverRole","administrator");
            core.find("User","id",otherOwner.id).setProperty("serverRole","administrator");
            core.setRole(otherOwner.id,a.namespace,"owner");
            tx.commit();
        }
        String id=operationId(clock.get());
        String route="deposit.set.user";
        var arguments=List.<Object>of(Map.of("id",a.id),Map.of("enabled",false));
        begin(a,id,"server",route,arguments);
        assertEquals(false,call(a,a.execution,route,arguments,id).get("enabled"));
        assertThrows(ExceptionHandler.DepositException.class,()->status(a,id,"server"));
        try(var tx=database.beginTx()) {
            assertEquals("COMMITTED",server.storage.attempts.find(a.id,"server",id).state());
        }
    }

    private static Map<String,Object> batch(String key,String procedure,List<?> arguments) {
        return Json.map("key",key,"procedure",procedure,"arguments",arguments);
    }

    private static Map<String,Object> ref(String key) {return Map.of("ref",key,"field","id");}

    @Test void structuredExchangeAcceptsValidGraphAndRejectsMembershipAndManifestErrors() {
        Actor a=actor();
        String source=UUID.randomUUID().toString(), first=UUID.randomUUID().toString(),
            second=UUID.randomUUID().toString(), object=UUID.randomUUID().toString();
        var manifest=exchangeManifest(source);
        var graph=emptyExchangeGraph();
        graph.put("classes",List.of(record("Class",first,source,"name","First","descriptors",List.of()),
            record("Class",second,source,"name","Second","descriptors",List.of())));
        graph.put("objects",List.of(record("Object",object,source,"classId",first)));
        graph.put("memberships",List.of(record("Membership",UUID.randomUUID().toString(),source,"objectId",object,"classId",first)));
        var reference=uploadSnapshot(a,a.namespace,manifest,graph,Map.of());
        assertNotNull(tracked(a,a.namespace,"deposit.import.analyze",List.of(packageSource(reference),Map.of())).get("planId"));

        var wrongManifest=new LinkedHashMap<>(manifest);
        wrongManifest.put("scope","elsewhere");
        assertInvalidSnapshot(a,wrongManifest,graph);
        graph.put("memberships",List.of(record("Membership",UUID.randomUUID().toString(),source,"objectId",object,"classId",second)));
        assertInvalidSnapshot(a,manifest,graph);
    }

    private void assertInvalidSnapshot(Actor a,Map<String,Object> manifest, Map<String,Object> graph) {
        var failure=assertThrows(ExceptionHandler.DepositException.class,()->uploadSnapshot(a,a.namespace,manifest,graph,Map.of()));
        assertTrue(failure.getMessage().contains("VALIDATION_FAILED"),failure.getMessage());
    }

    @Test void rowImportRejectsDuplicateIdsAndUnknownRelationshipTemplatesOnServer() {
        Actor a=actor();
        var row=Map.of("id","sample","values",Map.of("code","X"));
        var targets=Map.of("code",Map.of("object","item","className","Specimen","descriptor","Code","type","String"));
        var valid=List.<Object>of(Map.of("rows",List.of(row)),targets,List.of(),Map.of());
        var plan=tracked(a,a.namespace,"deposit.import.rows",valid);
        var applied=tracked(a,a.namespace,"deposit.import.apply",List.of(plan.get("planId")));
        assertEquals("APPLIED",applied.get("state"));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertEquals(1L,core.sequence(a.namespace));
            assertNotNull(core.find("Class",Map.of("namespaceId",a.namespace,"name","Specimen")));
        }
        for(var invalid:List.of(
                List.<Object>of(Map.of("rows",List.of(row,row)),targets,List.of(),Map.of()),
                List.<Object>of(Map.of("rows",List.of(Map.of("id","bad/id","values",row.get("values")))),targets,List.of(),Map.of()),
                List.<Object>of(Map.of("rows",List.of(row)),targets,
                    List.of(Map.of("source","missing","target","item","label","links")),Map.of()))) {
            String id=operationId(clock.get());
            begin(a,id,a.namespace,"deposit.import.rows",invalid);
            var failure=assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,"deposit.import.rows",invalid,id));
            assertTrue(failure.getMessage().contains("VALIDATION_FAILED"),failure.getMessage());
        }
    }

    @Test void patternFormAppliesItsVerifiedPlan() {
        Actor a=actor();
        tracked(a,a.namespace,"deposit.create.class",List.of("Form sample",Map.of(
            "descriptors",List.of(Map.of("name","Code","type","String")))));
        tracked(a,a.namespace,"deposit.create.pattern",List.of("Form",pattern("Form sample","Code",null),Map.of()));
        var plan=tracked(a,a.namespace,"deposit.import.dataByPattern",List.of("Form",Map.of("root",Map.of("Code","ABC")),Map.of()));
        var result=tracked(a,a.namespace,"deposit.import.apply",List.of(plan.get("planId")));
        assertEquals("APPLIED",result.get("state"));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var objects=core.nodes("Object",Map.of("namespaceId",a.namespace));
            assertEquals(1,objects.size());
            assertEquals("ABC",core.descriptorValue(core.descriptor(objects.getFirst(),"Code")));
        }
    }

    @Test void interruptionAfterOcflPublicationReclaimsOnlyTheUncommittedObject() throws Exception {
        Actor a=actor();
        String upload=beginResource(a);
        writeResource(a,upload);
        String resource;
        try(var tx=database.beginTx()){
            resource=server.storage.transfers.transfer(upload).resource();
        }
        String finish="deposit.resource.finishUpload", id=operationId(clock.get());
        var args=List.<Object>of(upload);
        begin(a,id,a.namespace,finish,args);
        server.storageObserverForTesting(point->{if(point.equals("publication-after"))throw new IllegalStateException("Simulated interruption");});
        try{
            assertThrows(IllegalStateException.class,()->call(a,a.execution,finish,args,id));
        }finally{server.storageObserverForTesting(point->{});}
        assertTrue(server.storage.content.exists(resource));
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
        new OperationMaintenance(server,database).tick();
        server.storage.recover();
        server.storage.recover();
        assertFalse(server.storage.content.exists(resource));
    }

    @Test void aFailedStagingWriteFencesTheUploadAndReleasesItsBytes() throws Exception {
        Actor a=actor();
        String upload=beginResource(a);
        Path staged=server.storage.files.path("staging/"+a.namespace+"/"+upload+".part");
        byte[] bytes={1,2};
        String chunk="deposit.resource.uploadChunk",id=operationId(clock.get());
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes));
        begin(a,id,a.namespace,chunk,part);
        server.storageObserverForTesting(point->{
            if(point.equals("chunk-flushed"))throw new java.io.UncheckedIOException(new java.io.IOException("Simulated staging I/O failure"));
        });
        try{
            assertThrows(java.io.UncheckedIOException.class,()->call(a,a.execution,chunk,part,id));
        }finally{server.storageObserverForTesting(point->{});}
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
        assertThrows(ExceptionHandler.DepositException.class,()->begin(a,operationId(clock.get()),a.namespace,chunk,part));
        new OperationMaintenance(server,database).tick();
        server.storage.recover();
        assertFalse(Files.exists(staged));
        assertEquals("complete",status(a,id,a.namespace).get("cleanupState"));
    }

    @Test void trackedCsvLoadStagesRowsAndKeepsAnAbsoluteExpiry() throws Exception {
        Actor a=actor();
        byte[] bytes="field\r\nvalue\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String begin="deposit.resource.beginUpload", uploadId=operationId(clock.get());
        var args=List.<Object>of("table.csv",(long)bytes.length,ResourceFiles.checksum(bytes),"text/csv");
        begin(a,uploadId,a.namespace,begin,args);
        String upload=(String)call(a,a.execution,begin,args,uploadId).get("uploadId");
        String chunk="deposit.resource.uploadChunk", chunkId=operationId(clock.get());
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes));
        begin(a,chunkId,a.namespace,chunk,part);
        call(a,a.execution,chunk,part,chunkId);
        String finish="deposit.resource.finishUpload", finishId=operationId(clock.get());
        var finishArgs=List.<Object>of(upload);
        begin(a,finishId,a.namespace,finish,finishArgs);
        String resource=(String)call(a,a.execution,finish,finishArgs,finishId).get("resourceId");
        String load="deposit.load.csv", loadId=operationId(clock.get());
        var loadArgs=List.<Object>of(resource,Map.of());
        begin(a,loadId,a.namespace,load,loadArgs);
        String table=(String)call(a,a.execution,load,loadArgs,loadId).get("tableId");
        long created=clock.get();
        clock.addAndGet(1000);
        assertEquals(1,((List<?>)call(a,a.control,"deposit.get.tablePage",List.of(table,Map.of()),null).get("rows")).size());
        assertEquals(created+OperationAttemptRepository.RETENTION,server.storage.tables.table(table).expires());
        assertTrue(server.storage.tables.protects(resource,clock.get()));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.find("User","id",a.id).setProperty("serverRole","administrator");
            tx.commit();
        }
        clock.set(created+ResourceFiles.LEASE);
        Actor admin=renew(a);
        var jobs=new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,message->{});
        jobs.process(resource,false);
        var scope=Json.map("scope","namespace","minimumAgeHours",0L);
        assertFalse(((List<?>)call(admin,admin.control,"deposit.admin.pruneResources",List.of(scope),null).get("candidateIds")).contains(resource));
        jobs.expireTables();
        assertFalse(server.storage.tables.protects(resource,clock.get()));
        assertThrows(ExceptionHandler.DepositException.class,()->call(admin,admin.control,"deposit.get.tablePage",List.of(table,Map.of()),null));
        scope.put("dryRun",false);
        var prune=tracked(admin,admin.namespace,"deposit.admin.pruneResources",List.of(scope));
        assertEquals(List.of(resource),prune.get("candidateIds"));
        String job=(String)prune.get("pruneId");
        jobs.collect(job+"/"+resource);
        assertTrue(server.storage.content.exists(resource));
        jobs.collect(job+"/"+resource);
        var collection=call(admin,admin.control,"deposit.admin.pruneResourcesStatus",List.of(job),null);
        assertEquals("completed",collection.get("state"));
        assertTrue((long)collection.get("reclaimedBytes")>=bytes.length);
        assertFalse(server.storage.content.exists(resource));
    }

    @Test void fullSizeChunkStreamsThroughTrackedUploadAndOcfl() throws Exception {
        Actor a=actor();
        byte[] bytes=new byte[ResourceFiles.MAX_CHUNK];
        new Random(19).nextBytes(bytes);
        String digest=ResourceFiles.checksum(bytes), begin="deposit.resource.beginUpload", id=operationId(clock.get());
        var args=List.<Object>of("bounded.bin",(long)bytes.length,digest,"application/octet-stream");
        begin(a,id,a.namespace,begin,args);
        String upload=(String)call(a,a.execution,begin,args,id).get("uploadId");
        String step=operationId(clock.get()),chunk="deposit.resource.uploadChunk";
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes));
        begin(a,step,a.namespace,chunk,part);
        assertEquals((long)bytes.length,call(a,a.execution,chunk,part,step).get("nextOffset"));
        assertEquals(bytes.length,server.storage.transfers.transfer(upload).nextOffset());
        String finish=operationId(clock.get()), procedure="deposit.resource.finishUpload";
        var finalArgs=List.<Object>of(upload);
        begin(a,finish,a.namespace,procedure,finalArgs);
        String resource=(String)call(a,a.execution,procedure,finalArgs,finish).get("resourceId");
        try(var tx=database.beginTx()){
            var loc=new CoreRepository(tx,ZonedDateTime.now()).find("DResource","id",resource);
            var stored=new ResourceStorage.Content(resource,(String)loc.getProperty("storedFilename"),bytes.length,digest);
            try(var input=server.storage.content.open(stored,0,bytes.length)){
                byte[] buffer=new byte[64*1024];
                int count,offset=0;
                while((count=input.read(buffer))!=-1){
                    assertTrue(Arrays.equals(bytes,offset,offset+count,buffer,0,count));
                    offset+=count;
                }
                assertEquals(bytes.length,offset);
            }
        }
    }

    @Test void exchangeExportInspectAndDirectImport() throws Exception {
        Actor source=actor();
        tracked(source,source.namespace,"deposit.create.class",List.of("Exchange specimen",Map.of()));
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var params=Map.of("id",reference.get("packageId"),"grant",reference.get("grant"),"token",source.token);
        var inspection=ValueCodec.map(cypher(source.control,
            "CALL deposit.exchange.inspect($id,$grant,$token) YIELD data RETURN data",params).get("data"));
        assertEquals("namespace",ValueCodec.map(inspection.get("manifest")).get("scope"));
        assertEquals(1L,inspection.get("memberCount"));
        byte[] archive=exchangeBytes(source,reference,inspection,"package");
        assertEquals(reference.get("bytes"),(long)archive.length);
        assertEquals(reference.get("sha256"),ResourceFiles.checksum(archive));
        Actor receiver=receivingNamespace(source);
        var analysis=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(reference),Map.of()));
        assertNotNull(analysis.get("planId"));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Exchange specimen")));
            assertTrue(core.nodes("DResource",Map.of("namespaceId",source.namespace)).isEmpty());
        }
        tracked(receiver,receiver.namespace,"deposit.import.apply",List.of(analysis.get("planId")));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNotNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Exchange specimen")));
        }
    }

    @Test void discardPlanFencesDelayedApplyAndRemovesStaging() throws Exception {
        Actor source=actor(), receiver=receivingNamespace(source);
        tracked(source,source.namespace,"deposit.create.class",List.of("Discarded specimen",Map.of()));
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var args=List.<Object>of(packageSource(reference),Map.of());
        var plan=tracked(receiver,receiver.namespace,"deposit.import.analyze",args);
        String planId=(String)plan.get("planId"), delayed=operationId(clock.get());
        List<Object> apply=List.of(planId);
        assertEquals("REGISTERED",state(begin(receiver,delayed,receiver.namespace,"deposit.import.apply",apply)));
        String path;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            path=server.storage.importPlans.plan(planId).stagingPath();
            assertTrue(Files.exists(server.storage.files.path(path)));
        }
        var params=Map.<String,Object>of("plan",planId,"token",receiver.token);
        String discard="CALL deposit.import.discardPlan($plan,$token) YIELD data RETURN data";
        assertEquals("cancelled",ValueCodec.map(cypher(receiver.control,discard,params).get("data")).get("state"));
        assertEquals("cancelled",ValueCodec.map(cypher(receiver.control,discard,params).get("data")).get("state"));
        assertFalse(Files.exists(server.storage.files.path(path)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(receiver,receiver.execution,"deposit.import.apply",apply,delayed));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Discarded specimen")));
        }
        var fresh=tracked(receiver,receiver.namespace,"deposit.import.analyze",args);
        assertNotEquals(planId,fresh.get("planId"));
        tracked(receiver,receiver.namespace,"deposit.import.apply",List.of(fresh.get("planId")));
        assertEquals("applied",call(receiver,receiver.control,"deposit.import.discardPlan",List.of(fresh.get("planId")),null).get("state"));
    }

    @Test void disconnectedPlanIsFencedAndReclaimedBeforeAReplacementAnalysis() throws Exception {
        Actor source=actor(), receiver=receivingNamespace(source);
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var args=List.<Object>of(packageSource(reference),Map.of());
        String id=(String)tracked(receiver,receiver.namespace,"deposit.import.analyze",args).get("planId");
        String path;
        try(var tx=database.beginTx()){
            path=server.storage.importPlans.plan(id).stagingPath();
            assertTrue(Files.isRegularFile(server.storage.files.path(path)));
        }
        connections.remove(receiver.execution);
        Actor replacement=new Actor(receiver.id,receiver.token,receiver.namespace,addConnection(),receiver.control);
        assertTrue(assertThrows(ExceptionHandler.DepositException.class,()->begin(replacement,operationId(clock.get()),
            receiver.namespace,"deposit.import.apply",List.of(id))).getMessage().contains("PLAN_FENCED"));
        var jobs=new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,message->{});
        jobs.expireImportPlans();
        assertFalse(Files.exists(server.storage.files.path(path)));
        assertEquals("cancelled",call(replacement,replacement.control,"deposit.get.importPlanMetadata",List.of(id),null).get("state"));
        assertNotEquals(id,tracked(replacement,replacement.namespace,"deposit.import.analyze",args).get("planId"));
    }

    @Test void receivingUploadDisposalKeepsRollbackReceiptAccessible() throws Exception {
        Actor source=actor(), receiver=receivingNamespace(source);
        var exported=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        byte[] zip=exchangeBytes(source,exported,inspectExchange(source,exported),"package");
        String upload=beginExchange(receiver,receiver.namespace,"zip",Map.of());
        exchangeChunk(receiver,receiver.namespace,upload,"package",0,zip);
        var packageRef=tracked(receiver,receiver.namespace,"deposit.exchange.finishUpload",List.of(upload));
        var analysis=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(packageRef),Map.of()));
        String apply=operationId(clock.get());
        var arguments=List.of(analysis.get("planId"));
        begin(receiver,apply,receiver.namespace,"deposit.import.apply",arguments);
        connections.remove(receiver.execution);
        call(receiver,receiver.control,"deposit.exchange.discard",List.of(packageRef.get("packageId"),packageRef.get("grant")),null);
        assertEquals("CANCELLED_ROLLED_BACK",state(status(receiver,apply,receiver.namespace)));
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(receiver.id,receiver.namespace,null);
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->status(receiver,apply,receiver.namespace));
    }

    @Test void importResultLeaseAndCommitProofOutliveAdmissionIndependently() throws Exception {
        Actor source=actor(), receiver=receivingNamespace(source);
        tracked(source,source.namespace,"deposit.create.class",List.of("First mapped class",Map.of()));
        tracked(source,source.namespace,"deposit.create.class",List.of("Second mapped class",Map.of()));
        var exported=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        byte[] zip=exchangeBytes(source,exported,inspectExchange(source,exported),"package");
        String upload=beginExchange(receiver,receiver.namespace,"zip",Map.of());
        exchangeChunk(receiver,receiver.namespace,upload,"package",0,zip);
        var received=tracked(receiver,receiver.namespace,"deposit.exchange.finishUpload",List.of(upload));
        var analysis=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(received),Map.of()));
        assertEquals("PLAN_READY",analysis.get("state"));
        String plan=(String)analysis.get("planId");
        var inspected=call(receiver,receiver.control,"deposit.get.importPlan",List.of(plan),null);
        var analysisLease=ValueCodec.map(inspected.get("analysisResult"));
        assertEquals(analysis.get("resultId"),analysisLease.get("resultId"));
        assertNotEquals(analysis.get("leaseId"),analysisLease.get("leaseId"));
        assertEquals(2L,ValueCodec.map(ValueCodec.map(analysisLease.get("collections")).get("classMap")).get("count"));
        Actor owner=actor();
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(owner.id,receiver.namespace,"owner");
            tx.commit();
        }
        Actor otherOwner=renew(new Actor(owner.id,null,receiver.namespace,owner.execution,owner.control));
        assertEquals(plan,call(otherOwner,otherOwner.control,"deposit.get.importPlanMetadata",List.of(plan),null).get("planId"));
        assertTrue(assertThrows(ExceptionHandler.DepositException.class,
            ()->call(otherOwner,otherOwner.control,"deposit.get.importPlan",List.of(plan),null)).getMessage().contains("FORBIDDEN"));
        var applied=tracked(receiver,receiver.namespace,"deposit.import.apply",List.of(plan));
        assertEquals("APPLIED",applied.get("state"));
        String result=(String)applied.get("resultId"), operation=(String)applied.get("operationId");
        var pageParams=Json.map("id",result,"lease",applied.get("leaseId"),"collection","identityMappings","token",receiver.token);
        var registeredPage=ValueCodec.map(cypher(receiver.control,
            "CALL deposit.get.importResultPage($id,$lease,$collection,null,1,$token) YIELD data RETURN data",pageParams).get("data"));
        var kind=ValueCodec.map(((List<?>)registeredPage.get("entries")).getFirst());
        assertNotNull(kind.get("key"));
        var jobs=new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,message->{});
        long original=clock.get(), expiry=(long)applied.get("expiresAt");
        assertEquals(original+ImportResultContract.RETENTION,expiry);
        try {
            clock.set(expiry-1);
            Actor reader=renew(receiver);
            var lease=call(reader,reader.control,"deposit.get.importResult",List.of(result),null);
            long deadline=(long)lease.get("leaseExpiresAt");
            Object leaseId=lease.get("leaseId");
            var first=call(reader,reader.control,"deposit.get.importResultPage",Arrays.asList(result,leaseId,"identityMappings",null,1L),null);
            Object cursor=first.get("nextCursor");
            assertNotNull(cursor);
            var another=call(reader,reader.control,"deposit.get.importResult",List.of(result),null);
            assertThrows(ExceptionHandler.DepositException.class,()->call(reader,reader.control,"deposit.get.importResultPage",
                List.of(result,another.get("leaseId"),"identityMappings",cursor,1L),null));
            clock.set(expiry);
            jobs.expireImportResults();
            assertTrue(assertThrows(ExceptionHandler.DepositException.class,
                ()->call(reader,reader.control,"deposit.get.importResult",List.of(result),null)).getMessage().contains("RESULT_EXPIRED"));
            var second=call(reader,reader.control,"deposit.get.importResultPage",List.of(result,leaseId,"identityMappings",cursor,1L),null);
            assertNotEquals(first.get("entries"),second.get("entries"));
            var committed=status(reader,operation,receiver.namespace);
            assertEquals("COMMITTED",state(committed));
            assertEquals("RESULT_EXPIRED",committed.get("code"));
            assertEquals(result,ValueCodec.map(committed.get("result")).get("resultId"));
            clock.set(deadline);
            Actor renewed=renew(receiver);
            assertTrue(assertThrows(ExceptionHandler.DepositException.class,
                ()->call(renewed,renewed.control,"deposit.get.importResultPage",List.of(result,leaseId,"identityMappings",cursor,1L),null))
                .getMessage().contains("RESULT_EXPIRED"));
            clock.set(expiry+ImportResultContract.READER);
            assertThrows(ExceptionHandler.DepositException.class,()->inspectExchange(renewed,received));
            var maintenance=new OperationMaintenance(server,database);
            maintenance.tick();
            assertNotNull(server.storage.attempts.find(receiver.id,receiver.namespace,operation));
            // Each maintenance pass collects only one bounded batch. Other
            // methods of this shared fixture can have equally old results.
            for(int pass=0;pass<32;pass++) {
                try(var tx=database.beginTx()) {
                    if(server.storage.importResults.root(result,null)==null)break;
                }
                jobs.expireImportResults();
            }
            try(var tx=database.beginTx()) {
                var core=new CoreRepository(tx,ZonedDateTime.now());
                assertNull(server.storage.importResults.root(result,null));
                var receipt=core.receipt(receiver.id,receiver.namespace,operation);
                assertEquals(result,receipt.importResultId());

                assertTrue(receipt.resultCollected());
                assertTrue(ImportResultContract.verifiesResult(server.storage.importResults,receipt,receiver.namespace));
            }
            assertEquals("COMMITTED",state(status(renewed,operation,receiver.namespace)));
            jobs.expireImportPlans();
            maintenance.tick();
            assertEquals("OUTCOME_UNKNOWN",state(status(renewed,operation,receiver.namespace)));
            assertNull(server.storage.receipts.find(receiver.id,receiver.namespace,operation,null));
            assertTrue(server.storage.operationalState.admission().rejectedThrough()>=OperationAttemptRepository.idTime(operation));
            var rejected=assertThrows(ExceptionHandler.DepositException.class,()->
                begin(renewed,operation,receiver.namespace,"deposit.import.apply",List.of(plan)));
            assertTrue(rejected.getMessage().contains("OPERATION_ID_EXPIRED"));
            assertThrows(ExceptionHandler.DepositException.class,()->
                call(renewed,renewed.execution,"deposit.import.apply",List.of(plan),operation));
        } finally {
            clock.set(Math.max(clock.get(),original));
        }
    }

    @Test void cloneReceiptStaysSanitizedWhenSourceOrDestinationAccessChanges() {
        Actor source=actor();
        tracked(source,source.namespace,"deposit.create.class",List.of("Clone specimen",Map.of()));
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).namespace(source.namespace).setProperty("name","renamed-"+source.namespace);
            tx.commit();
        }
        var clone=tracked(source,"server","deposit.import.cloneNamespace",List.of(packageSource(reference)));
        assertEquals("COMMITTED",clone.get("state"));
        String target=(String)clone.get("namespaceId"), id=(String)clone.get("operationId");
        String result=(String)clone.get("resultId");
        var page=Arrays.asList(result,clone.get("leaseId"),"identityMappings",null,1L);
        assertEquals(1,((List<?>)call(source,source.control,"deposit.get.importResultPage",page,null).get("entries")).size());
        Actor outsider=actor();
        assertEquals("OUTCOME_UNKNOWN",state(status(outsider,id,"server")));
        assertThrows(ExceptionHandler.DepositException.class,()->call(outsider,outsider.control,"deposit.get.importResultPage",page,null));
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(source.id,target,null);
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->call(source,source.control,"deposit.get.importResultPage",page,null));
        assertSanitizedClone(source,id);
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(source.id,target,"owner");
            tx.commit();
        }
        long now=clock.get();
        try {
            clock.set((long)reference.get("expiresAt"));
            Actor renewed=renew(source);
            assertThrows(ExceptionHandler.DepositException.class,()->call(renewed,renewed.control,"deposit.get.importResult",List.of(result),null));
            assertSanitizedClone(renewed,id);
        } finally {
            clock.set(now);
        }
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(source.id,source.namespace,null);
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->call(source,source.control,"deposit.get.importResultPage",page,null));
        assertSanitizedClone(source,id);
        String targetName;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.setRole(source.id,source.namespace,"owner");
            targetName=(String)core.namespace(target).getProperty("name");
            tx.commit();
        }
        assertEquals(result,call(source,source.control,"deposit.get.importResult",List.of(result),null).get("resultId"));

        Actor destination=renew(new Actor(source.id,null,target,addConnection(),addConnection()));
        tracked(destination,"server","deposit.delete.namespace",List.of(targetName));
        assertThrows(ExceptionHandler.DepositException.class,()->call(source,source.control,"deposit.get.importResult",List.of(result),null));
        assertSanitizedClone(source,id);
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).find("User","id",source.id).setProperty("enabled",false);
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->status(source,id,"server"));
        assertThrows(ExceptionHandler.DepositException.class,()->call(source,source.control,"deposit.get.importResultPage",page,null));
    }

    private void assertSanitizedClone(Actor actor,String operation) {
        var receipt=status(actor,operation,"server");
        assertEquals("COMMITTED",state(receipt));
        assertNull(receipt.get("code"));
        assertNull(receipt.get("result"));
    }

    @Test void exchangeBadCentralDirectoryFencesUploadBeforePublication() throws Exception {
        Actor actor=actor();
        Path archive=directory.resolve("malformed-intake.zip");
        var manifest=exchangeManifest(actor.namespace);
        manifest.put("packageId",UUID.randomUUID().toString());
        manifest.put("depositVersion",DepositInfo.version());
        ExchangePackageCodec.write(archive,manifest,emptyExchangeGraph(),null,Map.of());
        byte[] bytes=Files.readAllBytes(archive);
        int central=java.nio.ByteBuffer.wrap(bytes,bytes.length-6,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        bytes[central]^=1;
        String upload=beginExchange(actor,"server","zip",Map.of());
        exchangeChunk(actor,"server",upload,"package",0,bytes);
        String finish=operationId(clock.get());
        var args=List.<Object>of(upload);
        begin(actor,finish,"server","deposit.exchange.finishUpload",args);
        var rejected=assertThrows(ExceptionHandler.DepositException.class,
            ()->call(actor,actor.execution,"deposit.exchange.finishUpload",args,finish));
        assertTrue(rejected.getMessage().contains("VALIDATION_FAILED"),rejected.getMessage());
        assertEquals("FAILED_ROLLED_BACK",state(status(actor,finish,"server")));
        assertThrows(ExceptionHandler.DepositException.class,
            ()->begin(actor,operationId(clock.get()),"server","deposit.exchange.finishUpload",args));
        new OperationMaintenance(server,database).tick();
        server.storage.recover();
        assertFalse(Files.exists(server.storage.files.path("staging/exchange/"+upload)));
        assertEquals("complete",status(actor,finish,"server").get("cleanupState"));
    }

    @Test void exchangeSourceRevocationDeniesLeasedReadAndApply() {
        Actor source=actor();
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var inspection=inspectExchange(source,reference);
        Actor outsider=actor();
        assertThrows(ExceptionHandler.DepositException.class,()->inspectExchange(outsider,reference));
        Actor receiver=receivingNamespace(source);
        var analysis=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(reference),Map.of()));
        String apply=operationId(clock.get());
        var args=List.of(analysis.get("planId"));
        begin(receiver,apply,receiver.namespace,"deposit.import.apply",args);
        try(var tx=database.beginTx()){
            new CoreRepository(tx,ZonedDateTime.now()).setRole(source.id,source.namespace,null);
            tx.commit();
        }
        var read=List.of(reference.get("packageId"),reference.get("grant"),inspection.get("leaseId"),"package",0L,1L);
        assertThrows(ExceptionHandler.DepositException.class,()->call(source,source.control,"deposit.exchange.readChunk",read,null));
        assertThrows(ExceptionHandler.DepositException.class,()->call(receiver,receiver.execution,"deposit.import.apply",args,apply));
        try(var tx=database.beginTx()){
            assertEquals(0L,new CoreRepository(tx,ZonedDateTime.now()).sequence(receiver.namespace));
        }
    }

    @Test void verifiedImportRejectsChangedArchiveAndStaleTarget() throws Exception {
        Actor source=actor(),receiver=receivingNamespace(source);
        tracked(source,source.namespace,"deposit.create.class",List.of("Verified specimen",Map.of()));
        var reference=tracked(source,source.namespace,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var sourceArchive=server.storage.files.path("staging/exchange/"+reference.get("packageId")+"/package.zip");
        byte[] original=Files.readAllBytes(sourceArchive),changed=original.clone();
        changed[changed.length/2]^=1;
        var analyze=List.<Object>of(packageSource(reference),Map.of());
        Files.write(sourceArchive,changed);
        var rejected=assertThrows(ExceptionHandler.DepositException.class,
            ()->tracked(receiver,receiver.namespace,"deposit.import.analyze",analyze));
        assertTrue(rejected.getMessage().contains("RESOURCE_CORRUPT"),rejected.getMessage());
        Files.write(sourceArchive,original);
        var analysis=tracked(receiver,receiver.namespace,"deposit.import.analyze",analyze);
        var apply=List.of(analysis.get("planId"));
        Path staged;
        try(var tx=database.beginTx()){
            var plan=server.storage.importPlans.plan((String)analysis.get("planId"));
            staged=server.storage.files.path(plan.stagingPath());
        }
        assertArrayEquals(original,Files.readAllBytes(staged));
        Files.write(staged,changed);
        String operation=operationId(clock.get());
        begin(receiver,operation,receiver.namespace,"deposit.import.apply",apply);
        rejected=assertThrows(ExceptionHandler.DepositException.class,
            ()->call(receiver,receiver.execution,"deposit.import.apply",apply,operation));
        assertTrue(rejected.getMessage().contains("RESOURCE_CORRUPT"),rejected.getMessage());
        assertEquals("FAILED_ROLLED_BACK",state(status(receiver,operation,receiver.namespace)));
        Files.write(staged,original);
        tracked(receiver,receiver.namespace,"deposit.create.class",List.of("Intervening edit",Map.of()));
        rejected=assertThrows(ExceptionHandler.DepositException.class,
            ()->tracked(receiver,receiver.namespace,"deposit.import.apply",apply));
        assertTrue(rejected.getMessage().contains("PLAN_STALE"),rejected.getMessage());
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertEquals(1L,core.sequence(receiver.namespace));
            assertNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Verified specimen")));
        }

        String waitingPlan=(String)tracked(receiver,receiver.namespace,"deposit.import.analyze",analyze).get("planId");
        String waitingOperation=operationId(clock.get());
        begin(receiver,waitingOperation,receiver.namespace,"deposit.import.apply",List.of(waitingPlan));
        var points=new ConcurrentLinkedQueue<String>();
        server.storageObserverForTesting(points::add);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();var blocker=database.beginTx()){
            var core=new CoreRepository(blocker,ZonedDateTime.now());
            core.lockNamespace(receiver.namespace);
            var waiting=workers.submit(()->cypher(receiver.execution,
                "CALL deposit.import.apply($plan,$operation,$token) YIELD data RETURN data",
                Map.of("plan",waitingPlan,"operation",waitingOperation,"token",receiver.token)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!"RUNNING".equals(status(receiver,waitingOperation,receiver.namespace).get("state"))
                    &&!waiting.isDone()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals("RUNNING",state(status(receiver,waitingOperation,receiver.namespace)));
            assertThrows(TimeoutException.class,()->waiting.get(100,TimeUnit.MILLISECONDS),
                "Import apply completed while the Namespace lock was held");
            assertFalse(points.contains("namespace-sequence-read"),points.toString());
            Node namespace=core.namespace(receiver.namespace);
            namespace.setProperty("description","Edit committed while apply was waiting");
            assertEquals(2L,core.advanceNamespace(namespace,1L,receiver.id));
            blocker.commit();
            var failure=assertThrows(ExecutionException.class,()->waiting.get(10,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("PLAN_STALE"),failure.toString());
            assertEquals(1,Collections.frequency(points,"namespace-sequence-read"),points.toString());
            assertFalse(points.contains("history-capture"),points.toString());
        } finally {server.storageObserverForTesting(ignored->{});}
        assertEquals("FAILED_ROLLED_BACK",state(status(receiver,waitingOperation,receiver.namespace)));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertEquals(2L,core.sequence(receiver.namespace));
            assertEquals("Edit committed while apply was waiting",core.namespace(receiver.namespace).getProperty("description"));
            assertEquals("ready",server.storage.importPlans.plan(waitingPlan).state());
            assertNull(core.receipt(receiver.id,receiver.namespace,waitingOperation));
            assertNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Verified specimen")));
        }

        var fresh=tracked(receiver,receiver.namespace,"deposit.import.analyze",analyze);
        var session=server.session(Json.hash(receiver.token));
        synchronized(session){session.baselines.put(receiver.namespace,0L);}
        var observed=call(receiver,receiver.control,"deposit.get.session",List.of(),null);
        assertEquals(0L,ValueCodec.map(observed.get("observed")).get(receiver.namespace));
        String currentOperation=operationId(clock.get());
        var currentApply=List.of(fresh.get("planId"));
        assertEquals("REGISTERED",state(begin(receiver,currentOperation,receiver.namespace,"deposit.import.apply",currentApply)));
        points.clear();
        server.storageObserverForTesting(points::add);
        Map<String,Object> applied;
        try {
            applied=call(receiver,receiver.execution,"deposit.import.apply",currentApply,currentOperation);
        } finally {server.storageObserverForTesting(ignored->{});}
        assertEquals(1,Collections.frequency(points,"namespace-sequence-read"),points.toString());
        assertTrue(points.contains("history-capture"),points.toString());
        assertEquals(3L,applied.get("revision"));
        var committed=status(receiver,currentOperation,receiver.namespace);
        assertEquals("COMMITTED",state(committed));
        assertEquals(applied.get("resultId"),ValueCodec.map(committed.get("result")).get("resultId"));
        observed=call(receiver,receiver.control,"deposit.get.session",List.of(),null);
        assertEquals(3L,ValueCodec.map(observed.get("observed")).get(receiver.namespace));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertEquals(3L,core.sequence(receiver.namespace));
            assertNotNull(core.find("Class",Map.of("namespaceId",receiver.namespace,"name","Verified specimen")));
            var receipt=core.receipt(receiver.id,receiver.namespace,currentOperation);
            assertEquals(3L,receipt.revision());
            assertEquals(applied.get("resultId"),receipt.importResultId());
        }
    }

    @Test void exchangeOversizedSelectorIsRejectedBeforeRegistration() {
        Actor actor=actor();
        String id=operationId(clock.get());
        var args=List.<Object>of(Collections.nCopies(30_000,UUID.randomUUID().toString()),Map.of());
        var rejected=assertThrows(ExceptionHandler.DepositException.class,
            ()->begin(actor,id,actor.namespace,"deposit.exchange.export",args));
        assertTrue(rejected.getMessage().contains("LIMIT_EXCEEDED"),rejected.getMessage());
        assertEquals("OUTCOME_UNKNOWN",state(status(actor,id,actor.namespace)));
        assertThrows(ExceptionHandler.DepositException.class,
            ()->begin(actor,id,actor.namespace,"deposit.get.data",List.of(List.of(),Map.of())));
    }

    @Test void exchangeOpaqueZipRetainsHistoricalNamespaceForServerScopedClone() throws Exception {
        Actor actor=actor();
        var historyNamespace=tracked(actor,"server","deposit.create.namespace",
            List.of("Historical origin "+UUID.randomUUID(),Map.of("historyMode","persistent")));
        String original=(String)historyNamespace.get("id");
        Actor source=renew(new Actor(actor.id,null,original,addConnection(),addConnection()));
        byte[] payload={1,3,5,7};
        String digest=ResourceFiles.checksum(payload);
        String resourceUpload=(String)tracked(source,original,"deposit.resource.beginUpload",
            List.of("history.bin",(long)payload.length,digest,"application/octet-stream")).get("uploadId");
        tracked(source,original,"deposit.resource.uploadChunk",
            List.of(resourceUpload,0L,Base64.getEncoder().encodeToString(payload)));
        String resource=(String)tracked(source,original,"deposit.resource.finishUpload",List.of(resourceUpload)).get("resourceId");
        var clazz=tracked(source,original,"deposit.create.class",List.of("Historical class",
            Map.of("descriptors",List.of(Map.of("name","Attachment","type","DResource")))));
        var object=tracked(source,original,"deposit.create.object",List.of(Map.of("id",clazz.get("id")),
            Map.of("descriptors",Map.of("Attachment",Map.of("resourceId",resource)))));
        tracked(source,original,"deposit.set.object",List.of(object.get("id"),Map.of("clearDescriptors",List.of("Attachment"))));
        var current=tracked(source,original,"deposit.get.data",Arrays.asList(null,Map.of("includeHistory",false)));
        assertEquals(false,ValueCodec.map(current.get("manifest")).get("includeHistory"));
        assertNull(current.get("history"));
        assertTrue(((List<?>)ValueCodec.map(current.get("graph")).get("resources")).isEmpty());
        var reference=tracked(source,original,"deposit.exchange.export",Arrays.asList(null,Map.of()));
        var inspected=inspectExchange(source,reference);
        assertEquals(true,ValueCodec.map(inspected.get("manifest")).get("includeHistory"));
        byte[] packageBytes=exchangeBytes(source,reference,inspected,"package");
        // The full package keeps the source name. Remove the disposable source
        // Namespace to exercise opaque intake and clone without a name collision.
        tracked(source,"server","deposit.delete.namespace",List.of(historyNamespace.get("name")));
        Actor clone=renew(new Actor(actor.id,null,null,addConnection(),addConnection()));
        String upload=beginExchange(clone,"server","zip",Map.of());
        exchangeChunk(clone,"server",upload,"package",0,packageBytes);
        var received=tracked(clone,"server","deposit.exchange.finishUpload",List.of(upload));
        assertEquals(reference.get("sha256"),received.get("sha256"));
        var outcome=tracked(clone,"server","deposit.import.cloneNamespace",List.of(packageSource(received)));
        String target=(String)outcome.get("namespaceId");
        assertNotEquals(original,target);
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNotNull(core.find("Class",Map.of("namespaceId",target,"name","Historical class")));
            assertEquals("persistent",core.namespace(target).getProperty("historyMode"));
            assertEquals(3L,core.sequence(target));
            var ids=core.nodes("DResource",Map.of("namespaceId",target));
            assertEquals(1,ids.size());
            String clonedResource=CoreRepository.id(ids.getFirst());
            assertNotEquals(resource,clonedResource);
            var location=ids.getFirst();
            var content=new ResourceStorage.Content(clonedResource,(String)location.getProperty("storedFilename"),payload.length,digest);
            try(var reader=server.storage.content.open(content,0,payload.length)){
                assertArrayEquals(payload,reader.readAllBytes());
            }
            assertFalse(core.nodes("DepositHistoryEpoch",Map.of("namespaceId",target)).isEmpty());
        }
    }

    @Test void exchangePagesLargeUnicodeManifestTextWithLeaseBoundCursor() {
        Actor actor=actor();
        var manifest=exchangeManifest(actor.namespace);
        String description=("study "+Character.toString(0x3b1)+Character.toString(0x1f52c)+" ").repeat(7000);
        manifest.put("description",description);
        var reference=uploadSnapshot(actor,"server",manifest,emptyExchangeGraph(),Map.of());
        var inspection=inspectExchange(actor,reference);
        var field=ValueCodec.map(ValueCodec.map(inspection.get("manifest")).get("description"));
        assertEquals("text",field.get("kind"));
        Object cursor=null;
        var value=new StringBuilder();
        int pages=0;
        do {
            var args=Arrays.asList(reference.get("packageId"),reference.get("grant"),inspection.get("leaseId"),"description",cursor,1L);
            var result=call(actor,actor.control,"deposit.exchange.graphPage",args,null);
            var entries=(List<?>)result.get("entries");
            assertEquals(1,entries.size());
            String text=(String)ValueCodec.map(entries.getFirst()).get("value");
            assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16*1024);
            value.append(text);
            cursor=result.get("nextCursor");
            if(++pages==1 && cursor!=null){
                var other=inspectExchange(actor,reference);
                var mismatched=List.of(reference.get("packageId"),reference.get("grant"),other.get("leaseId"),"description",cursor,1L);
                assertThrows(ExceptionHandler.DepositException.class,
                    ()->call(actor,actor.control,"deposit.exchange.graphPage",mismatched,null));
            }
        }while(cursor!=null);
        assertTrue(pages>1);
        assertEquals(description,value.toString());
    }

    @Test void largeInlineSnapshotUsesTheCanonicalExchangeGrant() {
        Actor a=actor();
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.namespace(a.namespace).setProperty("description","s".repeat(1_050_000));
            tx.commit();
        }
        var reference=tracked(a,a.namespace,"deposit.get.data",Arrays.asList(null,Map.of("includeHistory",false)));
        assertNotNull(reference.get("packageId"));
        assertFalse(reference.containsKey("resourceId"));
        var data=inspectExchange(a,reference);
        assertEquals("text",ValueCodec.map(ValueCodec.map(data.get("manifest")).get("description")).get("kind"));
    }

    @Test void exchangeStructuredContentDeduplicatesMembersWithoutCombiningResourceIdentities() throws Exception {
        Actor receiver=actor();
        String ns=UUID.randomUUID().toString(), clazz=UUID.randomUUID().toString(), object=UUID.randomUUID().toString();
        String first=UUID.randomUUID().toString(), second=UUID.randomUUID().toString();
        byte[] content="Deposit alpha fixture\0\377".repeat(4096).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String digest=ResourceFiles.checksum(content), entry="resources/"+digest;
        var graph=emptyExchangeGraph();
        graph.put("classes",List.of(record("Class",clazz,ns,"name","Resource sample","descriptors",List.of(
            record("ClassDescriptor",UUID.randomUUID().toString(),ns,"classId",clazz,"name","First","type","DResource"),
            record("ClassDescriptor",UUID.randomUUID().toString(),ns,"classId",clazz,"name","Second","type","DResource")))));
        graph.put("objects",List.of(record("Object",object,ns,"classId",clazz)));
        graph.put("memberships",List.of(record("Membership",UUID.randomUUID().toString(),ns,"objectId",object,"classId",clazz)));
        graph.put("descriptors",List.of(
            record("Descriptor",UUID.randomUUID().toString(),ns,"objectId",object,"name","First","value",Map.of("resourceId",first)),
            record("Descriptor",UUID.randomUUID().toString(),ns,"objectId",object,"name","Second","value",Map.of("resourceId",second))));
        graph.put("resources",List.of(
            Json.map("kind","DResource","id",first,"filename","first.bin","mediaType","application/octet-stream","bytes",(long)content.length,"entry",entry,"sha256",digest),
            Json.map("kind","DResource","id",second,"filename","second.bin","mediaType","application/octet-stream","bytes",(long)content.length,"entry",entry,"sha256",digest)));
        var reference=uploadSnapshot(receiver,receiver.namespace,exchangeManifest(ns),graph,Map.of(entry,content));
        assertEquals(2L,reference.get("memberCount"));
        var inspection=inspectExchange(receiver,reference);
        var page=call(receiver,receiver.control,"deposit.exchange.memberPage",Arrays.asList(reference.get("packageId"),
            reference.get("grant"),inspection.get("leaseId"),null,1L),null);
        assertNotNull(page.get("nextCursor"));
        assertArrayEquals(content,exchangeBytes(receiver,reference,inspection,entry));
        assertThrows(ExceptionHandler.DepositException.class,()->begin(receiver,operationId(clock.get()),"server",
            "deposit.import.cloneNamespace",List.of(packageSource(reference))));
        byte[] zip=exchangeBytes(receiver,reference,inspection,"package");
        String upload=beginExchange(receiver,receiver.namespace,"zip",Map.of());
        exchangeChunk(receiver,receiver.namespace,upload,"package",0,zip);
        Path validationLog=directory.resolve("verified-import-validation.log");
        String timing="deposit.stage7.validation.timing.enabled",progress="deposit.stage7.timing.progress";
        String previousTiming=System.setProperty(timing,"true"),previousProgress=System.setProperty(progress,validationLog.toString());
        try {
            var received=tracked(receiver,receiver.namespace,"deposit.exchange.finishUpload",List.of(upload));
            assertEquals(reference.get("sha256"),received.get("sha256"));
            assertEquals(1,Files.readAllLines(validationLog).stream().filter(line->line.contains("phase=semantic-validation")).count());
            var plan=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(received),Map.of()));
            try(var tx=database.beginTx()){
                var core=new CoreRepository(tx,ZonedDateTime.now());
                assertEquals(0L,core.sequence(receiver.namespace));
                assertTrue(core.nodes("DResource",Map.of("namespaceId",receiver.namespace)).isEmpty());
            }
            var result=tracked(receiver,receiver.namespace,"deposit.import.apply",List.of(plan.get("planId")));
            assertEquals("APPLIED",result.get("state"));
            assertEquals("COMMITTED",state(status(receiver,(String)result.get("operationId"),receiver.namespace)));
            assertEquals(1,Files.readAllLines(validationLog).stream().filter(line->line.contains("phase=semantic-validation")).count());
        } finally {
            if(previousTiming==null)System.clearProperty(timing);else System.setProperty(timing,previousTiming);
            if(previousProgress==null)System.clearProperty(progress);else System.setProperty(progress,previousProgress);
        }
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertEquals(1L,core.sequence(receiver.namespace));
            var resources=core.nodes("DResource",Map.of("namespaceId",receiver.namespace));
            assertEquals(2,resources.size());
            assertNotEquals(CoreRepository.id(resources.get(0)),CoreRepository.id(resources.get(1)));
            for(Node resource:resources){
                assertEquals(digest,resource.getProperty("checksum"));
                var stored=new ResourceStorage.Content(CoreRepository.id(resource),(String)resource.getProperty("storedFilename"),content.length,digest);
                try(var input=server.storage.content.open(stored,0,content.length)){
                    assertArrayEquals(content,input.readAllBytes());
                }
            }
        }
    }

    private static Map<String,Object> record(String kind,String id,String namespace,Object... fields) {
        var row=Json.map("kind",kind,"id",id,"namespaceId",namespace);
        row.putAll(Json.map(fields));
        return row;
    }

    private Map<String,Object> uploadSnapshot(Actor actor,String scope,Map<String,Object> manifest,Map<String,Object> graph,
            Map<String,byte[]> resources) {
        String upload=beginExchange(actor,scope,"snapshot",Map.of("includeHistory",false));
        for(var member:Map.of("manifest",manifest,"graph",graph).entrySet()) {
            byte[] bytes=Json.write(member.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int split=bytes.length/2;
            exchangeChunk(actor,scope,upload,member.getKey(),0,Arrays.copyOf(bytes,split));
            exchangeChunk(actor,scope,upload,member.getKey(),split,Arrays.copyOfRange(bytes,split,bytes.length));
        }
        for(var member:resources.entrySet())exchangeChunk(actor,scope,upload,member.getKey(),0,member.getValue());
        return tracked(actor,scope,"deposit.exchange.finishUpload",List.of(upload));
    }

    private Actor receivingNamespace(Actor source) {
        var created=tracked(source,"server","deposit.create.namespace",List.of("Receiving "+UUID.randomUUID(),Map.of()));
        return renew(new Actor(source.id,null,(String)created.get("id"),addConnection(),addConnection()));
    }

    private Map<String,Object> tracked(Actor actor,String scope,String name,List<Object> args) {
        String id=operationId(clock.get());
        assertEquals("REGISTERED",state(begin(actor,id,scope,name,args)));
        return call(actor,actor.execution,name,args,id);
    }

    private Map<String,Object> exchangeManifest(String source) {
        return Json.map("sourceNamespaceId",source,"name","Exchange "+UUID.randomUUID(),"description","",
            "historyMode","disabled","exportedRevision",0L,"rootIds",List.of(),"scope","namespace","createdAt",ZonedDateTime.now().toString());
    }

    private static Map<String,Object> emptyExchangeGraph() {
        var graph=new LinkedHashMap<String,Object>();
        for(String name:List.of("classes","objects","descriptors","geometries","memberships","relationships",
                "classRelationships","patterns","userTools","resources"))graph.put(name,List.of());
        return graph;
    }

    private String beginExchange(Actor actor,String scope,String kind,Map<String,Object> metadata) {
        return (String)tracked(actor,scope,"deposit.exchange.beginUpload",List.of(scope,kind,metadata)).get("uploadId");
    }

    private void exchangeChunk(Actor actor,String scope,String upload,String member,long offset,byte[] data) {
        var result=tracked(actor,scope,"deposit.exchange.uploadChunk",
            List.of(upload,member,offset,Base64.getEncoder().encodeToString(data)));
        assertEquals(offset+data.length,result.get("nextOffset"));
    }

    private static Map<String,Object> packageSource(Map<String,Object> reference) {
        return Map.of("packageId",reference.get("packageId"),"grant",reference.get("grant"));
    }

    private Map<String,Object> inspectExchange(Actor actor,Map<String,Object> reference) {
        return call(actor,actor.control,"deposit.exchange.inspect",List.of(reference.get("packageId"),reference.get("grant")),null);
    }

    private byte[] exchangeBytes(Actor actor,Map<String,Object> reference,Map<String,Object> inspection,String member)throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        for(;;){
            var result=call(actor,actor.control,"deposit.exchange.readChunk",List.of(reference.get("packageId"),reference.get("grant"),
                inspection.get("leaseId"),member,(long)out.size(),257L),null);
            byte[] bytes=Base64.getDecoder().decode((String)result.get("bytesBase64"));
            assertEquals((long)bytes.length,result.get("length"));
            out.write(bytes);
            if(bytes.length<257)return out.toByteArray();
        }
    }

    private String beginResource(Actor a) {
        String id=operationId(clock.get()), procedure="deposit.resource.beginUpload";
        var args=List.<Object>of("payload.bin",2L,ResourceFiles.checksum(new byte[]{1,2}),"application/octet-stream");
        begin(a,id,a.namespace,procedure,args);
        return (String)call(a,a.execution,procedure,args,id).get("uploadId");
    }

    private void writeResource(Actor a,String upload) {
        String id=operationId(clock.get()), procedure="deposit.resource.uploadChunk";
        byte[] body={1,2};
        var args=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(body));
        begin(a,id,a.namespace,procedure,args);
        assertEquals(2L,call(a,a.execution,procedure,args,id).get("nextOffset"));
    }

    private Map<String,Object> begin(Actor actor,String id,String scope,String procedure,List<Object> args) {
        return call(actor,actor.execution,"deposit.action.begin",List.of(id,scope,procedure,args),null);
    }
    private Map<String,Object> status(Actor actor,String id,String scope) {
        return call(actor,actor.control,"deposit.get.operation",List.of(id,scope),null);
    }
    private Map<String,Object> cypher(Connection connection,String query,Map<String,Object> parameters) {
        try(var tx=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,connection.info())){
            Map<String,Object> row;
            try(var result=tx.execute("CYPHER 25 "+query,parameters)){row=result.next();assertFalse(result.hasNext());}
            tx.commit();
            return row;
        }
    }
    private static Object state(Map<String,Object> status) {
        assertEquals(Set.of("operationId","scope","procedure","state","cleanupState","updatedAt","code","result"),status.keySet());
        return status.get("state");
    }
    private Map<String,Object> call(Actor actor,Connection connection,String procedure,List<Object> args,String id) {
        return call(actor,connection,procedure,args,id,()->{});
    }
    private Map<String,Object> call(Actor actor,Connection connection,String procedure,List<Object> args,String id,Runnable beforeCommit) {
        return call(database,actor.token,connection,procedure,args,id,beforeCommit);
    }
    static Map<String,Object> call(GraphDatabaseAPI database,String token,Connection connection,
            String procedure,List<Object> args,String id,Runnable beforeCommit) {
        try(var tx=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,connection.info())){
            var context=new RequestContext(tx,Map.of("token",token));
            var actions=new SessionActionService(context);
            actions.beginPublic(procedure,args,id);
            Object row;
            try(var result=actions.finish(new ProcedureService(context).execute())){row=result.findFirst().orElseThrow();}
            actions.observe();
            beforeCommit.run();
            tx.commit();
            return switch(row) {
                case DataRow data -> data.data;
                case Results.EntityRow entity -> entity.data;
                case Results.RelRow relationship -> relationship.data;
                case Results.ValueRow value -> Map.of("value",value.value);
                default -> throw new IllegalArgumentException("Unexpected test row");
            };
        }
    }
    private Actor actor() {
        String id=UUID.randomUUID().toString(), namespace;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.createRestored("User",null,id,Json.map("username",id,"enabled",true,"serverRole","user"),id);
            namespace=CoreRepository.id(core.create("Namespace",null,id,Json.map("name",id,"sequence",0L,"historyMode","disabled")));
            core.setRole(id,namespace,"owner");
            tx.commit();
        }
        return renew(new Actor(id,null,namespace,addConnection(),addConnection()));
    }
    private Actor renew(Actor actor) {
        String token=ServerContext.randomToken();
        var session=new ServerContext.Session(actor.id,LoginContext.AUTH_DISABLED.subject().executingUser(),clock.get());
        session.namespace=actor.namespace;
        if(actor.namespace!=null)session.baselines.put(actor.namespace,0L);
        session.baselines.put("server",server.committedRevision());
        server.registerSession(Json.hash(token),session);
        return new Actor(actor.id,token,actor.namespace,actor.execution,actor.control);
    }
    private Connection addConnection() {
        var newConnection=new Connection(connections.newConnectionId("bolt"));
        connections.add(newConnection);
        return newConnection;
    }
    static String operationId(long time) {
        long entropy=ThreadLocalRandom.current().nextLong();
        return new UUID((time<<16)|0x7000|(entropy&0xfff), (entropy&0x3fffffffffffffffL)|Long.MIN_VALUE).toString();
    }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    record Connection(String id) implements TrackedNetworkConnection {
        public long connectTime(){return 0;}
        public String connectorId(){return "bolt";}
        public SocketAddress serverAddress(){return null;}
        public SocketAddress clientAddress(){return null;}
        public String username(){return "";}
        public String userAgent(){return "operation-test";}
        public void updateUser(String user,String agent){}
        public void close(){}
        ClientConnectionInfo info(){
            return new ClientConnectionInfo(){
                public String asConnectionDetails(){return id;}
                public String protocol(){return "bolt";}
                public String connectionId(){return id;}
            };
        }
    }
}
