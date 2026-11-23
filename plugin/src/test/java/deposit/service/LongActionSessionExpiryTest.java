package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.repository.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Session commit guards and prepared resource recovery through the procedure path. */
class LongActionSessionExpiryTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    UUID databaseId;
    String actor=UUID.randomUUID().toString(),token=ServerContext.randomToken(),previousConfig;

    @BeforeEach void setup() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path resources=Files.createDirectory(temporary.resolve("resources")),exports=Files.createDirectory(temporary.resolve("exports"));
        Path config=temporary.resolve("deposit.properties");
        Files.writeString(config,"resourceRoot="+resources.toString().replace('\\','/')+"\nexportRoot="+exports.toString().replace('\\','/')+"\n");
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");databaseId=database.databaseId().databaseId().uuid();
        SchemaRepository.initialize(database);
        server=ServerContext.attach(databaseId);server.storage=new StorageManager(server,database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        var databaseField=ServerContext.class.getDeclaredField("database");databaseField.setAccessible(true);databaseField.set(server,database);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        createActorAndSession();
    }

    @AfterEach void cleanup() {
        try {if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally {try {if(server!=null&&server.storage!=null)server.storage.close();}
        finally {try {if(management!=null)management.shutdown();}
            finally {if(databaseId!=null)ServerContext.detach(databaseId);if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }

    private void sessionClock(java.util.function.LongSupplier supplier) throws Exception {
        var method=ServerContext.class.getDeclaredMethod("sessionClockForTesting",java.util.function.LongSupplier.class);
        method.setAccessible(true);method.invoke(server,supplier);
    }

    private void createActorAndSession() {
        try(var tx=database.beginTx()) {
            Node user=tx.createNode(Label.label("User"));
            for (var entry:Json.map("id",actor,"enabled",true,"serverRole","user").entrySet()) user.setProperty(entry.getKey(),entry.getValue());
            Node lock=tx.createNode(Label.label("DepositActorLock"));lock.setProperty("id",actor);

            var internal=(InternalTransaction)tx;
            var session=new ServerContext.Session(actor,internal.securityContext().subject().executingUser(),server.sessionTime());
            session.baselines.put("server",server.revision(new RequestContext(tx,Json.map("token",token)).state));
            server.registerSession(Json.hash(token),session);tx.commit();
        }
    }

    private static final class ApiProbe extends deposit.api.BaseAPI {
        java.util.stream.Stream<Object> call(String token,String procedure,List<Object> arguments) {
            return invokePublic(token,procedure,arguments);
        }
    }

    private Map<String,Object> publicCall(String procedure,List<Object> arguments) {
        return publicCall(token,procedure,arguments);
    }

    private Map<String,Object> publicCall(String authToken,String procedure,List<Object> arguments) {
        try(var tx=database.beginTx()) {
            var probe=new ApiProbe();probe.tx=tx;probe.log=org.neo4j.logging.NullLog.getInstance();
            Object row=probe.call(authToken,procedure,arguments).findFirst().orElseThrow();
            Map<String,Object> result;
            if(row instanceof Results.EntityRow entity)result=Map.of("id",entity.node.getProperty("id"));
            else if(row instanceof Results.DataRow data)result=data.data;
            else if(row instanceof Results.ValueRow value)result=Map.of("value",value.value);
            else throw new AssertionError("Unexpected public result: "+row.getClass());
            tx.commit();
            return result;
        }
    }

    private String createNamespace(String name) {
        return (String)publicCall("deposit.create.namespace",List.of(name,Map.of("historyMode","disabled"))).get("id");
    }

    private void grantAdministratorAndNamespace(String writeToken,String ns) {
        try(var tx=database.beginTx()) {
            Node user=tx.findNode(Label.label("User"),"id",actor);
            user.setProperty("serverRole","administrator");
            Node target=tx.findNode(Label.label("Namespace"),"id",ns);
            user.createRelationshipTo(target,RelationshipType.withName("HAS_NAMESPACE_ROLE")).setProperty("role","owner");
            tx.commit();
        }
        replaceSession(writeToken,ns);
    }

    private void replaceSession(String sessionToken,String ns) {
        try(var tx=database.beginTx()) {
            var internal=(InternalTransaction)tx;
            var session=new ServerContext.Session(actor,internal.securityContext().subject().executingUser(),server.sessionTime());
            session.namespace=ns;server.registerSession(Json.hash(sessionToken),session);tx.commit();
        }
    }

    private String prepareChunk(String uploadId,List<Object> arguments) {
        String recoveryKey=UUID.randomUUID().toString();
        publicCall("deposit.action.prepare",List.of(recoveryKey,"deposit.resource.uploadChunk",arguments));
        publicCall("deposit.action.release",List.of());
        try(var tx=database.beginTx()) {
            Node action=tx.findNode(Label.label("DepositPreparedAction"),"recoveryKey",recoveryKey);
            Map<String,Object> bindings=Json.read((String)action.getProperty("bindings"));
            Node receipt=tx.findNode(Label.label("DepositReceipt"),"operationId",action.getProperty("operationId"));
            assertTrue(bindings.isEmpty(),"primitive uploadId is not captured as a selector binding");assertEquals(uploadId,action.getProperty("resourceUploadId"));assertNull(receipt);tx.rollback();
        }
        return recoveryKey;
    }

    private void expireAndPrune(String authToken,String resourceId,String ns) {
        server.storage.recover();
        publicCall(authToken,"deposit.admin.pruneResources",List.of(Map.of("dryRun",false,"minimumAgeHours",0L,"scope","namespace")));
        server.storage.recover();
    }

    @Test void expiredLeaseDoesNotRetainPreparedChunk() throws Exception {
        AtomicLong clock=new AtomicLong(server.sessionTime());sessionClock(clock::get);
        String ns=createNamespace("Prepared upload retention");grantAdministratorAndNamespace(token,ns);
        byte[] payload={42};String checksum=ResourceFiles.checksum(payload);
        String upload=(String)publicCall("deposit.resource.beginUpload",List.of("pending.bin",2L,ResourceFiles.checksum(new byte[]{42,43}),"application/octet-stream")).get("uploadId");
        List<Object> chunk=List.of(upload,0L,Base64.getEncoder().encodeToString(payload),checksum);
        assertEquals(1L,publicCall("deposit.resource.uploadChunk",chunk).get("nextOffset"));
        String resourceId,stagingPath,originalPrep;
        try(var tx=database.beginTx()) {
            Node transfer=tx.findNode(Label.label("DepositTransfer"),"id",upload);resourceId=(String)transfer.getProperty("resourceId");
            stagingPath=(String)tx.findNode(Label.label("DepositResourceLocation"),"id",resourceId).getProperty("stagingPath");tx.rollback();
        }
        originalPrep=prepareChunk(upload,chunk);
        clock.addAndGet(ResourceFiles.LEASE+1);
        String resumedToken=ServerContext.randomToken();replaceSession(resumedToken,ns);
        expireAndPrune(resumedToken,resourceId,ns);
        try(var tx=database.beginTx()) {
            Node transfer=tx.findNode(Label.label("DepositTransfer"),"id",upload);
            assertEquals("expired",transfer.getProperty("state"),"an uncommitted prepared action must not extend the upload lease");
            Node location=tx.findNode(Label.label("DepositResourceLocation"),"id",resourceId);
            assertFalse(location.hasProperty("stagingPath"),"expired upload staging is eligible for normal cleanup");
            try(var chunks=tx.findNodes(Label.label("DepositTransferChunk"),"uploadId",upload)) {
                assertEquals(0,chunks.stream().count());
            }
            Node action=tx.findNode(Label.label("DepositPreparedAction"),"recoveryKey",originalPrep);
            assertNull(new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).receipt(ns,(String)action.getProperty("operationId")),"expiry must not fabricate a receipt for an uncommitted action");
            assertFalse(Files.exists(server.storage.files.path(stagingPath)));tx.rollback();
        }
    }
    @Test void abortReclaimsStagingAndChunksWithAnUnexecutedPreparedChunk() throws Exception {
        String ns=createNamespace("Abort pending upload");
        grantAdministratorAndNamespace(token,ns);
        byte[] payload={42,43,44};
        String upload=(String)publicCall("deposit.resource.beginUpload",List.of(
                "pending.bin",3L,ResourceFiles.checksum(payload),"application/octet-stream")).get("uploadId");
        byte[] first={42}, last={43,44};
        assertEquals(1L,publicCall("deposit.resource.uploadChunk",List.of(
                upload,0L,Base64.getEncoder().encodeToString(first),ResourceFiles.checksum(first))).get("nextOffset"));
        String pending=prepareChunk(upload,List.of(
                upload,1L,Base64.getEncoder().encodeToString(last),ResourceFiles.checksum(last)));
        String resourceId;
        Path staging;
        try(var tx=database.beginTx()) {
            var transfer=tx.findNode(Label.label("DepositTransfer"),"id",upload);
            resourceId=(String)transfer.getProperty("resourceId");
            var location=tx.findNode(Label.label("DepositResourceLocation"),"id",resourceId);
            staging=server.storage.files.path((String)location.getProperty("stagingPath"));
            assertArrayEquals(first,Files.readAllBytes(staging));
        }

        assertEquals(true,publicCall("deposit.resource.abortUpload",List.of(upload)).get("aborted"));
        expireAndPrune(token,resourceId,ns);
        try(var tx=database.beginTx()) {
            var transfer=tx.findNode(Label.label("DepositTransfer"),"id",upload);
            assertEquals("aborted",transfer.getProperty("state"));
            assertEquals(0L,transfer.getProperty("expires"));
            var action=tx.findNode(Label.label("DepositPreparedAction"),"recoveryKey",pending);
            assertNull(new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC))
                    .receipt(ns,(String)action.getProperty("operationId")));
            var location=tx.findNode(Label.label("DepositResourceLocation"),"id",resourceId);
            try(var chunks=tx.findNodes(Label.label("DepositTransferChunk"),"uploadId",upload)) {
                long remaining=chunks.stream().count();
                assertAll(()->assertFalse(Files.exists(staging),"staged bytes"),
                        ()->assertFalse(location.hasProperty("stagingPath"),"staging metadata"),
                        ()->assertEquals(0,remaining,"terminal chunk rows"));
            }
        }
    }

    @Test void leasedNamespaceCreationRollsBackAtAbsoluteSessionExpiry() throws Exception {
        AtomicLong now=new AtomicLong(server.sessionTime());
        sessionClock(now::get);
        String operation=UUID.randomUUID().toString(),name="Namespace at absolute session expiry";
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token,"operationId",operation));
            context.state.observations.put("server",server.revision(context.state));
            new SessionActionService(context).begin("deposit.create.namespace",List.of(name,Map.of("historyMode","disabled")));
            new ProcedureService(context).execute().findFirst().orElseThrow();
            assertSame(context.state.identityToken(),context.session.mutationOwner);
            now.set(context.session.created+28_800_000L);
            context.session.touched=now.get()-1;
            RuntimeException failure=assertThrows(RuntimeException.class,tx::commit);
            Throwable cause=failure;
            while(cause!=null&&!String.valueOf(cause.getMessage()).contains("SESSION_EXPIRED"))cause=cause.getCause();
            assertNotNull(cause,()->"Expected SESSION_EXPIRED, got "+failure);
        }
        assertNull(server.session(Json.hash(token)).mutationOwner);
        try(var tx=database.beginTx()) {
            assertNull(new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).receipt("server",operation));
            assertNull(tx.findNode(Label.label("Namespace"),"name",name));
        }
        assertTrue(assertThrows(ExceptionHandler.DepositException.class,
                ()->publicCall("deposit.get.session",List.of())).getMessage().contains("SESSION_EXPIRED"));
    }

    @Test void planReadRefreshesInactiveWindowWithoutReceiptRewrite() throws Exception {
        AtomicLong now=new AtomicLong(server.sessionTime());
        sessionClock(now::get);
        String ns=createNamespace("Import plan inactivity");
        grantAdministratorAndNamespace(token,ns);
        String operation=UUID.randomUUID().toString(),planId=UUID.randomUUID().toString();
        long initialExpiry=now.get()+86_400_000L;
        Map<String,Object> analysis;
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token,"operationId",operation));
            context.actorId=actor;
            context.namespaceId=ns;
            var core=new CoreRepository(tx,context.timestamp);
            context.revision=core.sequence(ns);
            analysis=new ImportResultRepository(context).save(operation,"analysis",planId,context.revision,initialExpiry,Map.of());
            core.saveReceipt(context,ns,"analysis-request",new Results.Outcome(null,null,analysis));
            var plan=tx.createNode(Label.label("DepositImportPlan"));
            Json.map("id",planId,"namespaceId",ns,"actorId",actor,"state","ready",
                    "targetRevision",context.revision,"expiresAt",initialExpiry,"lastActivity",now.get(),
                    "resultId",analysis.get("resultId")).forEach(plan::setProperty);
            tx.commit();
        }

        now.set(initialExpiry-1);
        replaceSession(token,ns);
        database.getDependencyResolver().resolveDependency(org.neo4j.kernel.api.procedure.GlobalProcedures.class)
                .registerProcedure(deposit.api.DepositAPI.class);
        Map<String,Object> inspected;
        try(var tx=database.beginTx();
            var rows=tx.execute("CALL deposit.get.importPlan($planId, $token, false) YIELD data RETURN data",
                    Map.of("planId",planId,"token",token))) {
            var noRenew=ValueCodec.map(ValueCodec.map(rows.next().get("data")).get("plan"));
            assertEquals(initialExpiry,noRenew.get("expiresAt"));
            assertFalse(rows.hasNext());
            tx.commit();
        }
        try(var tx=database.beginTx()) {
            var plan=tx.findNode(Label.label("DepositImportPlan"),"id",planId);
            assertEquals(initialExpiry,plan.getProperty("expiresAt"));
            assertEquals(now.get()-86_399_999L,plan.getProperty("lastActivity"));
        }
        try(var tx=database.beginTx();
            var rows=tx.execute("CALL deposit.get.importPlan($planId, $token) YIELD data RETURN data",
                    Map.of("planId",planId,"token",token))) {
            inspected=ValueCodec.map(rows.next().get("data"));
            assertFalse(rows.hasNext());
            tx.commit();
        }
        var lifecycle=ValueCodec.map(inspected.get("plan"));
        assertEquals(planId,lifecycle.get("planId"));
        assertEquals(analysis,inspected.get("analysisResult"));
        try(var tx=database.beginTx()) {
            var plan=tx.findNode(Label.label("DepositImportPlan"),"id",planId);
            var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));
            assertEquals(lifecycle.get("targetRevision"),core.sequence(ns));
            assertEquals(analysis,core.replay(core.receipt(ns,operation),ns).data());
            assertAll(
                    ()->assertEquals(now.get()+86_400_000L,lifecycle.get("expiresAt"),"expiresAt"),
                    ()->assertEquals(lifecycle.get("expiresAt"),plan.getProperty("expiresAt"),"persisted expiresAt"),
                    ()->assertEquals(now.get(),plan.getProperty("lastActivity"),"lastActivity"));
        }
    }

    @Test void planExpiryWaitsForRenewalAndRechecksBeforeRemovingStaging() throws Exception {
        String ns=createNamespace("Import plan expiry lock");
        grantAdministratorAndNamespace(token,ns);
        String operation=UUID.randomUUID().toString(),planId=UUID.randomUUID().toString();
        String stagingPath="staging/imports/"+planId+".zip";
        Path staging=temporary.resolve("resources").resolve(stagingPath);
        Files.createDirectories(staging.getParent());
        Files.writeString(staging,"staged import");
        long initialExpiry=server.sessionTime()+86_400_000L;
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token,"operationId",operation));
            context.actorId=actor;
            context.namespaceId=ns;
            var core=new CoreRepository(tx,context.timestamp);
            context.revision=core.sequence(ns);
            var analysis=new ImportResultRepository(context).save(
                    operation,"analysis",planId,context.revision,initialExpiry,Map.of());
            var plan=tx.createNode(Label.label("DepositImportPlan"));
            Json.map("id",planId,"namespaceId",ns,"actorId",actor,"state","ready",
                    "targetRevision",context.revision,"expiresAt",initialExpiry,
                    "stagingPath",stagingPath,"resultId",analysis.get("resultId")).forEach(plan::setProperty);
            // Plan inspection resolves the result through its real operation receipt.
            core.saveReceipt(context,ns,"analysis-expiry-lock",new Results.Outcome(null,null,analysis));
            tx.commit();
        }

        // The reader reached the plan's last active instant before maintenance's cutoff.
        Thread reader=Thread.currentThread();
        sessionClock(()->Thread.currentThread()==reader?initialExpiry-1:initialExpiry);
        replaceSession(token,ns);
        database.getDependencyResolver().resolveDependency(org.neo4j.kernel.api.procedure.GlobalProcedures.class)
                .registerProcedure(deposit.api.DepositAPI.class);
        var jobsField=StorageManager.class.getDeclaredField("jobs");
        jobsField.setAccessible(true);
        Object jobs=jobsField.get(server.storage);
        var expire=jobs.getClass().getDeclaredMethod("expireImportPlans");
        expire.setAccessible(true);
        var cleanup=new FutureTask<Void>(()->{expire.invoke(jobs);return null;});
        Thread worker=new Thread(cleanup,"import-plan-expiry");

        long renewedExpiry;
        try(var tx=database.beginTx()) {
            new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).lockNamespace(ns);
            worker.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!cleanup.isDone()&&!waitingForNamespaceLock(worker)&&System.nanoTime()<deadline)
                Thread.sleep(10);
            if(cleanup.isDone()) cleanup.get();
            assertTrue(Files.exists(staging),"expiry deleted staging while the reader held the Namespace lock");
            assertTrue(waitingForNamespaceLock(worker),"expiry did not wait for the Namespace lock");

            try(var rows=tx.execute("CALL deposit.get.importPlan($planId, $token) YIELD data RETURN data",
                    Map.of("planId",planId,"token",token))) {
                var lifecycle=ValueCodec.map(ValueCodec.map(rows.next().get("data")).get("plan"));
                renewedExpiry=((Number)lifecycle.get("expiresAt")).longValue();
                assertFalse(rows.hasNext());
            }
            tx.commit();
            cleanup.get(10,TimeUnit.SECONDS);
        } finally {
            worker.join(10_000);
            assertFalse(worker.isAlive(),"expiry worker did not finish");
        }
        assertEquals(initialExpiry-1+86_400_000L,renewedExpiry);
        assertEquals("staged import",Files.readString(staging));
        try(var tx=database.beginTx()) {
            var plan=tx.findNode(Label.label("DepositImportPlan"),"id",planId);
            assertNotNull(plan);
            assertEquals(renewedExpiry,plan.getProperty("expiresAt"));
        }
    }

    private static boolean waitingForNamespaceLock(Thread worker) {
        return Arrays.stream(worker.getStackTrace()).anyMatch(frame->
                frame.getClassName().equals(CoreRepository.class.getName())&&frame.getMethodName().equals("lockNamespace"));
    }

    @Test void unleasedCommitStillExpires() throws Throwable {
        AtomicLong now=new AtomicLong(server.sessionTime());sessionClock(now::get);
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token));
            new SessionActionService(context).begin("deposit.get.session",List.of());
            assertNull(context.session.mutationOwner);
            now.addAndGet(1_800_000L);
            assertTrue(assertThrows(ExceptionHandler.DepositException.class,()->server.beforeCommit(null,tx,database)).getMessage().contains("SESSION_EXPIRED"));
            tx.rollback();
        }
    }

    @Test void revokedSessionNeverUsesMutationLease() {
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token));
            new SessionActionService(context).begin("deposit.create.namespace",List.of("Revoked",Map.of("historyMode","disabled")));
            assertSame(context.session.mutationOwner,context.state.identityToken());
            assertTrue(server.removeSession(Json.hash(token)));
            assertTrue(assertThrows(ExceptionHandler.DepositException.class,()->server.beforeCommit(null,tx,database)).getMessage().contains("AUTH_FAILED"));
            tx.rollback();
        }
    }
}
