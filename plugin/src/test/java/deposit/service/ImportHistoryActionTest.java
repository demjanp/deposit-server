package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import java.util.concurrent.atomic.AtomicBoolean;

/** Action-boundary coverage for analysis isolation and atomic import history. */
class ImportHistoryActionTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    UUID databaseId;
    String namespace,actor=UUID.randomUUID().toString(),token=ServerContext.randomToken(),pattern;
    String previousConfig;

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
    }

    @AfterEach void cleanup() {
        try {if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally {try {if(server!=null&&server.storage!=null)server.storage.close();}
        finally {try {if(management!=null)management.shutdown();}
            finally {if(databaseId!=null)ServerContext.detach(databaseId);if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }

    private void createNamespace(String historyMode) {
        try(var tx=database.beginTx()) {
            Node user=node(tx,"User",Json.map("id",actor,"enabled",true,"serverRole","user"));
            Node ns=node(tx,"Namespace",Json.map("id",UUID.randomUUID().toString(),"name","Import history","description","before","sequence",0L,"historyMode",historyMode));
            namespace=CoreRepository.id(ns);node(tx,"DepositActorLock",Json.map("id",actor));node(tx,"DepositNamespaceLock",Json.map("id",namespace));
            user.createRelationshipTo(ns,RelationshipType.withName("HAS_NAMESPACE_ROLE")).setProperty("role","editor");
            Node clazz=node(tx,"Class",Json.map("id",UUID.randomUUID().toString(),"namespaceId",namespace,"name","Specimen","position",new double[]{0,0},"order",0L,"comment",""));
            node(tx,"ClassDescriptor",Json.map("id",UUID.randomUUID().toString(),"namespaceId",namespace,"ownerId",CoreRepository.id(clazz),"name","Mass","type","Integer","order",0L,"comment",""));
            pattern=UUID.randomUUID().toString();var definition=Json.map("objects",Json.map("root",Json.map("className","Specimen","root",true,"descriptors",List.of(Json.map("name","Mass","type","Integer")))));
            node(tx,"Pattern",Json.map("id",pattern,"namespaceId",namespace,"name","By mass","definition",Json.write(definition),"comment",""));
            if(!"disabled".equals(historyMode))new HistoryRepository(tx,new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC))).baseline(namespace,actor,0,server.runId,ZonedDateTime.now(ZoneOffset.UTC));
            var internal=(InternalTransaction)tx;var session=new ServerContext.Session(actor,internal.securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;server.registerSession(Json.hash(token),session);
            tx.commit();
        }
    }

    private static Node node(Transaction tx,String label,Map<String,Object> properties) {
        Node node=tx.createNode(Label.label(label));properties.forEach(node::setProperty);return node;
    }

    private static final class ProbeAction extends ActionService {
        ProbeAction(RequestContext context){super(context);}
        Outcome run(String procedure,Map<String,Object> request,boolean changed,java.util.function.Consumer<RequestContext> mutation) {
            if(procedure.equals("deposit.import.apply"))c.historyKind="import";
            return action(procedure,request,false,"editor",()->{mutation.accept(c);return new Change(new Outcome(null,null,Json.map("ok",true)),changed);});
        }
    }

    private Map<String,Object> invoke(String procedure,String operation,long expectedRevision,Map<String,Object> request,boolean commit,java.util.function.Consumer<RequestContext> mutation) {
        try(var tx=database.beginTx()) {
            AtomicBoolean released=new AtomicBoolean();boolean finished=false;
            try {
                var context=new RequestContext(tx,Json.map("token",token,"operationId",operation,"expectedRevision",expectedRevision));
                context.state.release.add(()->released.set(true));
                Map<String,Object> data=new ProbeAction(context).run(procedure,request,procedure.equals("deposit.import.apply"),mutation).data();
                if(commit){tx.commit();finished=true;}else{tx.rollback();finished=true;}
                assertTrue(released.get(),"Neo4j transaction monitor should complete Deposit callbacks");
                return data;
            } finally {if(!finished)tx.rollback();}
        }
    }

    private long revisionCount() {try(var tx=database.beginTx()){return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).nodes("Revision",Map.of("namespaceId",namespace)).size();}}
    private long sequence() {try(var tx=database.beginTx()){return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).sequence(namespace);}}
    private Map<String,Object> definition() {try(var tx=database.beginTx()){return Json.read((String)new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).entity("Pattern",namespace,pattern).getProperty("definition"));}}

    @Test void allImportAnalysisEntryPointsAvoidScientificHistoryAndPatternCanonicalization() {
        createNamespace("persistent");long revisions=revisionCount();
        for(String procedure:List.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern"))
            invoke(procedure,UUID.randomUUID().toString(),0,Map.of(),true,context->{});
        assertEquals(0,sequence());assertEquals(revisions,revisionCount());
        assertTrue(hasPatternProposal());
    }

    @ParameterizedTest @ValueSource(strings={"disabled","transient","persistent"})
    void importApplyWritesOneCompleteHistoryActionAndExactReplay(String mode) {
        createNamespace(mode);long revisionsBefore=revisionCount();String operation=UUID.randomUUID().toString(),plan=UUID.randomUUID().toString();var invocations=new AtomicInteger();
        Map<String,Object> request=Json.map("planId",plan);
        invoke("deposit.import.apply",operation,0,request,true,context->{
            invocations.incrementAndGet();
            var core=new CoreRepository(context.tx,context.timestamp);core.update(core.namespace(namespace),Map.of("description","applied"),actor);
        });
        invoke("deposit.import.apply",operation,0,request,true,context->{throw new AssertionError("Receipt replay must not rerun import application");});
        assertEquals(1,invocations.get());assertEquals(1,sequence());
        assertEquals(revisionsBefore+(!"disabled".equals(mode)?1:0),revisionCount());
        assertFalse(hasPatternProposal());
        if(!"disabled".equals(mode)) {
            try(var tx=database.beginTx()) {
                var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));Node root=core.namespace(namespace);String head=(String)root.getProperty("headRevisionId");Revision revision=new HistoryRepository(tx,core).revision(namespace,head);
                assertEquals("import",revision.kind());
                var prior=revision.before().get(HistoryCodec.key("Namespace",namespace));var current=revision.after().get(HistoryCodec.key("Namespace",namespace));
                assertEquals("before",prior.get("description"));assertEquals("applied",current.get("description"));
                var beforePattern=revision.before().get(HistoryCodec.key("Pattern",pattern));var afterPattern=revision.after().get(HistoryCodec.key("Pattern",pattern));
                assertTrue(ValueCodec.map(ValueCodec.map(beforePattern.get("definition")).get("objects")).values().stream().map(ValueCodec::map).anyMatch(role->ValueCodec.map(((List<?>)role.get("descriptors")).getFirst()).containsKey("type")));
                assertFalse(ValueCodec.map(ValueCodec.map(afterPattern.get("definition")).get("objects")).values().stream().map(ValueCodec::map).anyMatch(role->ValueCodec.map(((List<?>)role.get("descriptors")).getFirst()).containsKey("type")));
            }
        }
    }

    @Test void rollingBackImportApplyLeavesNoRevisionOrScratchIndex() throws Exception {
        createNamespace("persistent");long revisionsBefore=revisionCount();
        invoke("deposit.import.apply",UUID.randomUUID().toString(),0,Json.map("planId",UUID.randomUUID().toString()),false,context->{
            new CoreRepository(context.tx,context.timestamp).update(new CoreRepository(context.tx,context.timestamp).namespace(namespace),Map.of("description","rolled back"),actor);
        });
        assertEquals(0,sequence());assertEquals(revisionsBefore,revisionCount());
        try(var indexes=Files.newDirectoryStream(server.storage.files.path("staging/imports"),"history-index-*")){assertFalse(indexes.iterator().hasNext());}
    }

    private boolean hasPatternProposal() {
        var objects=ValueCodec.map(definition().get("objects"));
        return objects.values().stream().map(ValueCodec::map).anyMatch(role->ValueCodec.map(((List<?>)role.get("descriptors")).getFirst()).containsKey("type"));
    }
}
