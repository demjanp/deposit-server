package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(120)
class HistoryFailureProbeIT {
    @BeforeAll static void start(){connect();ResourceFailureProbeIT.ensureAccount();RevisionHistoryIT.token=BatchMutationIT.login();}
    @AfterAll static void stop(){close();}
    @BeforeEach void fixture(){RevisionHistoryIT.fixture5();}
    @AfterEach void reset(){ResourceFailureProbeIT.arm("","clear");query("CALL testprobe.security.clock(0)",Map.of());}
    @Test void historyFailureRollsBackDomainRetentionSequenceAndReceipt(){String token=RevisionHistoryIT.token;long seq=RevisionHistoryIT.seq(),records=RevisionHistoryIT.count("Revision");var context=write(token,seq);ResourceFailureProbeIT.arm("history-before-receipt","fail");error("INTERNAL_ERROR",()->call("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","Mass","value",20L),context));assertEquals(10L,RevisionHistoryIT.value());assertEquals(seq,RevisionHistoryIT.seq());assertEquals(records,RevisionHistoryIT.count("Revision"));assertEquals(0,query("MATCH (r:DepositReceipt {operationId:$op}) RETURN count(r) AS n",m("op",context.get("operationId"))).getFirst().get("n").asInt());}
    @Test void expiryBeforeCommitAndCancellationRollbackHistory()throws Exception {String token=RevisionHistoryIT.token;long before=RevisionHistoryIT.seq();try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.set.descriptor($r,$c)",m("r",m("objectId",RevisionHistoryIT.object,"name","Mass","value",20L),"c",write(token,before))).consume();query("CALL testprobe.security.clock(1800001)",Map.of());assertThrows(org.neo4j.driver.exceptions.ClientException.class,tx::commit);}query("CALL testprobe.security.clock(0)",Map.of());RevisionHistoryIT.token=BatchMutationIT.login();BatchMutationIT.select(RevisionHistoryIT.token,RevisionHistoryIT.ns);assertEquals(before,RevisionHistoryIT.seq());assertEquals(10L,RevisionHistoryIT.value());try(var session=driver.session();var tx=session.beginTransaction()){var context=write(RevisionHistoryIT.token,before);tx.run("CALL deposit.set.descriptor($r,$c)",m("r",m("objectId",RevisionHistoryIT.object,"name","Mass","value",30L),"c",context)).consume();call("deposit.utils.cancel",m("requestId",context.get("requestId")),ctx(RevisionHistoryIT.token));assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);}assertEquals(before,RevisionHistoryIT.seq());}
    @Test void geometryReversalFaultsAndSingleLiveIndex(){RevisionHistoryIT.resource();var g=ResourceAndGeometryIT.descriptor("G","DGeometry",ResourceAndGeometryIT.geometry("POINT Z (1 2 3)",4326L,3));String gid=g.get("data").get("geometryId").asString();String changed=RevisionHistoryIT.rid(RevisionHistoryIT.change("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","G","value",ResourceAndGeometryIT.geometry("POINT Z (9 8 7)",999999L,3))));for(String point:List.of("geometry-removal","geometry-update","geometry-registration")){var old=ResourceFailureProbeIT.inspect();long seq=RevisionHistoryIT.seq();ResourceFailureProbeIT.arm(point,"fail");error("INTERNAL_ERROR",()->RevisionHistoryIT.undo(changed));assertEquals(seq,RevisionHistoryIT.seq());assertEquals(old,ResourceFailureProbeIT.inspect());}ResourceFailureProbeIT.arm("","clear");RevisionHistoryIT.undo(changed);var layers=ResourceFailureProbeIT.inspect().get("layers").asList(v->v);assertEquals(1L,layers.stream().mapToLong(v->v.get("count").asLong()).sum());var entries=layers.stream().flatMap(v->v.get("entries").asList(x->x).stream()).toList();assertEquals(gid,entries.getFirst().get("id").asString());assertEquals(List.of(1.,2.,1.,2.),entries.getFirst().get("bbox").asList());assertEquals(0,query("MATCH (s:DepositSnapshot)-[:RTREE_REFERENCE]->() RETURN count(s) AS n",Map.of()).getFirst().get("n").asInt());}
    @Test void corruptHistoricalCategoryAndMissingSchemaRejectWithoutWrites(){String target=RevisionHistoryIT.head(),token=RevisionHistoryIT.token;long seq=RevisionHistoryIT.seq();query("MATCH (r:Revision {id:$id})-[:HAS_CHANGE]->(s:DepositSnapshot {entityKind:'Object'}) SET s.entityKind='Class'",m("id",target));error("REVISION_CONFLICT",()->call("deposit.get.restorePreview",m("targetRevisionId",target),ctx(token)));error("REVISION_CONFLICT",()->RevisionHistoryIT.change("deposit.utils.restore",m("targetRevisionId",target)));assertEquals(seq,RevisionHistoryIT.seq());query("MATCH (r:Revision {id:$id})-[:HAS_CHANGE]->(s:DepositSnapshot) WHERE s.afterJson CONTAINS '\"kind\":\"Object\"' SET s.entityKind='Object'",m("id",target));var damaged=query("MATCH (s:DepositSnapshot {namespaceId:$ns,entityKind:'ClassDescriptor'}) RETURN s.id AS id,s.afterJson AS image",m("ns",RevisionHistoryIT.ns)).getFirst();query("MATCH (s:DepositSnapshot {id:$id}) SET s.afterJson=replace(s.afterJson,$class,'missing-class')",m("id",damaged.get("id").asString(),"class",RevisionHistoryIT.clazz));error("REVISION_CONFLICT",()->call("deposit.get.restorePreview",m("targetRevisionId",target),ctx(token)));assertEquals(seq,RevisionHistoryIT.seq());query("MATCH (s:DepositSnapshot {id:$id}) SET s.afterJson=$image",m("id",damaged.get("id").asString(),"image",damaged.get("image").asString()));}
    @Test void historyOnlyEditorReattachmentAndRevocation(){RevisionHistoryIT.resource();String resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"editor-history");String editor=RevisionHistoryIT.actor("editor");call("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","File","value",m("type","DResource","value",m("resourceId",resource))),write(RevisionHistoryIT.token,RevisionHistoryIT.seq()));String removal=RevisionHistoryIT.rid(call("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","File","value",null),write(editor,RevisionHistoryIT.seq())));query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0",m("id",resource));assertEquals("YWJj",call("deposit.resource.downloadChunk",m("resourceId",resource,"offset",0L,"length",3L),ctx(editor)).get("data").get("bytesBase64").asString());call("deposit.utils.undo",m("revisionId",removal),write(editor,RevisionHistoryIT.seq()));String actor=query("MATCH (r:Revision {id:$id}) RETURN r.actorId AS id",m("id",removal)).getFirst().get("id").asString();RevisionHistoryIT.change("deposit.set.namespaceRole",m("userId",actor,"role",null));error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",resource),ctx(editor)));}
    @Test void historyReadRejectsConcurrentSequenceChange()throws Exception {String token=RevisionHistoryIT.token;ResourceFailureProbeIT.arm("history-read-row","pause");try(var pool=Executors.newSingleThreadExecutor()){var read=pool.submit(()->query("CALL deposit.get.history({},$c)",m("c",ctx(token))));try{var marker=ROOT.resolve("resource-pause.json");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!java.nio.file.Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(20);assertTrue(java.nio.file.Files.exists(marker));RevisionHistoryIT.mass(100);}finally{ResourceFailureProbeIT.arm("","clear");}var failure=assertThrows(ExecutionException.class,()->read.get(15,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("REVISION_CONFLICT"));}finally{ResourceFailureProbeIT.arm("","clear");}}
    @Test void historyReadRejectsGenerationOnlyChange()throws Exception {
        String token=RevisionHistoryIT.token;long sequence=RevisionHistoryIT.seq();
        long generation=query("MATCH (n:Namespace {id:$ns}) RETURN coalesce(n.historyAvailability,0) AS generation",m("ns",RevisionHistoryIT.ns)).getFirst().get("generation").asLong();
        ResourceFailureProbeIT.arm("history-read-row","pause");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var read=pool.submit(()->query("CALL deposit.get.history({},$c)",m("c",ctx(token))));
            try {
                var marker=ROOT.resolve("resource-pause.json");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!java.nio.file.Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(20);
                assertTrue(java.nio.file.Files.exists(marker),"History stream did not reach its row stability check");
                query("MATCH (n:Namespace {id:$ns}) SET n.historyAvailability=$generation",m("ns",RevisionHistoryIT.ns,"generation",generation+1));
                assertEquals(sequence,RevisionHistoryIT.seq(),"Generation-only invalidation must not advance Namespace sequence");
            } finally {
                ResourceFailureProbeIT.arm("","clear");
            }
            var failure=assertThrows(ExecutionException.class,()->read.get(15,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("REVISION_CONFLICT"),failure.getCause().getMessage());
            assertEquals(sequence,RevisionHistoryIT.seq());
            assertEquals(generation+1,query("MATCH (n:Namespace {id:$ns}) RETURN n.historyAvailability AS generation",m("ns",RevisionHistoryIT.ns)).getFirst().get("generation").asLong());
        } finally {ResourceFailureProbeIT.arm("","clear");}
    }
    @Test void historyReadRequiresViewerWhenGenerationAlsoChanged() {
        String username="history-read-viewer-"+UUID.randomUUID();
        String userId=id(call("deposit.create.user",m("username",username,"password",AccountAuthorizationIT.PASS),write(RevisionHistoryIT.token,RevisionHistoryIT.seq())));
        RevisionHistoryIT.change("deposit.set.namespaceRole",m("userId",userId,"role","viewer"));
        String viewer=AccountAuthorizationIT.login(username,AccountAuthorizationIT.PASS);BatchMutationIT.select(viewer,RevisionHistoryIT.ns);
        long generation=query("MATCH (n:Namespace {id:$ns}) RETURN coalesce(n.historyAvailability,0) AS generation",m("ns",RevisionHistoryIT.ns)).getFirst().get("generation").asLong();
        query("MATCH (u:User {id:$user})-[r:HAS_NAMESPACE_ROLE]->(n:Namespace {id:$ns}) DELETE r",m("user",userId,"ns",RevisionHistoryIT.ns));
        query("MATCH (n:Namespace {id:$ns}) SET n.historyAvailability=$generation",m("ns",RevisionHistoryIT.ns,"generation",generation+1));
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.get.history",Map.of(),ctx(viewer)));
        assertTrue(failure.getMessage().contains("NOT_FOUND"),failure.getMessage());
        assertFalse(failure.getMessage().contains("REVISION_CONFLICT"),failure.getMessage());
    }
    @Test void historyReadRejectsRevokedAuthenticationBeforeGenerationConflict()throws Exception {
        String token=RevisionHistoryIT.token;long sequence=RevisionHistoryIT.seq();long generation=query("MATCH (n:Namespace {id:$ns}) RETURN coalesce(n.historyAvailability,0) AS generation",m("ns",RevisionHistoryIT.ns)).getFirst().get("generation").asLong();
        ResourceFailureProbeIT.arm("history-read-row","pause");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var read=pool.submit(()->query("CALL deposit.get.history({},$c)",m("c",ctx(token))));
            try {
                var marker=ROOT.resolve("resource-pause.json");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!java.nio.file.Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(20);
                assertTrue(java.nio.file.Files.exists(marker),"History stream did not reach its row stability check");
                call("deposit.utils.logout",Map.of(),ctx(token));
                query("MATCH (n:Namespace {id:$ns}) SET n.historyAvailability=$generation",m("ns",RevisionHistoryIT.ns,"generation",generation+1));
            } finally {ResourceFailureProbeIT.arm("","clear");}
            var failure=assertThrows(ExecutionException.class,()->read.get(15,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("AUTH_FAILED"),failure.getCause().getMessage());
            assertFalse(failure.getCause().getMessage().contains("REVISION_CONFLICT"),failure.getCause().getMessage());
            assertEquals(sequence,query("MATCH (n:Namespace {id:$ns}) RETURN n.sequence AS sequence",m("ns",RevisionHistoryIT.ns)).getFirst().get("sequence").asLong());
        } finally {
            ResourceFailureProbeIT.arm("","clear");
            RevisionHistoryIT.token=BatchMutationIT.login();BatchMutationIT.select(RevisionHistoryIT.token,RevisionHistoryIT.ns);
        }
    }
    @Test void historyReadEnforcesTransactionTimeoutWhileStreaming()throws Exception {
        String token=RevisionHistoryIT.token;ResourceFailureProbeIT.arm("history-read-row","pause");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var read=pool.submit(()->{try(var session=driver.session()){return session.run("CYPHER 25 CALL deposit.get.history($r,$c)",m("r",Map.of(),"c",ctx(token))).list();}});
            try {
                var marker=ROOT.resolve("resource-pause.json");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!java.nio.file.Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(20);
                assertTrue(java.nio.file.Files.exists(marker),"History stream did not reach its row stability check");
                Thread.sleep(31_000);
            } finally {ResourceFailureProbeIT.arm("","clear");}
            var failure=assertThrows(ExecutionException.class,()->read.get(15,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("CANCELLED"),failure.getCause().getMessage());
        } finally {ResourceFailureProbeIT.arm("","clear");}
    }
    @Test void historyReadSerializesReattachmentAndCollection()throws Exception {RevisionHistoryIT.resource();String resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"race-history");ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",resource));String removal=RevisionHistoryIT.rid(RevisionHistoryIT.change("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","F","value",null)));ResourceAndGeometryIT.assertStored(resource);query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",resource));try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()){assertEquals("YWJj",tx.run("CALL deposit.resource.downloadChunk($r,$c)",m("r",m("resourceId",resource,"offset",0L,"length",3L),"c",ctx(RevisionHistoryIT.token))).single().get("data").get("bytesBase64").asString());var entered=new CountDownLatch(1);var restore=pool.submit(()->{entered.countDown();return RevisionHistoryIT.undo(removal);});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->restore.get(250,TimeUnit.MILLISECONDS));tx.commit();assertNotNull(restore.get(20,TimeUnit.SECONDS));}ResourceAndGeometryIT.assertStored(resource);assertFalse(call("deposit.admin.pruneResources",m("minimumAgeHours",0L),ctx(RevisionHistoryIT.token)).get("data").get("candidateIds").asList().contains(resource));assertArrayEquals(ResourceAndGeometryIT.ABC,java.nio.file.Files.readAllBytes(ResourceAndGeometryIT.resourcePath(resource)));}
    @Test void corruptionBeforeRetentionFailsWholeAction()throws Exception {RevisionHistoryIT.resource();String resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"corrupt-retention");ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",resource));var path=ResourceAndGeometryIT.resourcePath(resource);byte[] bytes=java.nio.file.Files.readAllBytes(path);long sequence=RevisionHistoryIT.seq(),records=RevisionHistoryIT.count("Revision");try{java.nio.file.Files.write(path,new byte[]{0,0,0});error("RESOURCE_CORRUPT",()->RevisionHistoryIT.change("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","F","value",null)));assertEquals(sequence,RevisionHistoryIT.seq());assertEquals(records,RevisionHistoryIT.count("Revision"));assertEquals(resource,call("deposit.get.descriptor",m("objectId",RevisionHistoryIT.object,"name","F"),ctx(RevisionHistoryIT.token)).get("data").get("value").get("resourceId").asString());}finally{java.nio.file.Files.write(path,bytes);}}
}
