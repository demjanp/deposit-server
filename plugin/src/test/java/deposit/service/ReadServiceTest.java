package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.repository.*;

import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Ordinary unscoped Namespace discovery must stay linear in the supplied result set. */
class ReadServiceTest {
    @TempDir java.nio.file.Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    UUID databaseId;
    String previousConfig,actor,token;
    List<String> namespaces;

    @BeforeEach void setup() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        java.nio.file.Files.createDirectory(temporary.resolve("resources"));Files.createDirectory(temporary.resolve("exports"));
        java.nio.file.Path config=temporary.resolve("deposit.properties");
        Files.writeString(config,"resourceRoot="+temporary.resolve("resources").toString().replace('\\','/')+"\nexportRoot="+temporary.resolve("exports").toString().replace('\\','/')+"\n");
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");databaseId=database.databaseId().databaseId().uuid();
        SchemaRepository.initialize(database);
        server=ServerContext.attach(databaseId);server.storage=new StorageManager(server,database);var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        var databaseField=ServerContext.class.getDeclaredField("database");databaseField.setAccessible(true);databaseField.set(server,database);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);
        management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();token=ServerContext.randomToken();
        try(var tx=database.beginTx()) {
            var user=node(tx,"User",Json.map("id",actor,"enabled",true,"serverRole","user"));
            var order=new ArrayList<Node>();
            for(int i=0;i<232;i++) {
                String id=String.format("00000000-0000-0000-0000-%012d",i);
                var ns=node(tx,"Namespace",Json.map("id",id,"name",String.format("N%03d",i),"description","fixture","sequence",(long)i,"historyMode","disabled"));
                user.createRelationshipTo(ns,RelationshipType.withName("HAS_NAMESPACE_ROLE")).setProperty("role","owner");order.add(ns);
            }
            // The source ordering must not accidentally substitute for Repository ordering.
            Collections.reverse(order);
            namespaces=order.stream().map(CoreRepository::id).sorted().toList();
            tx.commit();
        }
    }

    @AfterEach void cleanup() throws Exception {
        try {if(database!=null&&server!=null&&management!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally {try {if(server!=null&&server.storage!=null)server.storage.close();}
        finally {try {if(management!=null)management.shutdown();}
        finally {if(databaseId!=null)ServerContext.detach(databaseId);if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }

    @Test void ordinaryUnscopedNamespaceReadKeepsFieldsAndStaysInsideReadDeadline() throws Exception {
        var rows=read(false,Map.of());
        assertEquals(Collections.nCopies(232,"owner"),rows.stream().map(row->row.data.get("role")).toList());
        assertFields(rows);
        long elapsed=read(false,Map.of()).stream().mapToLong(row->(long)row.data.get("sequence")).sum();
        assertEquals(26796L,elapsed);
    }
    @Test void namespaceReadsPreserveFilteringAdministrativeModeAndStreamClose() throws Exception {
        var id=namespaces.get(122);
        var selected=read(false,Map.of("id",id));
        assertEquals(1,selected.size());assertEquals(id,selected.getFirst().data.get("id"));
        assertEquals("N122",selected.getFirst().data.get("name"));assertFields(selected,List.of(id));
        assertThrows(ExceptionHandler.DepositException.class,()->read(false,Map.of("name","absent")));
        assertEquals(Collections.nCopies(232,"owner"),read(true,Map.of()).stream().map(row->row.data.get("role")).toList());
        try(var tx=database.beginTx()) {
            authenticate(tx,false);
            var context=new RequestContext(tx,Map.of("token",token));
            try(var rows=new ReadService(context).entities("Namespace",Map.of()).onClose(()->{})) {
                assertEquals(232,rows.count());
            }
        }
    }

    @Test void objectPagesProjectOnlyReturnedIdentitiesAndKeepCursorOrder() {
        String namespace=namespaces.getFirst();
        var ids=List.of("00000000-0000-0000-0000-000000001000","00000000-0000-0000-0000-000000001001",
            "00000000-0000-0000-0000-000000001002");
        String classId=UUID.randomUUID().toString(), relationshipId=UUID.randomUUID().toString();
        try(var tx=database.beginTx()) {
            var clazz=node(tx,"Class",Json.map("id",classId,"namespaceId",namespace,"name","Fixture","order",0L));
            for(String id:ids.reversed()) {
                var object=node(tx,"Object",Json.map("id",id,"namespaceId",namespace));
                object.createRelationshipTo(clazz,RelationshipType.withName("MEMBER_OF"));
            }
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.createRelationshipRestored(core.entity("Object",namespace,ids.getFirst()),
                core.entity("Object",namespace,ids.getLast()),"test",Map.of(),actor,relationshipId);
            // This lookahead has an identity but cannot produce a portable Object.
            node(tx,"Object",Json.map("id","00000000-0000-0000-0000-000000001003","namespaceId",namespace));
            tx.commit();
        }
        try(var tx=database.beginTx()) {
            authenticate(tx,false);
            var context=new RequestContext(tx,Map.of("token",token));
            var service=new ReadService(context);
            service.authenticate();
            context.session.namespace=namespace;
            var first=service.page("Object",Map.of("pageSize",2L));
            assertEquals(ids.subList(0,2),pageIds(first));
            assertEquals(List.of(ids.getLast()),pageIds(new ReadService(new RequestContext(tx,Map.of("token",token)))
                .page("Object",Map.of("pageSize",1L,"cursor",first.meta.get("nextCursor")))));
            assertEquals(ids,pageIds(new ReadService(new RequestContext(tx,Map.of("token",token)))
                .page("ClassMembers",Map.of("classId",classId,"pageSize",3L))));
            for(var selector:List.of(Map.of("id",relationshipId),Map.of("sourceId",ids.getFirst()),
                    Map.of("targetId",ids.getLast()))) {
                var selected=new ReadService(new RequestContext(tx,Map.of("token",token)))
                    .page("Relationship",new LinkedHashMap<>(selector));
                assertEquals(List.of(relationshipId),pageIds(selected));
            }
            var second=new ReadService(new RequestContext(tx,Map.of("token",token))).page("Object",
                Map.of("pageSize",2L,"classId",classId,"descriptors",Map.of()));
            assertEquals(ids.subList(0,2),pageIds(second));
            String cursor=(String)second.meta.get("nextCursor");
            assertNotNull(cursor);
            var last=new ReadService(new RequestContext(tx,Map.of("token",token))).page("Object",
                Map.of("pageSize",2L,"classId",classId,"descriptors",Map.of(),"cursor",cursor));
            assertEquals(List.of(ids.getLast()),pageIds(last));
            assertNull(last.meta.get("nextCursor"));
            var invalid=assertThrows(ExceptionHandler.DepositException.class,()->new ReadService(
                new RequestContext(tx,Map.of("token",token))).page("Object",Map.of("pageSize",2L,"cursor",cursor)));
            assertTrue(invalid.getMessage().contains("VALIDATION_FAILED"));
        }
    }

    private static List<Object> pageIds(Results.DataRow page) {
        return ((List<?>)page.data.get("items")).stream().map(item->ValueCodec.map(item).get("id")).toList();
    }

    private void assertFields(List<Results.EntityRow> rows) {assertFields(rows,namespaces);}
    private void assertFields(List<Results.EntityRow> rows,List<String> expectedIds) {
        assertEquals(expectedIds,rows.stream().map(row->(String)row.data.get("id")).toList());
        for(var row:rows)assertEquals(Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","description","historyMode","sequence","role"),row.data.keySet());
    }
    private List<Results.EntityRow> read(boolean administrative,Map<String,Object> selection) throws Exception {
        long start=System.nanoTime();
        try(var tx=database.beginTx()) {authenticate(tx,administrative);var context=new RequestContext(tx,Map.of("token",token));try(var rows=new ReadService(context).entities("Namespace",selection)) {return rows.toList();}}
        finally {long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);System.out.println("NAMESPACE_READ namespaces=232 administrative="+administrative+" selection="+selection+" millis="+millis);assertTrue(millis<15_000,"ordinary discovery read exceeded its bounded test deadline: "+millis+" ms");}
    }
    private void authenticate(Transaction tx,boolean administrative) {
        var c=new RequestContext(tx,Map.of("token",token));
        var session=new ServerContext.Session(actor,((InternalTransaction)tx).securityContext().subject().executingUser(),server.sessionTime());session.administrative=administrative;
        server.registerSession(Json.hash(token),session);new ReadService(c).authenticate();
    }
    private static Node node(Transaction tx,String label,Map<String,Object> value) {
        var node=tx.createNode(Label.label(label));value.forEach(node::setProperty);
        if(!value.containsKey("createdAt")){node.setProperty("createdAt",ZonedDateTime.now(ZoneOffset.UTC));node.setProperty("createdBy","test");node.setProperty("updatedAt",ZonedDateTime.now(ZoneOffset.UTC));node.setProperty("updatedBy","test");}
        return node;
    }
}
