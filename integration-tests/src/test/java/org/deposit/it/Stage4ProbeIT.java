package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Installed probe controls; assertions use fixed values and independent Bolt/filesystem observations. */
@Timeout(120)
class Stage4ProbeIT {
    @BeforeAll static void start(){connect();ensureAccount();Stage4CoreIT.token=Stage3CoreIT.login();}
    static void ensureAccount(){if(query("MATCH (u:User {username:$name}) RETURN count(u) AS n",m("name",ACCOUNT)).getFirst().get("n").asInt()==0){String admin=special("deposit.utils.login",m("username",Stage2ProbeIT.USER,"password",Stage2CoreIT.PASS)).get("value").asString();call("deposit.create.user",m("username",ACCOUNT,"password",PASSWORD,"serverRole","administrator"),write(admin,revision(call("deposit.get.user",Map.of(),ctx(admin)))));}}
    @BeforeEach void fixture(){new Stage4CoreIT().fixture();}
    @AfterEach void reset(){arm("","clear");query("CALL stage0.security.clock(0)",Map.of());}
    @AfterAll static void stop(){close();}
    static void arm(String point,String mode){query("CALL stage0.storage.arm($point,$mode)",m("point",point,"mode",mode));}
    static org.neo4j.driver.Value inspect(){return query("CALL stage0.storage.inspect($ns)",m("ns",Stage4CoreIT.ns)).getFirst().get("data");}
    @Test void spatialFailuresRollbackCompleteDomainAction() {
        for(String point:List.of("geometry-layer","geometry-update","geometry-registration")) {long seq=Stage4CoreIT.seq();arm(point,"fail");error("INTERNAL_ERROR",()->Stage4CoreIT.descriptor("G","DGeometry",Stage4CoreIT.geometry("POINT Z (1 2 3)",null,3)));assertEquals(seq,Stage4CoreIT.seq());assertTrue(inspect().get("layers").asList().isEmpty());}
        Stage4CoreIT.descriptor("G","DGeometry",Stage4CoreIT.geometry("POINT Z (1 2 3)",null,3));var previous=inspect();
        for(String point:List.of("geometry-removal","geometry-update","geometry-registration")){arm(point,"fail");error("INTERNAL_ERROR",()->Stage4CoreIT.mutate("deposit.set.descriptor",m("objectId",Stage4CoreIT.object,"name","G","value",Stage4CoreIT.geometry("POINT Z (9 8 7)",4326L,3))));assertEquals(previous,inspect());}
    }
    @Test void geometryIndexBoundsIdentityRebuildAndNamespaceDeletion() {
        String token=Stage4CoreIT.token,ns=Stage4CoreIT.ns;var created=Stage4CoreIT.descriptor("G","DGeometry",Stage4CoreIT.geometry("LINESTRING Z (1 2 3,9 8 7)",4326L,3));
        var entry=inspect().get("layers").get(0).get("entries").get(0);assertEquals(List.of(1.,2.,9.,8.),entry.get("bbox").asList());String id=created.get("data").get("geometryId").asString();assertEquals(id,entry.get("id").asString());
        query("CALL stage0.storage.rebuild($ns)",m("ns",ns));assertEquals(id,inspect().get("layers").get(0).get("entries").get(0).get("id").asString());
        for(int i=0;i<3;i++){Stage4CoreIT.mutate("deposit.set.descriptor",m("objectId",Stage4CoreIT.object,"name","G","value",Stage4CoreIT.geometry("POINT Z (20 30 40)",999999999L,3)));assertEquals(1,inspect().get("layers").asList(v->v.get("count").asLong()).stream().mapToLong(x->x).sum());}
        var namespace=call("deposit.get.activeNamespace",Map.of(),ctx(token));call("deposit.delete.namespace",m("confirmNamespaceName",namespace.get("data").get("name").asString(),"expectedNamespaceRevision",Stage4CoreIT.seq()),write(token,revision(call("deposit.get.user",Map.of(),ctx(token)))));assertTrue(inspect().get("layers").asList().isEmpty());
    }
    @Test void expiryResumptionNonRenewalAndReadyLeasePlacement()throws Exception {
        var context=Stage4CoreIT.op();var metadata=Stage4CoreIT.meta(Stage4CoreIT.ABC,"expiry");String upload=call("deposit.resource.beginUpload",metadata,context).get("data").get("uploadId").asString();String originalNs=Stage4CoreIT.ns;
        query("CALL stage0.security.clock(82800000)",Map.of());Stage4CoreIT.token=Stage3CoreIT.login();Stage3CoreIT.select(Stage4CoreIT.token,originalNs);context.put("token",Stage4CoreIT.token);call("deposit.resource.beginUpload",metadata,context);
        long renewed=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.expires AS expiry",m("id",upload)).getFirst().get("expiry").asLong();
        error("VALIDATION_FAILED",()->call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,1,Stage4CoreIT.ABC),Stage4CoreIT.op()));assertEquals(renewed,query("MATCH (u:DepositTransfer {id:$id}) RETURN u.expires AS expiry",m("id",upload)).getFirst().get("expiry").asLong());
        query("CALL stage0.security.clock(172800100)",Map.of());Stage4CoreIT.token=Stage3CoreIT.login();Stage3CoreIT.select(Stage4CoreIT.token,originalNs);context.put("token",Stage4CoreIT.token);error("NOT_FOUND",()->call("deposit.resource.beginUpload",metadata,context));
        query("CALL stage0.security.clock(0)",Map.of());Stage4CoreIT.token=Stage3CoreIT.login();Stage3CoreIT.select(Stage4CoreIT.token,originalNs);
        String resource=Stage4CoreIT.upload(Stage4CoreIT.ABC,"ready");query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0",m("id",resource));Stage4CoreIT.awaitPath(resource,"deleted");
    }
    @Test void exportGrantRemainsPrivateAndRechecksCurrentRole() {
        String token=Stage4CoreIT.token,namespace=Stage4CoreIT.ns,resource=Stage4CoreIT.upload(Stage4CoreIT.ABC,"grant"),username="grant-"+UUID.randomUUID();
        String user=id(call("deposit.create.user",m("username",username,"password",PASSWORD,"serverRole","user"),write(token,revision(call("deposit.get.user",Map.of(),ctx(token))))));Stage4CoreIT.mutate("deposit.set.namespaceRole",m("userId",user,"role","viewer"));
        String recipient=special("deposit.utils.login",m("username",username,"password",PASSWORD)).get("value").asString();Stage3CoreIT.select(recipient,namespace);error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",resource),ctx(recipient)));
        query("CALL stage0.storage.grant($ns,$r,$actor,false)",m("ns",namespace,"r",resource,"actor",user));assertEquals(resource,call("deposit.resource.info",m("resourceId",resource),ctx(recipient)).get("data").get("resourceId").asString());
        Stage4CoreIT.mutate("deposit.set.namespaceRole",m("userId",user,"role",null));error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",resource),ctx(recipient)));
    }
    @Test void publicationFaultsRecoverWithoutRepeatingEffects() {
        for(String point:List.of("chunk-flushed","publication-before","publication-after","ready-before-commit")) {
            String upload=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,point),Stage4CoreIT.op()).get("data").get("uploadId").asString();
            var chunkContext=Stage4CoreIT.op();var finishContext=Stage4CoreIT.op();
            if(point.equals("chunk-flushed")){arm(point,"fail");error("INTERNAL_ERROR",()->call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,Stage4CoreIT.ABC),chunkContext));}
            call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,Stage4CoreIT.ABC),chunkContext);
            if(!point.equals("chunk-flushed")){arm(point,"fail");error("INTERNAL_ERROR",()->call("deposit.resource.finishUpload",m("uploadId",upload),finishContext));}
            var result=call("deposit.resource.finishUpload",m("uploadId",upload),finishContext).get("data");assertEquals(result,call("deposit.resource.finishUpload",m("uploadId",upload),finishContext).get("data"));
        }
    }
    @Test void readLeaseBlocksRelocationUntilTransactionCompletes()throws Exception {
        String resource=Stage4CoreIT.upload(Stage4CoreIT.ABC,"race");Stage4CoreIT.descriptor("F","DResource",m("resourceId",resource));long revision=Stage4CoreIT.seq();String other=Stage3CoreIT.login();Stage3CoreIT.select(other,Stage4CoreIT.ns);
        try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.resource.downloadChunk($r,$c)",m("r",m("resourceId",resource,"offset",0L,"length",3L),"c",ctx(Stage4CoreIT.token))).consume();
            var entered=new CountDownLatch(1);var pending=pool.submit(()->{entered.countDown();return call("deposit.set.descriptor",m("objectId",Stage4CoreIT.object,"name","F","value",null),write(other,revision));});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(200,TimeUnit.MILLISECONDS));tx.commit();pending.get(10,TimeUnit.SECONDS);
        }Stage4CoreIT.awaitPath(resource,"deleted");
    }
}
