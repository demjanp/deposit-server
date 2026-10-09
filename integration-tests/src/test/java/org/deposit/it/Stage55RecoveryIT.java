package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

@Timeout(120)
class Stage55RecoveryIT {
    static final Path FIXTURE=ROOT.resolve("stage55-recovery.properties"),TOKEN=ROOT.resolve("secrets/stage55-session.txt");
    @Test void seed()throws Exception {
        connect();try{
            Stage5CoreIT.token=Stage3CoreIT.login();Stage5CoreIT.fixture5();Stage5CoreIT.stage4();
            var p=new Properties();p.setProperty("namespace",Stage5CoreIT.ns);
            String retained=Stage4CoreIT.upload(Stage4CoreIT.ABC,"backup-history.txt");Stage4CoreIT.descriptor("F","DResource",m("resourceId",retained));
            Stage5CoreIT.change("deposit.set.descriptor",m("objectId",Stage5CoreIT.object,"name","F","value",null));Stage4CoreIT.awaitStagingCleanup(retained);p.setProperty("retained",retained);
            query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0 CREATE (:DepositResourceLease {id:$lease,resourceId:$id,type:'backup',expires:$expires})",m("id",retained,"lease",retained+"/backup","expires",System.currentTimeMillis()+86400000));
            var begin=Stage4CoreIT.op();String upload=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,"backup-partial"),begin).get("data").get("uploadId").asString();
            call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,new byte[]{97}),Stage4CoreIT.op());p.setProperty("upload",upload);p.setProperty("begin",(String)begin.get("operationId"));
            new Stage4CoreIT().fixture();String unknown=Stage4CoreIT.upload(Stage4CoreIT.ABC,"unknown-object");Stage4CoreIT.awaitStagingCleanup(unknown);p.setProperty("unknown",unknown);
            query("MATCH (n) WHERE (n:DepositResourceLocation OR n:DResource) AND n.id=$id DETACH DELETE n",m("id",unknown));query("MATCH (n:DepositTransfer {resourceId:$id}) DELETE n",m("id",unknown));
            Files.writeString(ROOT.resolve("exports/stage55-incomplete.export"),"pending temporary output");Files.writeString(TOKEN,Stage4CoreIT.token);
            try(var out=Files.newOutputStream(FIXTURE)){p.store(out,"Stage 5.5 restart/backup fixture; no session token");}
        }finally{close();}
    }
    @Test void verify()throws Exception {
        connect();try{
            var p=new Properties();try(var in=Files.newInputStream(FIXTURE)){p.load(in);}error("AUTH_FAILED",()->call("deposit.get.activeNamespace",Map.of(),ctx(readToken())));
            String token=Stage3CoreIT.login();Stage4CoreIT.token=token;Stage3CoreIT.select(token,p.getProperty("namespace"));
            var begin=ctx(token);begin.put("operationId",p.getProperty("begin"));var resumed=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,"backup-partial"),begin).get("data");assertEquals(p.getProperty("upload"),resumed.get("uploadId").asString());assertEquals(1,resumed.get("nextOffset").asLong());
            String retained=p.getProperty("retained");assertEquals("YWJj",call("deposit.resource.downloadChunk",m("resourceId",retained,"offset",0L,"length",3L),ctx(token)).get("data").get("bytesBase64").asString());Stage4CoreIT.assertStored(retained);
            assertEquals(1,query("MATCH (:DepositResourceLease {id:$id,type:'backup'}) RETURN count(*) AS n",m("id",retained+"/backup")).getFirst().get("n").asLong());
            String unknown=p.getProperty("unknown");var loc=query("MATCH (l:DepositResourceLocation {id:$id}) RETURN properties(l) AS p",m("id",unknown)).getFirst().get("p");
            assertEquals("00000000-0000-0000-0000-000000000000",loc.get("namespaceId").asString());assertTrue(loc.get("orphanSince").asLong()>0);assertEquals(0,query("MATCH (:DResource {id:$id}) RETURN count(*) AS n",m("id",unknown)).getFirst().get("n").asLong());
            error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",unknown),ctx(token)));assertFalse(Stage55CoreIT.candidates().contains(unknown));
            assertTrue(call("deposit.admin.pruneResources",m("scope","all","minimumAgeHours",0L),ctx(token)).get("data").get("candidateIds").asList().contains(unknown));
            assertFalse(call("deposit.admin.pruneResources",m("scope","all"),ctx(token)).get("data").get("candidateIds").asList().contains(unknown));
            assertEquals("pending temporary output",Files.readString(ROOT.resolve("exports/stage55-incomplete.export")));
            try(var work=Files.list(ROOT.resolve("resources/ocfl-work"))){assertEquals(0,work.count());}
        }finally{close();}
    }
    @Test void mutateAfterBackup()throws Exception {
        connect();try{
            var p=new Properties();try(var in=Files.newInputStream(FIXTURE)){p.load(in);}Stage4CoreIT.token=Stage3CoreIT.login();Stage3CoreIT.select(Stage4CoreIT.token,p.getProperty("namespace"));
            call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(p.getProperty("upload"),1,new byte[]{98,99}),Stage4CoreIT.op());
            call("deposit.resource.finishUpload",m("uploadId",p.getProperty("upload")),Stage4CoreIT.op());
            Stage4CoreIT.mutate("deposit.set.versioningType",m("mode","disabled"));
            query("MATCH (l:DepositResourceLease {id:$id}) DELETE l",m("id",p.getProperty("retained")+"/backup"));
            assertEquals("finished",query("MATCH (t:DepositTransfer {id:$id}) RETURN t.state AS state",m("id",p.getProperty("upload"))).getFirst().get("state").asString());
        }finally{close();}
    }
    private static String readToken(){try{return Files.readString(TOKEN);}catch(Exception e){throw new RuntimeException(e);}}
}
