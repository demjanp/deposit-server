package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import deposit.common.Results.DataRow;
import deposit.repository.CoreRepository;
import deposit.repository.OperationAttemptRepository;
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
import org.neo4j.graphdb.Node;
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
        SchemaRepository.initialize(database);
        server.storage=new StorageManager(server,database);
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
            assertFalse(receipt.hasProperty("outcome"));
        }
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
        cypher(a.execution,"CALL deposit.create.class($name,$patch,$id,$token) YIELD node RETURN node.id AS id",parameters);
        assertEquals("COMMITTED",state(ValueCodec.map(cypher(a.control,
            "CALL deposit.get.operation($id,$scope,$token) YIELD data RETURN data",parameters).get("data"))));
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
        try (var tx=database.beginTx()) {
            assertNull(new CoreRepository(tx,ZonedDateTime.now()).find("Class",Map.of("namespaceId",a.namespace,"name",args.getFirst())));
        }
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
        String export="deposit.save.data", exportId=operationId(clock.get());
        var exportArgs=List.<Object>of(Map.of(),Map.of());
        begin(a,exportId,a.namespace,export,exportArgs);
        call(a,a.execution,export,exportArgs,exportId);
        var exported=call(a,a.control,"deposit.action.cancel",List.of(exportId,a.namespace),null);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(exported));
        String packageId=(String)ValueCodec.map(exported.get("result")).get("resourceId");
        assertNotNull(packageId);
        String analyze="deposit.import.analyze",analysisId=operationId(clock.get());
        var analysis=List.<Object>of(Map.of("packageResourceId",packageId),Map.of());
        begin(a,analysisId,a.namespace,analyze,analysis);
        call(a,a.execution,analyze,analysis,analysisId);
        var analyzed=status(a,analysisId,a.namespace);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(analyzed));
        String planId=(String)ValueCodec.map(analyzed.get("result")).get("planId");
        assertNotNull(planId);
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(call(a,a.control,"deposit.action.cancel",List.of(analysisId,a.namespace),null)));
        String apply="deposit.import.apply", applyId=operationId(clock.get());
        var applyArgs=List.<Object>of(planId);
        begin(a,applyId,a.namespace,apply,applyArgs);
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.find("DepositExportGrant",Map.of("resourceId",packageId,"actorId",a.id)).delete();
            tx.commit();
        }
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,apply,applyArgs,applyId));
        try(var tx=database.beginTx()){
            assertEquals(0L,new CoreRepository(tx,ZonedDateTime.now()).sequence(a.namespace));
        }
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
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Waiting "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();var blocker=database.beginTx()){
            new CoreRepository(blocker,ZonedDateTime.now()).lockNamespace(a.namespace);
            var run=workers.submit(()->call(a,a.execution,procedure,args,id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!"RUNNING".equals(status(a,id,a.namespace).get("state"))&&System.nanoTime()<deadline)Thread.sleep(20);
            var cancelled=state(call(a,a.control,"deposit.action.cancel",List.of(id,a.namespace),null));
            assertTrue(Set.of("CANCEL_REQUESTED","CANCELLED_ROLLED_BACK").contains(cancelled));
            blocker.commit();
            assertThrows(ExecutionException.class,()->run.get(10,TimeUnit.SECONDS));
        }
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
            try{
                assertTrue(gate.await(10,TimeUnit.SECONDS));
                disabled=workers.submit(()->call(administrator,administrator.execution,"deposit.set.user",disableArgs,disableId));
                assertThrows(TimeoutException.class,()->disabled.get(100,TimeUnit.MILLISECONDS));
            }finally{release.countDown();}
            run.get(10,TimeUnit.SECONDS);
            disabled.get(10,TimeUnit.SECONDS);
        }
        assertThrows(ExceptionHandler.DepositException.class,()->status(a,id,a.namespace));
        try(var tx=database.beginTx()){
            assertEquals("COMMITTED",OperationAttemptRepository.state(new OperationAttemptRepository(tx).find(a.id,a.namespace,id)));
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

    @Test void deletedNamespaceLeavesAnActorOnlySanitizedServerReceipt() {
        Actor a=actor(), b=actor();
        String id=operationId(clock.get()), procedure="deposit.delete.namespace";
        var args=List.<Object>of(a.id);
        begin(a,id,"server",procedure,args);
        call(a,a.execution,procedure,args,id);
        var proof=status(a,id,"server");
        assertEquals("COMMITTED",state(proof));
        assertNull(proof.get("result"));
        assertNull(proof.get("code"));
        assertEquals("OUTCOME_UNKNOWN",state(status(b,id,"server")));
    }

    @Test void aRevokedSourceGrantBlocksClaimStatusAndIdenticalRegistration() {
        Actor a=actor();
        String packageId=UUID.randomUUID().toString(), grantId=UUID.randomUUID().toString(), source;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            source=CoreRepository.id(core.create("Namespace",null,a.id,Json.map("name",packageId,"sequence",0L,"historyMode","disabled")));
            core.setRole(a.id,source,"viewer");
            var location=tx.createNode(org.neo4j.graphdb.Label.label("DepositResourceLocation"));
            Json.map("id",packageId,"namespaceId",source).forEach(location::setProperty);
            var resource=tx.createNode(org.neo4j.graphdb.Label.label("DResource"));
            Json.map("id",packageId,"namespaceId",source,"state","ready").forEach(resource::setProperty);
            var grant=tx.createNode(org.neo4j.graphdb.Label.label("DepositExportGrant"));
            Json.map("id",grantId,"resourceId",packageId,"actorId",a.id,"namespaceId",source,"expires",clock.get()+60000).forEach(grant::setProperty);
            tx.commit();
        }
        String id=operationId(clock.get()), procedure="deposit.import.analyze";
        var args=List.<Object>of(Map.of("packageResourceId",packageId),Map.of());
        assertEquals("REGISTERED",state(begin(a,id,a.namespace,procedure,args)));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.find("DepositExportGrant","id",grantId).delete();
            tx.commit();
        }
        try{
            assertThrows(ExceptionHandler.DepositException.class,()->begin(a,id,a.namespace,procedure,args));
            assertThrows(ExceptionHandler.DepositException.class,()->status(a,id,a.namespace));
            assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,procedure,args,id));
        }finally{
            try(var tx=database.beginTx()){
                var core=new CoreRepository(tx,ZonedDateTime.now());
                core.entity("DResource",source,packageId).delete();
                core.find("DepositResourceLocation","id",packageId).delete();
                tx.commit();
            }
        }
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
        try(var tx=database.beginTx()){
            var record=tx.createNode(org.neo4j.graphdb.Label.label("DepositTabularTable"));
            Json.map("id",table,"namespaceId",a.namespace,"creatorId",a.id,"path","staging/tables/"+table+".json",
                "rowIndexPath","staging/tables/"+table+".idx","expiresAt",expires,"rowCount",1L,"columnsJson","[]","valid",true,
                "sourceResourceId",UUID.randomUUID().toString()).forEach(record::setProperty);
            var lease=tx.createNode(org.neo4j.graphdb.Label.label("DepositResourceLease"));
            lease.setProperty("tableId",table);
            var plan=tx.createNode(org.neo4j.graphdb.Label.label("DepositImportPlan"));
            Json.map("id",table,"tableId",table,"namespaceId",a.namespace,"expiresAt",expires+60_000L,"state","ready").forEach(plan::setProperty);
            tx.commit();
        }
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
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            assertNull(core.find("DepositResourceLease","tableId",table));
            assertFalse(core.find("DepositImportPlan","id",table).hasProperty("tableId"));
        }
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
        assertThrows(ExceptionHandler.DepositException.class,()->begin(fresh,id,fresh.namespace,procedure,args));
        clock.set(original+2*OperationAttemptRepository.RETENTION+100);
        String missing=operationId(clock.get()+OperationAttemptRepository.FUTURE_SKEW-1);
        try(var tx=database.beginTx()){OperationAttemptRepository.initialize(tx,clock.get());tx.commit();}
        Actor restored=renew(a);
        assertThrows(ExceptionHandler.DepositException.class,()->begin(restored,missing,restored.namespace,procedure,args));
        assertEquals("OUTCOME_UNKNOWN",state(status(restored,missing,restored.namespace)));
        clock.addAndGet(OperationAttemptRepository.FUTURE_SKEW+1);
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
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(first),ResourceFiles.checksum(first));
        String step=operationId(clock.get());
        begin(a,step,a.namespace,chunk,part);
        assertEquals(1L,call(a,a.execution,chunk,part,step).get("nextOffset"));
        connections.remove(a.execution);
        var newConnection=addConnection();
        var replacement=new Actor(a.id,a.token,a.namespace,newConnection,a.control);
        byte[] second={2};
        var append=List.<Object>of(upload,1L,Base64.getEncoder().encodeToString(second),ResourceFiles.checksum(second));
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

    @Test void interruptedCloneUploadDiscardsItsIncompleteZip() throws Exception {
        Actor a=actor();
        String begin="deposit.import.beginCloneUpload", beginId=operationId(clock.get());
        byte[] bytes={1,2};
        var args=List.<Object>of("clone.zip",2L,ResourceFiles.checksum(bytes));
        begin(a,beginId,"server",begin,args);
        String upload=(String)call(a,a.execution,begin,args,beginId).get("uploadId");
        var oneByte=new byte[]{1};
        String chunk="deposit.import.cloneUploadChunk", partId=operationId(clock.get());
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(oneByte),ResourceFiles.checksum(oneByte));
        begin(a,partId,"server",chunk,part);
        assertEquals(1L,call(a,a.execution,chunk,part,partId).get("nextOffset"));
        Path incomplete=server.storage.files.path("staging/imports/clone-uploads/"+upload+".zip");
        assertTrue(Files.exists(incomplete));
        connections.remove(a.execution);
        server.storage.recover();
        assertFalse(Files.exists(incomplete));
        assertEquals("COMPLETED_NO_DOMAIN_WRITE",state(status(a,partId,"server")));
    }

    @Test void startupSettlesPreviousIncarnationWithoutRestartingWork() {
        Actor a=actor();
        String id=operationId(clock.get()), procedure="deposit.create.class";
        var args=List.<Object>of("Previous incarnation "+id,Map.of());
        begin(a,id,a.namespace,procedure,args);
        try(var tx=database.beginTx()){
            var node=new OperationAttemptRepository(tx).find(a.id,a.namespace,id);
            node.setProperty("incarnation",UUID.randomUUID().toString());
            node.setProperty("requestId",UUID.randomUUID().toString());
            node.setProperty("transactionSequence",Long.MAX_VALUE);
            OperationAttemptRepository.transition(node,"RUNNING",clock.get());
            tx.commit();
        }
        new OperationMaintenance(server,database).recover();
        assertEquals("FAILED_ROLLED_BACK",state(status(a,id,a.namespace)));
        assertThrows(ExceptionHandler.DepositException.class,()->call(a,a.execution,procedure,args,id));
    }

    @Test void interruptionAfterOcflPublicationReclaimsOnlyTheUncommittedObject() throws Exception {
        Actor a=actor();
        String upload=beginResource(a);
        writeResource(a,upload);
        String resource;
        try(var tx=database.beginTx()){
            var record=new CoreRepository(tx,ZonedDateTime.now()).find("DepositTransfer","id",upload);
            resource=(String)record.getProperty("resourceId");
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
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes),ResourceFiles.checksum(bytes));
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

    @Test void trackedCsvLoadStagesRowsAndKeepsAnAbsoluteExpiry() {
        Actor a=actor();
        byte[] bytes="field\r\nvalue\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String begin="deposit.resource.beginUpload", uploadId=operationId(clock.get());
        var args=List.<Object>of("table.csv",(long)bytes.length,ResourceFiles.checksum(bytes),"text/csv");
        begin(a,uploadId,a.namespace,begin,args);
        String upload=(String)call(a,a.execution,begin,args,uploadId).get("uploadId");
        String chunk="deposit.resource.uploadChunk", chunkId=operationId(clock.get());
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes),ResourceFiles.checksum(bytes));
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
        try(var tx=database.beginTx()){
            var row=new CoreRepository(tx,ZonedDateTime.now()).find("DepositTabularTable","id",table);
            assertEquals(created+OperationAttemptRepository.RETENTION,row.getProperty("expiresAt"));
            assertEquals(clock.get(),row.getProperty("lastAccessAt"));
        }
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
        var part=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(bytes),digest);
        begin(a,step,a.namespace,chunk,part);
        assertEquals((long)bytes.length,call(a,a.execution,chunk,part,step).get("nextOffset"));
        String finish=operationId(clock.get()), procedure="deposit.resource.finishUpload";
        var finalArgs=List.<Object>of(upload);
        begin(a,finish,a.namespace,procedure,finalArgs);
        String resource=(String)call(a,a.execution,procedure,finalArgs,finish).get("resourceId");
        try(var tx=database.beginTx()){
            var loc=new CoreRepository(tx,ZonedDateTime.now()).find("DepositResourceLocation","id",resource);
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
            jobs.expireImportResults();
            jobs.expireImportResults();
            try(var tx=database.beginTx()) {
                var core=new CoreRepository(tx,ZonedDateTime.now());
                assertNull(core.find(ImportResultContract.LABEL,"id",result));
                assertTrue(core.nodes("DepositImportResultEntry",Map.of("resultId",result)).isEmpty());
                var receipt=core.receipt(receiver.id,receiver.namespace,operation);
                assertEquals(result,receipt.getProperty("importResultId"));
                assertFalse(receipt.hasProperty("outcome"));
                assertEquals(true,receipt.getProperty("importResultCollected"));
                assertTrue(ImportResultContract.verifiesResult(core,receipt,receiver.namespace));
            }
            assertEquals("COMMITTED",state(status(renewed,operation,receiver.namespace)));
        } finally {
            clock.set(original);
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
        tracked(source,original,"deposit.create.class",List.of("Historical class",Map.of()));
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
            assertEquals(1L,core.sequence(target));
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

    @Test void exchangeStructuredContentDeduplicatesMembersWithoutCombiningResourceIdentities() throws Exception {
        Actor receiver=actor();
        String ns=UUID.randomUUID().toString(), clazz=UUID.randomUUID().toString(), object=UUID.randomUUID().toString();
        String first=UUID.randomUUID().toString(), second=UUID.randomUUID().toString();
        byte[] content={0,1,2,3,4,5,6,7,8,9,10};
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
        var plan=tracked(receiver,receiver.namespace,"deposit.import.analyze",List.of(packageSource(reference),Map.of()));
        tracked(receiver,receiver.namespace,"deposit.import.apply",List.of(plan.get("planId")));
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            var resources=core.nodes("DResource",Map.of("namespaceId",receiver.namespace));
            assertEquals(2,resources.size());
            assertNotEquals(CoreRepository.id(resources.get(0)),CoreRepository.id(resources.get(1)));
            for(Node resource:resources)assertEquals(digest,resource.getProperty("checksum"));
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
            List.of(upload,member,offset,Base64.getEncoder().encodeToString(data),ResourceFiles.checksum(data)));
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
        var args=List.<Object>of(upload,0L,Base64.getEncoder().encodeToString(body),ResourceFiles.checksum(body));
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
        try(var tx=database.beginTransaction(KernelTransaction.Type.EXPLICIT,LoginContext.AUTH_DISABLED,connection.info())){
            var context=new RequestContext(tx,Map.of("token",actor.token));
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
    private static String operationId(long time) {
        long entropy=ThreadLocalRandom.current().nextLong();
        return new UUID((time<<16)|0x7000|(entropy&0xfff), (entropy&0x3fffffffffffffffL)|Long.MIN_VALUE).toString();
    }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private record Connection(String id) implements TrackedNetworkConnection {
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
