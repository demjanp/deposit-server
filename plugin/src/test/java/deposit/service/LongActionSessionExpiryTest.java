package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.repository.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
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
        server=ServerContext.attach(databaseId);server.storage=new StorageManager(server,database);
        SchemaRepository.initialize(database);
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
            assertFalse(new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).hasReceipt("server",operation));
            assertNull(tx.findNode(Label.label("Namespace"),"name",name));
        }
        assertTrue(assertThrows(ExceptionHandler.DepositException.class,
                ()->publicCall("deposit.get.session",List.of())).getMessage().contains("SESSION_EXPIRED"));
    }

    @Test void planReadKeepsFixedExpiryAndImmutableAnalysis() throws Exception {
        AtomicLong now=new AtomicLong(server.sessionTime());
        sessionClock(now::get);
        String ns;
        try(var tx=database.beginTx()) {
            var core=new CoreRepository(tx,ZonedDateTime.now());
            ns=CoreRepository.id(core.create("Namespace",null,actor,
                Json.map("name","Import plan fixed expiry","sequence",0L,"historyMode","disabled")));
            tx.commit();
        }
        grantAdministratorAndNamespace(token,ns);
        String operation=UUID.randomUUID().toString(),planId=UUID.randomUUID().toString();
        long initialActivity=now.get(), expiry=initialActivity+ImportResultContract.RETENTION;
        Map<String,Object> analysis;
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token,"operationId",operation));
            context.actorId=actor;
            context.namespaceId=ns;
            var core=new CoreRepository(tx,context.timestamp);
            context.revision=core.sequence(ns);
            analysis=new ImportResultRepository(context).save(operation,"analysis",planId,context.revision,Map.of());
            core.saveReceipt(context,ns,"analysis-request",new Results.Outcome(null,null,analysis));
            server.storage.importPlans.save(new deposit.repository.ImportPlanRepository.Plan(planId,ns,actor,
                "inline",null,null,null,null,0,"0".repeat(64),context.revision,initialActivity,initialActivity,expiry,
                "ready","{}",(String)analysis.get("resultId"),false),server.operationalUnit(context.state.kernel));
            tx.commit();
        }
        now.set(expiry-1);
        replaceSession(token,ns);
        database.getDependencyResolver().resolveDependency(org.neo4j.kernel.api.procedure.GlobalProcedures.class)
                .registerProcedure(deposit.api.DepositAPI.class);
        try(var tx=database.beginTx();
            var rows=tx.execute("CALL deposit.get.importPlan($planId, $token) YIELD data RETURN data",
                    Map.of("planId",planId,"token",token))) {
            var inspected=ValueCodec.map(rows.next().get("data"));
            var leased=ValueCodec.map(inspected.get("analysisResult"));
            assertEquals(expiry,ValueCodec.map(inspected.get("plan")).get("expiresAt"));
            assertEquals(ImportResultRepositoryTest.withoutLease(analysis),ImportResultRepositoryTest.withoutLease(leased));
            assertEquals(now.get()+ImportResultContract.READER,leased.get("leaseExpiresAt"));
            assertFalse(rows.hasNext());
            tx.commit();
        }
        try(var tx=database.beginTx()) {
            var plan=server.storage.importPlans.plan(planId);
            assertEquals(expiry,plan.expires());
            assertEquals(initialActivity,plan.lastActivity());
        }
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
