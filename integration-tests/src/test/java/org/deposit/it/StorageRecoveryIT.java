package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

@Timeout(120)
class StorageRecoveryIT {
    static final Path FIXTURE=ROOT.resolve("storage-recovery.properties"),TOKEN=ROOT.resolve("secrets/storage-session.txt");
    @Test void seed()throws Exception {
        connect();try{
            RevisionHistoryIT.token=BatchMutationIT.login();RevisionHistoryIT.fixture5();RevisionHistoryIT.resource();
            var p=new Properties();p.setProperty("namespace",RevisionHistoryIT.ns);
            String retained=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"restart-history.txt");ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",retained));
            RevisionHistoryIT.change("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","F","value",null));ResourceAndGeometryIT.awaitStagingCleanup(retained);p.setProperty("retained",retained);
            var begin=ResourceAndGeometryIT.op();String upload=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,"restart-partial"),begin).get("data").get("uploadId").asString();
            call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,new byte[]{97}),ResourceAndGeometryIT.op());p.setProperty("upload",upload);p.setProperty("begin",(String)begin.get("operationId"));
            new ResourceAndGeometryIT().fixture();String unknown=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"unknown-object");ResourceAndGeometryIT.awaitStagingCleanup(unknown);p.setProperty("unknown",unknown);
            query("MATCH (n) WHERE (n:DepositResourceLocation OR n:DResource) AND n.id=$id DETACH DELETE n",m("id",unknown));query("MATCH (n:DepositTransfer {resourceId:$id}) DELETE n",m("id",unknown));
            Files.writeString(ROOT.resolve("exports/storage-incomplete.export"),"pending temporary output");Files.writeString(TOKEN,ResourceAndGeometryIT.token);
            try(var out=Files.newOutputStream(FIXTURE)){p.store(out,"storage restart fixture; no session token");}
        }finally{close();}
    }
    @Test void verify()throws Exception {
        connect();try{
            var p=new Properties();try(var in=Files.newInputStream(FIXTURE)){p.load(in);}error("AUTH_FAILED",()->call("deposit.get.activeNamespace",Map.of(),ctx(readToken())));
            String token=BatchMutationIT.login();ResourceAndGeometryIT.token=token;BatchMutationIT.select(token,p.getProperty("namespace"));
            var begin=ctx(token);begin.put("operationId",p.getProperty("begin"));var resumed=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,"restart-partial"),begin).get("data");assertEquals(p.getProperty("upload"),resumed.get("uploadId").asString());assertEquals(1,resumed.get("nextOffset").asLong());
            String retained=p.getProperty("retained");assertEquals("YWJj",call("deposit.resource.downloadChunk",m("resourceId",retained,"offset",0L,"length",3L),ctx(token)).get("data").get("bytesBase64").asString());ResourceAndGeometryIT.assertStored(retained);
            String unknown=p.getProperty("unknown");var loc=query("MATCH (l:DepositResourceLocation {id:$id}) RETURN properties(l) AS p",m("id",unknown)).getFirst().get("p");
            assertEquals("00000000-0000-0000-0000-000000000000",loc.get("namespaceId").asString());assertTrue(loc.get("orphanSince").asLong()>0);assertEquals(0,query("MATCH (:DResource {id:$id}) RETURN count(*) AS n",m("id",unknown)).getFirst().get("n").asLong());
            error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",unknown),ctx(token)));assertFalse(ImmutableResourceStorageIT.candidates().contains(unknown));
            assertTrue(call("deposit.admin.pruneResources",m("scope","all","minimumAgeHours",0L),ctx(token)).get("data").get("candidateIds").asList().contains(unknown));
            assertFalse(call("deposit.admin.pruneResources",m("scope","all"),ctx(token)).get("data").get("candidateIds").asList().contains(unknown));
            assertEquals("pending temporary output",Files.readString(ROOT.resolve("exports/storage-incomplete.export")));
            try(var work=Files.list(ROOT.resolve("resources/ocfl-work"))){assertEquals(0,work.count());}
        }finally{close();}
    }
    private static String readToken(){try{return Files.readString(TOKEN);}catch(Exception e){throw new RuntimeException(e);}}
}
