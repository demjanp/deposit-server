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

    private void replaceRunId(ServerContext context) throws Exception {
        var field=ServerContext.class.getDeclaredField("runId");field.setAccessible(true);field.set(context,UUID.randomUUID().toString());
        try(var tx=database.beginTx()) {
            var internal=(InternalTransaction)tx;var session=new ServerContext.Session(actor,internal.securityContext().subject().executingUser(),context.sessionTime());session.namespace=namespace;
            context.registerSession(Json.hash(token),session);tx.commit();
        }
    }

    private void replaceRevision(long revision) throws Exception {
        var lifecycle=ServerContext.class.getDeclaredField("transactionLifecycle");lifecycle.setAccessible(true);
        var field=lifecycle.get(server).getClass().getDeclaredField("revision");field.setAccessible(true);field.set(lifecycle.get(server),revision);
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

    private Map<String,Object> invokeCloneUpload(String procedure,String operation,Map<String,Object> request,boolean commit,Long expectedRevision) {
        return invokeCloneUploadAs(token,procedure,operation,request,commit,expectedRevision);
    }

    private Map<String,Object> invokeCloneUploadAs(String authToken,String procedure,String operation,Map<String,Object> request,boolean commit,Long expectedRevision) {
        try(var tx=database.beginTx()) {
            boolean finished=false;
            try {
                var context=new RequestContext(tx,Json.map("token",authToken,"operationId",operation,"expectedRevision",0L));if(expectedRevision==null)context.expectedRevision=server.revision(context.state);else context.expectedRevision=expectedRevision;
                context.procedure=procedure;context.request=request;
                var row=(Results.DataRow)new ProcedureService(context).execute().findFirst().orElseThrow();
                if(commit){tx.commit();finished=true;}else{tx.rollback();finished=true;}
                return row.data;
            } finally {if(!finished)tx.rollback();}
        }
    }

    @Test void cloneUploadIsActorBoundReplaySafeAndRollbackRestoresCommittedOffset() throws Exception {
        createNamespace("disabled");byte[] payload="complete clone package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] prefix="complete ".getBytes(java.nio.charset.StandardCharsets.UTF_8),suffix="clone package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum=ResourceFiles.checksum(payload),begin=UUID.randomUUID().toString();
        Map<String,Object> started=invokeCloneUpload("deposit.import.beginCloneUpload",begin,Json.map("filename","clone.zip","bytes",(long)payload.length,"sha256",checksum),true,null);
        String uploadId=(String)started.get("uploadId");assertEquals(started,invokeCloneUpload("deposit.import.beginCloneUpload",begin,Json.map("filename","clone.zip","bytes",(long)payload.length,"sha256",checksum),true,0L));
        String chunk=UUID.randomUUID().toString();Map<String,Object> chunkRequest=Json.map("uploadId",uploadId,"offset",0L,"bytesBase64",Base64.getEncoder().encodeToString(prefix),"chunkSha256",ResourceFiles.checksum(prefix));
        assertEquals((long)prefix.length,invokeCloneUpload("deposit.import.cloneUploadChunk",chunk,chunkRequest,true,null).get("nextOffset"));
        assertEquals((long)prefix.length,invokeCloneUpload("deposit.import.cloneUploadChunk",UUID.randomUUID().toString(),chunkRequest,true,null).get("nextOffset"));
        String rollback=UUID.randomUUID().toString();
        var rollbackRequest=Json.map("uploadId",uploadId,"offset",(long)prefix.length,"bytesBase64",Base64.getEncoder().encodeToString(suffix),"chunkSha256",ResourceFiles.checksum(suffix));
        invokeCloneUpload("deposit.import.cloneUploadChunk",rollback,rollbackRequest,false,null);
        try(var tx=database.beginTx()) {
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);
            assertEquals((long)prefix.length,upload.getProperty("nextOffset"));
            assertEquals(prefix.length,Files.size(server.storage.files.path((String)upload.getProperty("stagingPath"))));
            tx.rollback();
        }
        assertEquals((long)payload.length,invokeCloneUpload("deposit.import.cloneUploadChunk",UUID.randomUUID().toString(),rollbackRequest,true,null).get("nextOffset"));
        assertEquals(Json.map("uploadId",uploadId,"bytes",(long)payload.length,"sha256",checksum,"state","ready"),
                invokeCloneUpload("deposit.import.finishCloneUpload",UUID.randomUUID().toString(),Json.map("uploadId",uploadId),true,null));
    }

    @Test void cloneUploadChunkFinishAndAbortAreDeniedToDifferentActorWithoutDisclosureOrMutation() throws Exception {
        createNamespace("disabled");String otherActor=UUID.randomUUID().toString(),otherToken=ServerContext.randomToken();
        try(var tx=database.beginTx()){
            Node user=node(tx,"User",Json.map("id",otherActor,"enabled",true,"serverRole","user"));node(tx,"DepositActorLock",Json.map("id",otherActor));
            var internal=(InternalTransaction)tx;var session=new ServerContext.Session(otherActor,internal.securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;server.registerSession(Json.hash(otherToken),session);tx.commit();
        }
        byte[] payload="actor private clone".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(payload);
        Map<String,Object> started=invokeCloneUpload("deposit.import.beginCloneUpload",UUID.randomUUID().toString(),Json.map("filename","private.zip","bytes",(long)payload.length,"sha256",checksum),true,null);String uploadId=(String)started.get("uploadId");
        byte[] first=Arrays.copyOfRange(payload,0,5);Map<String,Object> chunkRequest=Json.map("uploadId",uploadId,"offset",0L,"bytesBase64",Base64.getEncoder().encodeToString(first),"chunkSha256",ResourceFiles.checksum(first));
        for(var attempt:List.of(
                Map.entry("deposit.import.cloneUploadChunk",chunkRequest),
                Map.entry("deposit.import.finishCloneUpload",Json.map("uploadId",uploadId)),
                Map.entry("deposit.import.abortCloneUpload",Json.map("uploadId",uploadId)))){
            ExceptionHandler.DepositException denied=assertThrows(ExceptionHandler.DepositException.class,()->invokeCloneUploadAs(otherToken,attempt.getKey(),UUID.randomUUID().toString(),attempt.getValue(),true,null));
            assertTrue(denied.getMessage().contains("\"code\":\"NOT_FOUND\""),"another actor must receive a non-disclosing NOT_FOUND response");
            try(var tx=database.beginTx()){Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertEquals("uploading",upload.getProperty("state"));assertEquals(0L,upload.getProperty("nextOffset"));assertFalse(Files.exists(server.storage.files.path((String)upload.getProperty("stagingPath"))));assertEquals(0,tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",uploadId)).stream().count());tx.rollback();}
        }
    }

    @Test void clonePackageResourceWithoutAccessReturnsNotFoundWithoutCreatingArtifacts() throws Exception {
        createNamespace("disabled");String caller=UUID.randomUUID().toString(),callerToken=ServerContext.randomToken(),sourceNamespace=UUID.randomUUID().toString(),resourceId=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            Node user=node(tx,"User",Json.map("id",caller,"enabled",true,"serverRole","user"));node(tx,"DepositActorLock",Json.map("id",caller));
            var internal=(InternalTransaction)tx;var session=new ServerContext.Session(caller,internal.securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;server.registerSession(Json.hash(callerToken),session);
            node(tx,"Namespace",Json.map("id",sourceNamespace,"name","Private package source","description","","sequence",0L,"historyMode","disabled"));
            Node resource=node(tx,"DResource",Json.map("id",resourceId,"namespaceId",sourceNamespace,"filename","private-exchange.zip","mediaType","application/zip","bytes",128L,"sha256","a".repeat(64),"state","ready","createdBy",actor));
            node(tx,"DepositResourceLocation",Json.map("id",resourceId,"namespaceId",sourceNamespace,"storedFilename","private-exchange.zip","bytes",128L,"checksum","a".repeat(64),"leaseUntil",0L,"everAttached",true,"published",true));
            node(tx,"DepositExportGrant",Json.map("id",UUID.randomUUID().toString(),"resourceId",resourceId,"namespaceId",sourceNamespace,"actorId",caller,"history",true,"expires",server.sessionTime()+ResourceFiles.LEASE));
            Node descriptor=node(tx,"Descriptor",Json.map("id",UUID.randomUUID().toString(),"namespaceId",sourceNamespace,"name","Exchange package"));descriptor.createRelationshipTo(resource,RelationshipType.withName("HAS_RESOURCE"));tx.commit();
        }
        Path admissionRoot=server.storage.files.root.resolve("admission");Set<String> before;try(var rows=Files.list(admissionRoot)){before=rows.map(path->path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}
        ExceptionHandler.DepositException denied=assertThrows(ExceptionHandler.DepositException.class,()->invokeCloneUploadAs(callerToken,"deposit.import.cloneNamespace",operation,Json.map("source",Json.map("resourceId",resourceId)),true,serverRevision()));
        assertTrue(denied.getMessage().contains("\"code\":\"NOT_FOUND\""),"inaccessible package resources must not be disclosed");
        try(var tx=database.beginTx()){
            try(var matches=tx.findNodes(Label.label("Namespace"),"name","Private package source")){assertEquals(1,matches.stream().count());}
            assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",operation));
            try(var clones=tx.findNodes(Label.label("Namespace"),"name","private-exchange.zip")){assertEquals(0,clones.stream().count());}
            tx.rollback();
        }
        try(var rows=Files.list(admissionRoot)){assertEquals(before,rows.map(path->path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),"unauthorized clone creates no storage admission artifact");}
    }

    @Test void authorizedActorCanCloneAttachedPackageResource() throws Exception {
        createNamespace("disabled");String sourceNamespace=UUID.randomUUID().toString(),resourceId=UUID.randomUUID().toString();Path archive=temporary.resolve("authorized-package-resource.zip");
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",sourceNamespace,"name","Authorized Package","description","resource source","depositVersion","2.0.0","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());
        ExchangePackageCodec.write(archive,manifest,graph,null,Map.of());String checksum=ResourceFiles.checksum(archive);long bytes=Files.size(archive);String stored=ResourceFiles.filename("authorized-package-resource.zip",checksum);
        var content=new ResourceStorage.Content(resourceId,stored,bytes,checksum);server.storage.content.publish(content,archive);
        try(var tx=database.beginTx()){
            Node source=node(tx,"Namespace",Json.map("id",sourceNamespace,"name","Authorized source","description","","sequence",0L,"historyMode","disabled"));
            Node user=tx.findNode(Label.label("User"),"id",actor);user.createRelationshipTo(source,RelationshipType.withName("HAS_NAMESPACE_ROLE")).setProperty("role","viewer");
            Node resource=node(tx,"DResource",Json.map("id",resourceId,"namespaceId",sourceNamespace,"filename","authorized-package-resource.zip","storedFilename",stored,"mediaType","application/zip","bytes",bytes,"checksum",checksum,"state","ready","createdBy",actor));
            node(tx,"DepositResourceLocation",Json.map("id",resourceId,"namespaceId",sourceNamespace,"storedFilename",stored,"bytes",bytes,"checksum",checksum,"leaseUntil",0L,"everAttached",true,"published",true));
            node(tx,"DepositExportGrant",Json.map("id",UUID.randomUUID().toString(),"resourceId",resourceId,"namespaceId",sourceNamespace,"actorId",actor,"history",true,"expires",server.sessionTime()+ResourceFiles.LEASE));
            Node descriptor=node(tx,"Descriptor",Json.map("id",UUID.randomUUID().toString(),"namespaceId",sourceNamespace,"name","Exchange package"));descriptor.createRelationshipTo(resource,RelationshipType.withName("HAS_RESOURCE"));tx.commit();
        }

        String operation=UUID.randomUUID().toString();Map<String,Object> result=invokeCloneUpload("deposit.import.cloneNamespace",operation,Json.map("source",Json.map("resourceId",resourceId)),true,serverRevision());
        assertNotNull(result.get("namespaceId"));assertNotNull(result.get("resultId"));
        try(var tx=database.beginTx()){Node cloned=tx.findNode(Label.label("Namespace"),"id",(String)result.get("namespaceId"));assertNotNull(cloned);assertEquals("Authorized Package",cloned.getProperty("name"));assertNotNull(tx.findNode(Label.label("DepositReceipt"),"operationId",operation));tx.rollback();}

    }

    @Test void abortedCloneUploadIsReclaimedByExpiryMaintenance() throws Exception {
        createNamespace("disabled");byte[] payload="partial clone".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(payload);
        Map<String,Object> started=invokeCloneUpload("deposit.import.beginCloneUpload",UUID.randomUUID().toString(),Json.map("filename","clone.zip","bytes",(long)payload.length,"sha256",checksum),true,null);String uploadId=(String)started.get("uploadId");String abortOperation=UUID.randomUUID().toString();
        assertEquals(Json.map("aborted",true),invokeCloneUpload("deposit.import.abortCloneUpload",abortOperation,Json.map("uploadId",uploadId),true,null));
        String stagingPath,admissionId;try(var tx=database.beginTx()){Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertNotNull(upload);assertEquals("aborted",upload.getProperty("state"));stagingPath=(String)upload.getProperty("stagingPath");admissionId=(String)upload.getProperty("admissionId");tx.rollback();}
        Path staged=server.storage.files.path(stagingPath);server.storage.recover();
        assertFalse(Files.exists(staged));assertFalse(Files.exists(server.storage.files.root.resolve("admission").resolve(admissionId+".json")));
        try(var tx=database.beginTx()){assertNull(tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId));tx.rollback();}
    }

    @Test void expiredCloneUploadChunkLedgerIsReclaimedInBoundedRestartableBatches() throws Exception {
        createNamespace("disabled");String uploadId=UUID.randomUUID().toString();int chunkCount=700;
        String relative="staging/imports/clone-uploads/large-expired-ledger.zip";Path staged=server.storage.files.path(relative);Files.createDirectories(staged.getParent());Files.write(staged,new byte[]{1});
        try(var tx=database.beginTx()){
            Node upload=node(tx,"DepositCloneUpload",Json.map("id",uploadId,"actorId",actor,"filename","large.zip","bytes",1L,"sha256",ResourceFiles.checksum(new byte[]{1}),"nextOffset",1L,"state","aborted","expires",0L,"stagingPath",relative));
            for(int index=0;index<chunkCount;index++)node(tx,"DepositCloneUploadChunk",Json.map("id",uploadId+"/"+index,"uploadId",uploadId,"offset",(long)index,"length",1L,"checksum","0".repeat(64),"nextOffset",(long)index+1));
            tx.commit();
        }
        server.storage.recover();
        try(var tx=database.beginTx()){
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertNotNull(upload,"ledger remains the durable cleanup owner between bounded passes");
            assertEquals(1L,upload.getProperty("cleanupAttempts"));assertFalse(Files.exists(staged));
            assertEquals((long)(chunkCount-256),tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",uploadId)).stream().count());tx.rollback();
        }
        try(var tx=database.beginTx()){tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId).setProperty("expires",0L);tx.commit();}
        server.storage.recover();
        try(var tx=database.beginTx()){
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertNotNull(upload);
            assertEquals((long)(chunkCount-512),tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",uploadId)).stream().count());tx.rollback();
        }
        server.storage.recover();
        try(var tx=database.beginTx()){
            assertNull(tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId));
            assertEquals(0,tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",uploadId)).stream().count());tx.rollback();
        }
    }

    @Test void cloneUploadCoordinatesHaveUniqueIndexedLookupAcrossUploads() {
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            Node one=tx.createNode(Label.label("DepositCloneUpload"));one.setProperty("id",first);one.setProperty("actorId",actor);one.setProperty("state","uploading");one.setProperty("expires",1L);
            Node two=tx.createNode(Label.label("DepositCloneUpload"));two.setProperty("id",second);two.setProperty("actorId",actor);two.setProperty("state","uploading");two.setProperty("expires",1L);
            for(String upload:List.of(first,second)){Node chunk=tx.createNode(Label.label("DepositCloneUploadChunk"));chunk.setProperty("id",upload+"/0");chunk.setProperty("uploadId",upload);chunk.setProperty("offset",0L);chunk.setProperty("length",1L);chunk.setProperty("checksum","0".repeat(64));chunk.setProperty("nextOffset",1L);}
            try(var found=tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",first,"offset",0L))){assertTrue(found.hasNext());assertEquals(first,found.next().getProperty("uploadId"));assertFalse(found.hasNext());}
            var explained=tx.execute("EXPLAIN MATCH (chunk:DepositCloneUploadChunk {uploadId:$uploadId, offset:$offset}) RETURN chunk",Map.of("uploadId",first,"offset",0L));String plan=explained.getExecutionPlanDescription().toString();assertTrue(plan.contains("NodeUniqueIndexSeek")||plan.contains("NodeIndexSeek"),plan);
            var expiry=tx.execute("EXPLAIN MATCH (upload:DepositCloneUpload) WHERE upload.expires <= $now RETURN upload.id",Map.of("now",0L));String expiryPlan=expiry.getExecutionPlanDescription().toString();assertTrue(expiryPlan.contains("NodeIndexSeekByRange")||expiryPlan.contains("NodeIndexSeek"),expiryPlan);
            tx.rollback();
        }
    }

    @Test void cloneNamespaceCommitsAndReplaysAsOneServerScopedActionAndRejectsNameCollision() throws Exception {
        createNamespace("disabled");String sourceNamespace=UUID.randomUUID().toString();Path archive=temporary.resolve("clone-source.zip");
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",sourceNamespace,"name","Cloned Namespace","description","copied","depositVersion","2.0.0","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());
        ExchangePackageCodec.write(archive,manifest,graph,null,Map.of());byte[] bytes=Files.readAllBytes(archive);String checksum=ResourceFiles.checksum(bytes);
        String uploadId=uploadClonePackage(bytes,checksum);String operation=UUID.randomUUID().toString();long expected=serverRevision();var request=Json.map("source",Json.map("uploadId",uploadId,"sha256",checksum));
        Map<String,Object> first=invokeCloneUpload("deposit.import.cloneNamespace",operation,request,true,expected);
        assertEquals("clone",first.get("kind"));String clonedId=(String)first.get("namespaceId");assertNotNull(clonedId);
        assertEquals(first,invokeCloneUpload("deposit.import.cloneNamespace",operation,request,true,expected));
        try(var tx=database.beginTx()){
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertEquals("consumed",upload.getProperty("state"));
            assertTrue(Files.exists(server.storage.files.path((String)upload.getProperty("stagingPath"))),"committed clone retains durable upload ownership until maintenance cleanup");
            assertEquals(1,tx.findNodes(Label.label("DepositCloneUploadChunk"),Map.of("uploadId",uploadId)).stream().count());tx.rollback();
        }
        try(var tx=database.beginTx()){
            var cloned=tx.findNode(Label.label("Namespace"),"id",clonedId);assertNotNull(cloned);assertEquals("Cloned Namespace",cloned.getProperty("name"));
            try(var matching=tx.findNodes(Label.label("Namespace"),"name","Cloned Namespace")){assertEquals(1,matching.stream().count());}
            assertNotNull(tx.findNode(Label.label("DepositReceipt"),"operationId",operation));tx.rollback();
        }
        try(var tx=database.beginTx()){tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId).setProperty("expires",0L);tx.commit();}
        server.storage.recover();
        try(var tx=database.beginTx()){assertNull(tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId));tx.rollback();}

        String collisionUpload=uploadClonePackage(bytes,checksum);String collisionOperation=UUID.randomUUID().toString();long collisionRevision=serverRevision();
        var collisionRequest=Json.map("source",Json.map("uploadId",collisionUpload,"sha256",checksum));
        assertThrows(ExceptionHandler.DepositException.class,()->invokeCloneUpload("deposit.import.cloneNamespace",collisionOperation,collisionRequest,true,collisionRevision));
        try(var tx=database.beginTx()){
            try(var matching=tx.findNodes(Label.label("Namespace"),"name","Cloned Namespace")){assertEquals(1,matching.stream().count());}
            assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",collisionOperation));tx.rollback();
        }

        var rollbackManifest=new LinkedHashMap<>(manifest);rollbackManifest.put("packageId",UUID.randomUUID().toString());rollbackManifest.put("sourceNamespaceId",UUID.randomUUID().toString());rollbackManifest.put("name","Rollback Namespace");
        Path rollbackArchive=temporary.resolve("clone-rollback-source.zip");ExchangePackageCodec.write(rollbackArchive,rollbackManifest,graph,null,Map.of());byte[] rollbackBytes=Files.readAllBytes(rollbackArchive);String rollbackChecksum=ResourceFiles.checksum(rollbackBytes);
        String rollbackUpload=uploadClonePackage(rollbackBytes,rollbackChecksum);String rollbackOperation=UUID.randomUUID().toString();long rollbackRevision=serverRevision();
        var rollbackRequest=Json.map("source",Json.map("uploadId",rollbackUpload,"sha256",rollbackChecksum));
        Map<String,Object> rolledBack=invokeCloneUpload("deposit.import.cloneNamespace",rollbackOperation,rollbackRequest,false,rollbackRevision);
        assertEquals("clone",rolledBack.get("kind"));
        try(var tx=database.beginTx()){
            assertNull(tx.findNode(Label.label("Namespace"),"id",rolledBack.get("namespaceId")));
            assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",rollbackOperation));
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",rollbackUpload);assertEquals("finished",upload.getProperty("state"));tx.rollback();
        }
    }

    @Test void cloneStreamingValidationCancellationRollsBackAndRetainsUploadForRetry() throws Exception {
        createNamespace("disabled");String sourceNamespace=UUID.randomUUID().toString();Path archive=temporary.resolve("clone-cancel-source.zip");
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",sourceNamespace,"name","Cancelled Clone","description","cancel","depositVersion","2.0.0","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());
        ExchangePackageCodec.write(archive,manifest,graph,null,Map.of());byte[] bytes=Files.readAllBytes(archive);String checksum=ResourceFiles.checksum(bytes);String uploadId=uploadClonePackage(bytes,checksum),operation=UUID.randomUUID().toString();Path staged;String admissionId;
        try(var tx=database.beginTx()){Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);staged=server.storage.files.path((String)upload.getProperty("stagingPath"));admissionId=(String)upload.getProperty("admissionId");tx.rollback();}
        var observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);AtomicBoolean cancelled=new AtomicBoolean();observer.invoke(server,(java.util.function.Consumer<String>)point->{if("clone-validation".equals(point)&&cancelled.compareAndSet(false,true))throw new IllegalStateException("injected clone validation cancellation");});
        assertThrows(IllegalStateException.class,()->invokeCloneUpload("deposit.import.cloneNamespace",operation,Json.map("source",Json.map("uploadId",uploadId,"sha256",checksum)),true,serverRevision()));observer.invoke(server,(java.util.function.Consumer<String>)point->{});
        assertTrue(cancelled.get(),"clone must use the streaming validation callback checkpoint");assertTrue(Files.exists(staged));assertTrue(Files.exists(server.storage.files.root.resolve("admission").resolve(admissionId+".json")));
        try(var tx=database.beginTx()){assertNull(tx.findNode(Label.label("Namespace"),"name","Cancelled Clone"));assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",operation));assertEquals("finished",tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId).getProperty("state"));tx.rollback();}
    }

    @Test void cloneNamespaceMaterializesHistoryOnlyResourceAndRetainsItInClonedSnapshot() throws Exception {
        createNamespace("disabled");String sourceNamespace=UUID.randomUUID().toString(),sourceResource=UUID.randomUUID().toString();
        String sourceClass=UUID.randomUUID().toString(),sourceObject=UUID.randomUUID().toString(),sourceDescriptor=UUID.randomUUID().toString();
        String sourceEpoch=UUID.randomUUID().toString(),sourceRevision=UUID.randomUUID().toString();
        byte[] content="historical-only resource payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(content);
        Path contentPath=temporary.resolve("historical-only.bin");Files.write(contentPath,content);
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",sourceNamespace,"name","History Resource Clone","description","history","depositVersion","2.0.0","historyMode","persistent","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),
                "resources",List.of(Json.map("kind","DResource","id",sourceResource,"filename","historical-only.bin","bytes",(long)content.length,"mediaType","application/octet-stream","sha256",checksum,"entry","resources/"+checksum)));
        var images=List.of(
                Json.map("kind","Namespace","id",sourceNamespace,"namespaceId",sourceNamespace,"name","History Source","description",""),
                Json.map("kind","Class","id",sourceClass,"namespaceId",sourceNamespace,"name","Historical","position",List.of(0.0,0.0),"order",0L,"comment",""),
                Json.map("kind","Object","id",sourceObject,"namespaceId",sourceNamespace,"position",List.of(0.0,0.0)),
                Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",sourceNamespace,"objectId",sourceObject,"classId",sourceClass),
                Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",sourceNamespace,"classId",sourceClass,"name","Attachment","type","DResource","order",0L,"comment",""),
                Json.map("kind","Descriptor","id",sourceDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Attachment","value",Json.map("resourceId",sourceResource)));
        List<Map<String,Object>> changes=images.stream().map(image->Json.map("entityKind",image.get("kind"),"entityId",image.get("id"),"before",null,"after",image)).toList();
        var revision=Json.map("revisionId",sourceRevision,"namespaceId",sourceNamespace,"sequence",0L,"historyEpochId",sourceEpoch,"historyMode","persistent","operationId",UUID.randomUUID().toString(),"actorId",actor,"committedAt","2026-01-01T00:00:00Z","kind","baseline","summary","Baseline","parentRevisionId",null,"changes",changes,"touches",Map.of(),"dependencies",List.of());
        var epoch=Json.map("id",sourceEpoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",sourceRevision,"headRevisionId",sourceRevision,"baseline",revision,"revisions",List.of(revision));
        var history=Json.map("namespaceId",sourceNamespace,"historyMode","persistent","activeEpochId",sourceEpoch,"headRevisionId",sourceRevision,"epochs",List.of(epoch),"complete",true);
        Path archive=temporary.resolve("clone-history-resource.zip");ExchangePackageCodec.write(archive,manifest,graph,history,Map.of(checksum,contentPath));
        byte[] bytes=Files.readAllBytes(archive);String packageChecksum=ResourceFiles.checksum(bytes);String uploadId=uploadClonePackage(bytes,packageChecksum);
        String failedOperation=UUID.randomUUID().toString();var failedRequest=Json.map("source",Json.map("uploadId",uploadId,"sha256",packageChecksum));
        var observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);
        observer.invoke(server,(java.util.function.Consumer<String>)point->{if("clone-after-result-save".equals(point))throw new IllegalStateException("injected clone result publication failure");});
        assertThrows(IllegalStateException.class,()->invokeCloneUpload("deposit.import.cloneNamespace",failedOperation,failedRequest,true,serverRevision()));
        observer.invoke(server,(java.util.function.Consumer<String>)point->{});
        try(var tx=database.beginTx()){
            try(var namespaces=tx.findNodes(Label.label("Namespace"),"name","History Resource Clone")){assertEquals(0,namespaces.stream().count());}
            for(String label:List.of("DepositHistoryEpoch","Revision","DepositSnapshot","DepositImportResult"))try(var records=tx.findNodes(Label.label(label))){assertEquals(0,records.stream().count(),label+" must roll back with the failed clone");}
            assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",failedOperation));
            try(var resources=tx.findNodes(Label.label("DResource"),"filename","historical-only.bin")){assertEquals(0,resources.stream().count());}
            Node upload=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertEquals("finished",upload.getProperty("state"));assertTrue(Files.exists(server.storage.files.path((String)upload.getProperty("stagingPath"))),"rollback preserves the original upload bytes for retry");assertTrue(Files.exists(server.storage.files.root.resolve("admission").resolve((String)upload.getProperty("admissionId")+".json")),"rollback preserves upload admission");tx.rollback();
        }
        Map<String,Object> envelope=invokeCloneUpload("deposit.import.cloneNamespace",UUID.randomUUID().toString(),Json.map("source",Json.map("uploadId",uploadId,"sha256",packageChecksum)),true,serverRevision());
        String clonedNamespace=(String)envelope.get("namespaceId"),resultId=(String)envelope.get("resultId");assertNotNull(clonedNamespace);assertNotNull(resultId);
        try(var tx=database.beginTx()){
            var context=new RequestContext(tx,Json.map("token",token));context.actorId=actor;
            var page=new ImportResultRepository(context).clonePage(Json.map("resultId",resultId,"collection","resourceMapping","limit",128L));
            var row=ValueCodec.map(((List<?>)page.get("entries")).getFirst());String clonedResource=(String)ValueCodec.map(row.get("value")).get("value");
            assertEquals(sourceResource,ValueCodec.map(row.get("key")).get("value"));assertNotNull(clonedResource);
            Node resource=tx.findNode(Label.label("DResource"),"id",clonedResource);assertNotNull(resource);assertEquals(clonedNamespace,resource.getProperty("namespaceId"));assertTrue(server.storage.content.exists(clonedResource));
            Node snapshot=null;try(var snapshots=tx.findNodes(Label.label("DepositSnapshot"),"namespaceId",clonedNamespace)){while(snapshots.hasNext()){Node candidate=snapshots.next();if("Descriptor".equals(candidate.getProperty("entityKind"))){snapshot=candidate;break;}}}
            assertNotNull(snapshot);var retained=snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"));assertTrue(retained.iterator().hasNext());assertEquals(clonedResource,retained.iterator().next().getEndNode().getProperty("id"));
            tx.commit();
        }
    }


    private String uploadClonePackage(byte[] bytes,String checksum){
        Map<String,Object> started=invokeCloneUpload("deposit.import.beginCloneUpload",UUID.randomUUID().toString(),Json.map("filename","clone.zip","bytes",(long)bytes.length,"sha256",checksum),true,null);
        String id=(String)started.get("uploadId");var chunk=Json.map("uploadId",id,"offset",0L,"bytesBase64",Base64.getEncoder().encodeToString(bytes),"chunkSha256",checksum);
        invokeCloneUpload("deposit.import.cloneUploadChunk",UUID.randomUUID().toString(),chunk,true,null);
        invokeCloneUpload("deposit.import.finishCloneUpload",UUID.randomUUID().toString(),Json.map("uploadId",id),true,null);return id;
    }

    private Map<String,Object> invokeNamespaceProcedure(String procedure,Map<String,Object> request,String selectedNamespace) {
        try(var tx=database.beginTx()) {
            boolean finished=false;
            try {
                long revision=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).sequence(selectedNamespace);
                var context=new RequestContext(tx,Json.map("token",token,"operationId",UUID.randomUUID().toString(),"expectedRevision",revision));
                context.procedure=procedure;context.request=request;
                Map<String,Object> data=((Results.DataRow)new ProcedureService(context).execute().findFirst().orElseThrow()).data;
                tx.commit();finished=true;return data;
            } finally {if(!finished)tx.rollback();}
        }
    }

    @Test void persistentCloneKeepsCurrentClassDescriptorIdentityAcrossUndoRedoAndClosedEpochRestore() throws Exception {
        createNamespace("disabled");
        String sourceNamespace=UUID.randomUUID().toString(),sourceClass=UUID.randomUUID().toString(),sourceDeclaration=UUID.randomUUID().toString(),sourceListDeclaration=UUID.randomUUID().toString(),sourceTransitionDeclaration=UUID.randomUUID().toString();
        String sourceObject=UUID.randomUUID().toString(),sourceMembership=UUID.randomUUID().toString(),sourceMassDescriptor=UUID.randomUUID().toString(),sourceListDescriptor=UUID.randomUUID().toString(),sourceTransitionDescriptor=UUID.randomUUID().toString();
        String closedEpoch=UUID.randomUUID().toString(),closedRevision=UUID.randomUUID().toString();
        String activeEpoch=UUID.randomUUID().toString(),baselineRevision=UUID.randomUUID().toString(),editRevision=UUID.randomUUID().toString();
        var descriptor=Json.map("id",sourceDeclaration,"name","Mass","type","Integer","order",0L,"comment","");
        var namespaceImage=Json.map("kind","Namespace","id",sourceNamespace,"namespaceId",sourceNamespace,"name","Declaration Source","description","");
        var baseClass=Json.map("kind","Class","id",sourceClass,"namespaceId",sourceNamespace,"name","Specimen","position",List.of(0.0,0.0),"order",0L,"comment","base");
        var editedClass=new LinkedHashMap<>(baseClass);editedClass.put("comment","edited");
        var declarationImage=Json.map("kind","ClassDescriptor","id",sourceDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Mass","type","Integer","order",0L,"comment","");
        var listDeclarationImage=Json.map("kind","ClassDescriptor","id",sourceListDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Series","type","List","order",1L,"comment","");
        var beforeTransitionDeclaration=Json.map("kind","ClassDescriptor","id",sourceTransitionDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Transition","type","String","order",2L,"comment","");
        var afterTransitionDeclaration=new LinkedHashMap<>(beforeTransitionDeclaration);afterTransitionDeclaration.put("type","Integer");
        var objectImage=Json.map("kind","Object","id",sourceObject,"namespaceId",sourceNamespace,"position",List.of(1.0,2.0));
        var membershipImage=Json.map("kind","Membership","id",sourceMembership,"namespaceId",sourceNamespace,"objectId",sourceObject,"classId",sourceClass);
        var beforeMass=Json.map("kind","Descriptor","id",sourceMassDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Mass","value","5");
        var afterMass=Json.map("kind","Descriptor","id",sourceMassDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Mass","value","9");
        var beforeSeries=Json.map("kind","Descriptor","id",sourceListDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Series","value",Json.map("itemType","Integer","items",List.of("5","6")));
        var afterSeries=Json.map("kind","Descriptor","id",sourceListDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Series","value",Json.map("itemType","Integer","items",List.of("9","10")));
        var beforeTransition=Json.map("kind","Descriptor","id",sourceTransitionDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Transition","value","old");
        var afterTransition=Json.map("kind","Descriptor","id",sourceTransitionDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Transition","value","11");
        java.util.function.BiFunction<String,String,Map<String,Object>> baseline=(epoch,revision)->Json.map("revisionId",revision,"namespaceId",sourceNamespace,"sequence",0L,"historyEpochId",epoch,"historyMode","persistent","operationId",UUID.randomUUID().toString(),"actorId",actor,"committedAt","2026-01-01T00:00:00Z","kind","baseline","summary","Baseline","parentRevisionId",null,"changes",List.of(
                Json.map("entityKind","Namespace","entityId",sourceNamespace,"before",null,"after",namespaceImage),
                Json.map("entityKind","Class","entityId",sourceClass,"before",null,"after",baseClass),
                Json.map("entityKind","ClassDescriptor","entityId",sourceDeclaration,"before",null,"after",declarationImage),
                Json.map("entityKind","ClassDescriptor","entityId",sourceListDeclaration,"before",null,"after",listDeclarationImage),
                Json.map("entityKind","ClassDescriptor","entityId",sourceTransitionDeclaration,"before",null,"after",beforeTransitionDeclaration),
                Json.map("entityKind","Object","entityId",sourceObject,"before",null,"after",objectImage),
                Json.map("entityKind","Membership","entityId",sourceMembership,"before",null,"after",membershipImage),
                Json.map("entityKind","Descriptor","entityId",sourceMassDescriptor,"before",null,"after",beforeMass),
                Json.map("entityKind","Descriptor","entityId",sourceListDescriptor,"before",null,"after",beforeSeries),
                Json.map("entityKind","Descriptor","entityId",sourceTransitionDescriptor,"before",null,"after",beforeTransition)),"touches",Map.of(),"dependencies",List.of());
        var closedBase=baseline.apply(closedEpoch,closedRevision);
        var closed=Json.map("id",closedEpoch,"mode","persistent","closed",true,"startedSequence",0L,"endedSequence",0L,"baselineRevisionId",closedRevision,"headRevisionId",closedRevision,"baseline",closedBase,"revisions",List.of(closedBase));
        var activeBase=baseline.apply(activeEpoch,baselineRevision);
        var change=Json.map("entityKind","Class","entityId",sourceClass,"before",baseClass,"after",editedClass);
        var declarationChange=Json.map("entityKind","ClassDescriptor","entityId",sourceTransitionDeclaration,"before",beforeTransitionDeclaration,"after",afterTransitionDeclaration);var massChange=Json.map("entityKind","Descriptor","entityId",sourceMassDescriptor,"before",beforeMass,"after",afterMass);var seriesChange=Json.map("entityKind","Descriptor","entityId",sourceListDescriptor,"before",beforeSeries,"after",afterSeries);var transitionChange=Json.map("entityKind","Descriptor","entityId",sourceTransitionDescriptor,"before",beforeTransition,"after",afterTransition);
        var activeEdit=Json.map("revisionId",editRevision,"namespaceId",sourceNamespace,"sequence",1L,"historyEpochId",activeEpoch,"historyMode","persistent","operationId",UUID.randomUUID().toString(),"actorId",actor,"committedAt","2026-01-01T00:00:01Z","kind","edit","summary","Edit class and typed descriptors","parentRevisionId",baselineRevision,"changes",List.of(change,declarationChange,massChange,seriesChange,transitionChange),"touches",Map.of(HistoryCodec.key("Class",sourceClass),List.of("comment"),HistoryCodec.key("ClassDescriptor",sourceTransitionDeclaration),List.of("type"),HistoryCodec.key("Descriptor",sourceMassDescriptor),List.of("value"),HistoryCodec.key("Descriptor",sourceListDescriptor),List.of("value"),HistoryCodec.key("Descriptor",sourceTransitionDescriptor),List.of("value")),"dependencies",List.of(HistoryCodec.key("Class",sourceClass),HistoryCodec.key("ClassDescriptor",sourceTransitionDeclaration),HistoryCodec.key("Descriptor",sourceMassDescriptor),HistoryCodec.key("Descriptor",sourceListDescriptor),HistoryCodec.key("Descriptor",sourceTransitionDescriptor)));
        var active=Json.map("id",activeEpoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",baselineRevision,"headRevisionId",editRevision,"baseline",activeBase,"revisions",List.of(activeBase,activeEdit));
        var history=Json.map("namespaceId",sourceNamespace,"historyMode","persistent","activeEpochId",activeEpoch,"headRevisionId",editRevision,"epochs",List.of(closed,active),"complete",true);
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",sourceNamespace,"name","Declaration Identity Clone","description","history identity","depositVersion","2.0.0","historyMode","persistent","exportedRevision",1L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
        var graph=Json.map("classes",List.of(Json.map("kind","Class","id",sourceClass,"namespaceId",sourceNamespace,"name","Specimen","position",List.of(0.0,0.0),"order",0L,"comment","edited","descriptors",List.of(Json.map("kind","ClassDescriptor","id",sourceDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Mass","type","Integer","order",0L,"comment",""),Json.map("kind","ClassDescriptor","id",sourceListDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Series","type","List","order",1L,"comment",""),Json.map("kind","ClassDescriptor","id",sourceTransitionDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Transition","type","Integer","order",2L,"comment","")))),"objects",List.of(Json.map("kind","Object","id",sourceObject,"namespaceId",sourceNamespace,"classId",sourceClass,"position",List.of(1.0,2.0))),"descriptors",List.of(Json.map("kind","Descriptor","id",sourceMassDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Mass","value","9"),Json.map("kind","Descriptor","id",sourceListDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Series","value",Json.map("itemType","Integer","items",List.of("9","10"))),Json.map("kind","Descriptor","id",sourceTransitionDescriptor,"namespaceId",sourceNamespace,"objectId",sourceObject,"name","Transition","value","11")),"geometries",List.of(),"memberships",List.of(Json.map("kind","Membership","id",sourceMembership,"namespaceId",sourceNamespace,"objectId",sourceObject,"classId",sourceClass)),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());
        Path archive=temporary.resolve("clone-class-descriptor-history.zip");ExchangePackageCodec.write(archive,manifest,graph,history,Map.of());byte[] bytes=Files.readAllBytes(archive);String checksum=ResourceFiles.checksum(bytes);String uploadId=uploadClonePackage(bytes,checksum);
        Map<String,Object> result=invokeCloneUpload("deposit.import.cloneNamespace",UUID.randomUUID().toString(),Json.map("source",Json.map("uploadId",uploadId,"sha256",checksum)),true,serverRevision());
        String destination=(String)result.get("namespaceId"),resultId=(String)result.get("resultId");assertNotNull(destination);
        String targetDeclaration,targetMassDescriptor,targetListDescriptor,targetTransitionDeclaration,targetTransitionDescriptor;
        try(var tx=database.beginTx()) {
            var cloned=tx.findNodes(Label.label("ClassDescriptor"),Map.of("namespaceId",destination,"name","Mass"));assertTrue(cloned.hasNext());Node declarationNode=cloned.next();assertFalse(cloned.hasNext());targetDeclaration=CoreRepository.id(declarationNode);
            try(var declarations=tx.findNodes(Label.label("ClassDescriptor"),Map.of("namespaceId",destination,"name","Transition"))){assertTrue(declarations.hasNext());targetTransitionDeclaration=CoreRepository.id(declarations.next());}
            Node object;try(var objects=tx.findNodes(Label.label("Object"),Map.of("namespaceId",destination))){assertTrue(objects.hasNext());object=objects.next();assertFalse(objects.hasNext());}
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));targetMassDescriptor=CoreRepository.id(core.descriptor(object,"Mass"));targetListDescriptor=CoreRepository.id(core.descriptor(object,"Series"));targetTransitionDescriptor=CoreRepository.id(core.descriptor(object,"Transition"));
            var context=new RequestContext(tx,Json.map("token",token));context.actorId=actor;
            Map<String,Object> page=new ImportResultRepository(context).clonePage(Json.map("resultId",resultId,"collection","mapping","limit",128L));
            boolean matched=false;for(Object raw:(List<?>)page.get("entries")){var entry=ValueCodec.map(raw);if(sourceDeclaration.equals(ValueCodec.map(entry.get("key")).get("value"))){assertEquals(targetDeclaration,ValueCodec.map(entry.get("value")).get("value"));matched=true;}}
            assertTrue(matched,"clone result must map the source ClassDescriptor to the materialized current declaration");tx.commit();
        }
        server.session(Json.hash(token)).namespace=destination;
        String editId;try(var tx=database.beginTx()){editId=null;try(var revisions=tx.findNodes(Label.label("Revision"),"namespaceId",destination)){while(revisions.hasNext()){Node candidate=revisions.next();if("edit".equals(candidate.getProperty("kind"))){editId=CoreRepository.id(candidate);break;}}}assertNotNull(editId);tx.rollback();}
        Map<String,Object> undo=invokeNamespaceProcedure("deposit.utils.undo",Json.map("revisionId",editId),destination);String undoId=(String)undo.get("revisionId");assertNotNull(undoId);
        assertClonedIntegerHistoryValues(destination,targetMassDescriptor,targetListDescriptor,targetTransitionDescriptor,5L,List.of(5L,6L),"old");
        try(var tx=database.beginTx()){assertEquals("String",tx.findNode(Label.label("ClassDescriptor"),"id",targetTransitionDeclaration).getProperty("type"));tx.rollback();}
        try(var tx=database.beginTx()){Node declarationNode=tx.findNode(Label.label("ClassDescriptor"),"id",targetDeclaration);assertNotNull(declarationNode);assertEquals(destination,declarationNode.getProperty("namespaceId"));assertEquals("Integer",declarationNode.getProperty("type"));Node clazz=tx.findNode(Label.label("Class"),"id",declarationNode.getProperty("ownerId",null));if(clazz==null){try(var classes=tx.findNodes(Label.label("Class"),"namespaceId",destination)){assertTrue(classes.hasNext());clazz=classes.next();}}assertEquals("base",clazz.getProperty("comment"));tx.rollback();}
        invokeNamespaceProcedure("deposit.utils.redo",Json.map("undoRevisionId",undoId),destination);
        assertClonedIntegerHistoryValues(destination,targetMassDescriptor,targetListDescriptor,targetTransitionDescriptor,9L,List.of(9L,10L),11L);
        try(var tx=database.beginTx()){assertEquals("Integer",tx.findNode(Label.label("ClassDescriptor"),"id",targetTransitionDeclaration).getProperty("type"));tx.rollback();}
        try(var tx=database.beginTx()){Node declarationNode=tx.findNode(Label.label("ClassDescriptor"),"id",targetDeclaration);assertNotNull(declarationNode);assertEquals("Integer",declarationNode.getProperty("type"));try(var classes=tx.findNodes(Label.label("Class"),"namespaceId",destination)){assertTrue(classes.hasNext());assertEquals("edited",classes.next().getProperty("comment"));}tx.rollback();}
        String closedTarget;try(var tx=database.beginTx()){try(var epochs=tx.findNodes(Label.label("DepositHistoryEpoch"),Map.of("namespaceId",destination,"closed",true))){assertTrue(epochs.hasNext());closedTarget=(String)epochs.next().getProperty("baselineRevisionId");}tx.rollback();}
        invokeNamespaceProcedure("deposit.utils.restore",Json.map("targetRevisionId",closedTarget),destination);
        assertClonedIntegerHistoryValues(destination,targetMassDescriptor,targetListDescriptor,targetTransitionDescriptor,5L,List.of(5L,6L),"old");
        try(var tx=database.beginTx()){assertEquals("String",tx.findNode(Label.label("ClassDescriptor"),"id",targetTransitionDeclaration).getProperty("type"));tx.rollback();}
        try(var tx=database.beginTx()){Node declarationNode=tx.findNode(Label.label("ClassDescriptor"),"id",targetDeclaration);assertNotNull(declarationNode,"restoring a closed epoch must preserve the materialized declaration identity");assertEquals("Integer",declarationNode.getProperty("type"));try(var classes=tx.findNodes(Label.label("Class"),"namespaceId",destination)){assertTrue(classes.hasNext());assertEquals("base",classes.next().getProperty("comment"));}tx.rollback();}
    }

    private void assertClonedIntegerHistoryValues(String ns,String massId,String listId,String transitionId,long mass,List<Long> series,Object transition){
        try(var tx=database.beginTx()){
            var state=new HistoryRepository(tx,new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC))).capture(ns);
            assertEquals(mass,HistoryCodec.find(state,massId,"Descriptor").get("value"));
            assertEquals(Json.map("itemType","Integer","items",series),HistoryCodec.find(state,listId,"Descriptor").get("value"));
            assertEquals(transition,HistoryCodec.find(state,transitionId,"Descriptor").get("value"));
            tx.rollback();
        }
    }

    private long serverRevision(){try(var tx=database.beginTx()){var context=new RequestContext(tx,Json.map("token",token));long revision=server.revision(context.state);tx.rollback();return revision;}}

    @Test void cloneHistoryReusesCurrentIdsAndAddsHistoryOnlyIdsToDiskBackedMappings() throws Exception {
        createNamespace("disabled");
        String sourceNamespace=UUID.randomUUID().toString(),sourceClass=UUID.randomUUID().toString();
        String historicalObject=UUID.randomUUID().toString(),historicalMembership=UUID.randomUUID().toString(),historicalDeclaration=UUID.randomUUID().toString(),sourceDescriptor=UUID.randomUUID().toString(),sourceResource=UUID.randomUUID().toString(),targetResource=UUID.randomUUID().toString(),sourceEpoch=UUID.randomUUID().toString();
        String baselineRevision=UUID.randomUUID().toString(),changeRevision=UUID.randomUUID().toString();
        String targetClass;
        try(var tx=database.beginTx()) {targetClass=CoreRepository.id(new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC)).find("Class",Map.of("namespaceId",namespace,"name","Specimen")));tx.rollback();}
        try(var baseMappings=new ImportScratch(temporary,()->{})) {
            Map<String,String> namespaceMapping=baseMappings.orderedMap();namespaceMapping.put(sourceNamespace,namespace);
            Map<String,String> classMapping=baseMappings.orderedMap();classMapping.put(sourceClass,targetClass);
            Map<String,String> objectMapping=baseMappings.orderedMap();
            String largeMappedId=UUID.randomUUID().toString();for(int i=0;i<10_000;i++)objectMapping.put(String.format("large-source-%05d",i),largeMappedId);
            AtomicInteger fullObjectMapTraversals=new AtomicInteger();Map<String,String> guardedObjectMapping=new AbstractMap<>(){
                public String get(Object key){return objectMapping.get(key);}
                public boolean containsKey(Object key){return objectMapping.containsKey(key);}
                public int size(){return objectMapping.size();}
                public Set<Entry<String,String>> entrySet(){fullObjectMapTraversals.incrementAndGet();return objectMapping.entrySet();}
            };
            Map<String,String> resourceMapping=baseMappings.orderedMap();resourceMapping.put(sourceResource,targetResource);
            Map<String,Object> identityMappings=Json.map("Namespace",namespaceMapping,"Class",classMapping,"Object",guardedObjectMapping,"DResource",resourceMapping);
            var baseline=Json.map("revisionId",baselineRevision,"namespaceId",sourceNamespace,"sequence",0L,"historyEpochId",sourceEpoch,"historyMode","persistent","operationId",UUID.randomUUID().toString(),"actorId",actor,"committedAt","2026-11-11T00:00:00Z","kind","baseline","summary","Baseline","parentRevisionId",null,"changes",List.of(),"touches",Map.of(),"dependencies",List.of());
            var image=Json.map("kind","Object","id",historicalObject,"namespaceId",sourceNamespace,"position",List.of(1.0,2.0));
            var objectChange=Json.map("entityKind","Object","entityId",historicalObject,"before",null,"after",image);
            var membershipImage=Json.map("kind","Membership","id",historicalMembership,"namespaceId",sourceNamespace,"objectId",historicalObject,"classId",sourceClass);
            var membershipChange=Json.map("entityKind","Membership","entityId",historicalMembership,"before",null,"after",membershipImage);
            var declarationImage=Json.map("kind","ClassDescriptor","id",historicalDeclaration,"namespaceId",sourceNamespace,"classId",sourceClass,"name","Historical attachment","type","DResource","order",0L,"comment","");
            var declarationChange=Json.map("entityKind","ClassDescriptor","entityId",historicalDeclaration,"before",null,"after",declarationImage);
            var descriptorImage=Json.map("kind","Descriptor","id",sourceDescriptor,"namespaceId",sourceNamespace,"objectId",historicalObject,"name","Historical attachment","value",Json.map("resourceId",sourceResource));
            var resourceChange=Json.map("entityKind","Descriptor","entityId",sourceDescriptor,"before",null,"after",descriptorImage);
            var touchKey=HistoryCodec.key("Object",historicalObject);
            var revision=Json.map("revisionId",changeRevision,"namespaceId",sourceNamespace,"sequence",1L,"historyEpochId",sourceEpoch,"historyMode","persistent","operationId",UUID.randomUUID().toString(),"actorId",actor,"committedAt","2026-11-11T00:00:01Z","kind","edit","summary","Historical object and attachment","parentRevisionId",baselineRevision,"changes",List.of(objectChange,membershipChange,declarationChange,resourceChange),"touches",Map.of(touchKey,List.of("position")),"dependencies",List.of(touchKey));
            var epoch=Json.map("id",sourceEpoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",baselineRevision,"headRevisionId",changeRevision,"revisions",List.of(baseline,revision));
            var bundle=Json.map("namespaceId",sourceNamespace,"historyMode","persistent","activeEpochId",sourceEpoch,"headRevisionId",changeRevision,"epochs",List.of(epoch));
            try(var tx=database.beginTx()) {
                var context=new RequestContext(tx,Json.map("token",token,"operationId",UUID.randomUUID().toString()));context.actorId=actor;context.namespaceId=null;
                node(tx,"DResource",Json.map("id",targetResource,"namespaceId",namespace,"filename","history-only.bin","mediaType","application/octet-stream","bytes",1L,"sha256","a".repeat(64)));
                new HistoryCloneService(context,namespace,identityMappings,resourceMapping).importHistory(bundle);
                @SuppressWarnings("unchecked") Map<String,String> returnedClasses=(Map<String,String>)identityMappings.get("Class");
                @SuppressWarnings("unchecked") Map<String,String> returnedObjects=(Map<String,String>)identityMappings.get("Object");
                @SuppressWarnings("unchecked") Map<String,String> returnedEpochs=(Map<String,String>)identityMappings.get("Epoch");
                @SuppressWarnings("unchecked") Map<String,String> returnedRevisions=(Map<String,String>)identityMappings.get("Revision");
                assertEquals(targetClass,returnedClasses.get(sourceClass));
                assertNotEquals(historicalObject,returnedObjects.get(historicalObject));
                assertEquals(10_001,returnedObjects.size());assertEquals(largeMappedId,returnedObjects.get("large-source-00000"));
                assertEquals(0,fullObjectMapTraversals.get(),"The 10,000-entry disk-backed current mapping must not be copied into a heap Map");
                assertNotNull(returnedEpochs.get(sourceEpoch));
                assertEquals(returnedRevisions.get(changeRevision),tx.findNode(Label.label("Revision"),"id",returnedRevisions.get(changeRevision)).getProperty("id"));
                Node clonedRevision=tx.findNode(Label.label("Revision"),"id",returnedRevisions.get(changeRevision));
                var touches=clonedRevision.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_TOUCH"));
                assertTrue(touches.iterator().hasNext());
                assertEquals(returnedObjects.get(historicalObject),touches.iterator().next().getEndNode().getProperty("entityId"));
                String returnedDescriptor=((Map<String,String>)identityMappings.get("Descriptor")).get(sourceDescriptor);
                Node snapshot=tx.findNode(Label.label("DepositSnapshot"),"entityId",returnedDescriptor);
                assertNotNull(snapshot);
                assertTrue(snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE")).iterator().hasNext());
                tx.commit();
            }
        }
    }

    @Test void preparedCloneAndStagingActionsReplayAfterServerRunChange() throws Exception {
        createNamespace("disabled");
        String recoveryKey=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        List<Object> arguments=List.of("clone.zip",1L,ResourceFiles.checksum(new byte[]{97}));
        apiCall(token,"deposit.utils.refresh",List.of("server"),true);
        // Prepare at a proven nonzero saved lifecycle revision to exercise the restart case.
        replaceRevision(7L);
        apiCall(token,"deposit.utils.refresh",List.of("server"),true);
        apiCall(token,"deposit.action.prepare",List.of(recoveryKey,"deposit.import.beginCloneUpload",arguments),true);
        long savedRevision;
        try(var tx=database.beginTx()) {
            savedRevision=tx.findNode(Label.label("DepositPreparedAction"),"recoveryKey",recoveryKey).getProperty("expectedRevision") instanceof Long value ? value : -1L;
            tx.rollback();
        }
        assertTrue(savedRevision>0L,"restart fixture must begin from nonzero lifecycle revision");
        replaceRunId(server);
        // A restart resets lifecycle revision to zero. Restore must not confuse
        // the revision observed before preparation with an operation cursor.
        replaceRevision(0L);
        apiCall(token,"deposit.action.resume",List.of(recoveryKey),true);
        var prepared=apiCall(token,"deposit.action.status",List.of(recoveryKey),true);
        assertEquals("prepared",prepared.get("state"));operation=(String)prepared.get("operationId");
        var started=apiCall(token,"deposit.import.beginCloneUpload",arguments,true);
        assertTrue(started.containsKey("uploadId"));
        var status=apiCall(token,"deposit.action.status",List.of(recoveryKey),false);
        assertEquals("committed",status.get("state"));assertEquals(operation,status.get("operationId"));
        var replayed=status.get("outcome");
        replaceRunId(server);
        apiCall(token,"deposit.action.resume",List.of(recoveryKey),true);
        var actionReplay=apiCall(token,"deposit.import.beginCloneUpload",arguments,true);
        assertEquals(replayed,actionReplay,"committed procedure replay must use the original recovery key and request hash");
    }

    private Map<String,Object> apiCall(String token,String procedure,List<Object> arguments,boolean commit) throws Exception {
        try(var tx=database.beginTx()) {
            var probe=new ApiProbe();probe.tx=tx;probe.log=org.neo4j.logging.NullLog.getInstance();
            var row=(Results.DataRow)probe.call(token,procedure,arguments).findFirst().orElseThrow();
            if(commit)tx.commit();else tx.rollback();
            return row.data;
        }
    }

    private static final class ApiProbe extends deposit.api.BaseAPI {
        java.util.stream.Stream<Object> call(String token,String procedure,java.util.List<Object> arguments) {
            return invokePublic(token,procedure,arguments);
        }
    }

    @Test void cloneRunIdentityGuardsOrdinaryServerScopedActionRestore() throws Exception {
        createNamespace("disabled");
        String key=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        List<Object> arguments=List.of("Fresh Namespace");
        try(var tx=database.beginTx()) {
            node(tx,"DepositPreparedAction",Json.map("actorId",actor,"recoveryKey",key,"operationId",operation,"procedure","deposit.create.namespace","scope","server","operational",false,"expectedRevision",0L,"inputHash",new SessionActionService(new RequestContext(tx,Json.map("token",token))).fingerprint("deposit.create.namespace",arguments),"bindings",Json.write(Map.of("name","Fresh Namespace")),"runId","previous-run"));tx.rollback();
        }
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token));server.session(Json.hash(token)).preparedAction=key;
            assertThrows(ExceptionHandler.DepositException.class,()->new SessionActionService(context).begin("deposit.create.namespace",arguments));tx.rollback();
        }
    }

    @Test void preparedCloneAndStagingActionsResumeAfterServerRunChange() throws Exception {
        createNamespace("disabled");
        for(String procedure:List.of("deposit.import.beginCloneUpload","deposit.import.cloneUploadChunk","deposit.import.finishCloneUpload","deposit.import.abortCloneUpload","deposit.import.cloneNamespace")) {
            String recoveryKey=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
            List<Object> arguments=switch(procedure) {
                case "deposit.import.beginCloneUpload"->List.of("clone.zip",1L,ResourceFiles.checksum(new byte[]{97}));
                case "deposit.import.cloneUploadChunk"->List.of(UUID.randomUUID().toString(),0L,"YQ==","a".repeat(64));
                case "deposit.import.cloneNamespace"->List.of(Json.map("uploadId",UUID.randomUUID().toString(),"sha256","a".repeat(64)));
                default->List.of(UUID.randomUUID().toString());
            };
            try(var tx=database.beginTx()) {
                var context=new RequestContext(tx,Json.map("token",token));
                context.state.observations.put("server",server.revision(context.state));
                new SessionActionService(context).begin(procedure,arguments);
                context.operationId=operation;
                Node prepared=node(tx,"DepositPreparedAction",Json.map("actorId",actor,"recoveryKey",recoveryKey,"operationId",operation,
                        "procedure",procedure,"scope","server","operational",true,"inputHash",new SessionActionService(context).fingerprint(procedure,arguments),
                        "bindings",Json.write(context.request),"runId","previous-server-run"));
                context.session.preparedAction=recoveryKey;
                new SessionActionService(context).begin(procedure,arguments);
                assertEquals(operation,context.operationId);assertEquals("server",context.actionScope);
                context.session.preparedAction=null;
                tx.rollback();
            }
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
