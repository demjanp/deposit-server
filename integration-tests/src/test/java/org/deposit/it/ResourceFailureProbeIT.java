package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Installed probe controls; assertions use fixed values and independent Bolt/filesystem observations. */
@Timeout(120)
class ResourceFailureProbeIT {
    @BeforeAll static void start(){connect();ensureAccount();ResourceAndGeometryIT.token=BatchMutationIT.login();}
    static void ensureAccount(){if(query("MATCH (u:User {username:$name}) RETURN count(u) AS n",m("name",ACCOUNT)).getFirst().get("n").asInt()==0){String admin=special("deposit.utils.login",m("username",AuthenticationFailureProbeIT.USER,"password",AccountAuthorizationIT.PASS)).get("value").asString();call("deposit.create.user",m("username",ACCOUNT,"password",PASSWORD,"serverRole","administrator"),write(admin,revision(call("deposit.get.user",Map.of(),ctx(admin)))));}}
    @BeforeEach void fixture(){new ResourceAndGeometryIT().fixture();}
    @AfterEach void reset(){arm("","clear");query("CALL testprobe.security.clock(0)",Map.of());}
    @AfterAll static void stop(){close();}
    static void arm(String point,String mode){query("CALL testprobe.storage.arm($point,$mode)",m("point",point,"mode",mode));}
    static org.neo4j.driver.Value inspect(){return query("CALL testprobe.storage.inspect($ns)",m("ns",ResourceAndGeometryIT.ns)).getFirst().get("data");}
    @Test void spatialFailuresRollbackCompleteDomainAction() {
        for(String point:List.of("geometry-layer","geometry-update","geometry-registration")) {long seq=ResourceAndGeometryIT.seq();arm(point,"fail");error("INTERNAL_ERROR",()->ResourceAndGeometryIT.descriptor("G","DGeometry",ResourceAndGeometryIT.geometry("POINT Z (1 2 3)",null,3)));assertEquals(seq,ResourceAndGeometryIT.seq());assertTrue(inspect().get("layers").asList().isEmpty());}
        ResourceAndGeometryIT.descriptor("G","DGeometry",ResourceAndGeometryIT.geometry("POINT Z (1 2 3)",null,3));var previous=inspect();
        for(String point:List.of("geometry-removal","geometry-update","geometry-registration")){arm(point,"fail");error("INTERNAL_ERROR",()->ResourceAndGeometryIT.mutate("deposit.set.descriptor",m("objectId",ResourceAndGeometryIT.object,"name","G","value",ResourceAndGeometryIT.geometry("POINT Z (9 8 7)",4326L,3))));assertEquals(previous,inspect());}
    }
    @Test void geometryIndexBoundsIdentityRebuildAndNamespaceDeletion() {
        String token=ResourceAndGeometryIT.token,ns=ResourceAndGeometryIT.ns;var created=ResourceAndGeometryIT.descriptor("G","DGeometry",ResourceAndGeometryIT.geometry("LINESTRING Z (1 2 3,9 8 7)",4326L,3));
        var entry=inspect().get("layers").get(0).get("entries").get(0);assertEquals(List.of(1.,2.,9.,8.),entry.get("bbox").asList());String id=created.get("data").get("geometryId").asString();assertEquals(id,entry.get("id").asString());
        query("CALL testprobe.storage.rebuild($ns)",m("ns",ns));assertEquals(id,inspect().get("layers").get(0).get("entries").get(0).get("id").asString());
        for(int i=0;i<3;i++){ResourceAndGeometryIT.mutate("deposit.set.descriptor",m("objectId",ResourceAndGeometryIT.object,"name","G","value",ResourceAndGeometryIT.geometry("POINT Z (20 30 40)",999999999L,3)));assertEquals(1,inspect().get("layers").asList(v->v.get("count").asLong()).stream().mapToLong(x->x).sum());}
        var namespace=call("deposit.get.activeNamespace",Map.of(),ctx(token));call("deposit.delete.namespace",m("confirmNamespaceName",namespace.get("data").get("name").asString(),"expectedNamespaceRevision",ResourceAndGeometryIT.seq()),write(token,revision(call("deposit.get.user",Map.of(),ctx(token)))));assertTrue(inspect().get("layers").asList().isEmpty());
    }
    @Test void expiryResumptionNonRenewalAndReadyLeaseRetention()throws Exception {
        var context=ResourceAndGeometryIT.op();var metadata=ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,"expiry");String upload=call("deposit.resource.beginUpload",metadata,context).get("data").get("uploadId").asString();String originalNs=ResourceAndGeometryIT.ns;
        query("CALL testprobe.security.clock(82800000)",Map.of());ResourceAndGeometryIT.token=BatchMutationIT.login();BatchMutationIT.select(ResourceAndGeometryIT.token,originalNs);context.put("token",ResourceAndGeometryIT.token);call("deposit.resource.beginUpload",metadata,context);
        long renewed=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.expires AS expiry",m("id",upload)).getFirst().get("expiry").asLong();
        error("VALIDATION_FAILED",()->call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,1,ResourceAndGeometryIT.ABC),ResourceAndGeometryIT.op()));assertEquals(renewed,query("MATCH (u:DepositTransfer {id:$id}) RETURN u.expires AS expiry",m("id",upload)).getFirst().get("expiry").asLong());
        query("CALL testprobe.security.clock(172800100)",Map.of());ResourceAndGeometryIT.token=BatchMutationIT.login();BatchMutationIT.select(ResourceAndGeometryIT.token,originalNs);context.put("token",ResourceAndGeometryIT.token);error("NOT_FOUND",()->call("deposit.resource.beginUpload",metadata,context));
        query("CALL testprobe.security.clock(0)",Map.of());ResourceAndGeometryIT.token=BatchMutationIT.login();BatchMutationIT.select(ResourceAndGeometryIT.token,originalNs);
        String resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"ready");query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0",m("id",resource));ResourceAndGeometryIT.assertStored(resource);
    }
    @Test void exportGrantRemainsPrivateAndRechecksCurrentRole() {
        String token=ResourceAndGeometryIT.token,namespace=ResourceAndGeometryIT.ns,resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"grant"),username="grant-"+UUID.randomUUID();
        String user=id(call("deposit.create.user",m("username",username,"password",PASSWORD,"serverRole","user"),write(token,revision(call("deposit.get.user",Map.of(),ctx(token))))));ResourceAndGeometryIT.mutate("deposit.set.namespaceRole",m("userId",user,"role","viewer"));
        String recipient=special("deposit.utils.login",m("username",username,"password",PASSWORD)).get("value").asString();BatchMutationIT.select(recipient,namespace);error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",resource),ctx(recipient)));
        query("CALL testprobe.storage.grant($ns,$r,$actor,false)",m("ns",namespace,"r",resource,"actor",user));assertEquals(resource,call("deposit.resource.info",m("resourceId",resource),ctx(recipient)).get("data").get("resourceId").asString());
        ResourceAndGeometryIT.mutate("deposit.set.namespaceRole",m("userId",user,"role",null));error("NOT_FOUND",()->call("deposit.resource.info",m("resourceId",resource),ctx(recipient)));
    }
    @Test void publicationFaultsRecoverWithoutRepeatingEffects() {
        for(String point:List.of("chunk-flushed","publication-before","publication-after","ready-before-commit")) {
            String upload=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,point),ResourceAndGeometryIT.op()).get("data").get("uploadId").asString();
            var chunkContext=ResourceAndGeometryIT.op();var finishContext=ResourceAndGeometryIT.op();
            if(point.equals("chunk-flushed")){arm(point,"fail");error("INTERNAL_ERROR",()->call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,ResourceAndGeometryIT.ABC),chunkContext));}
            call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,ResourceAndGeometryIT.ABC),chunkContext);
            if(!point.equals("chunk-flushed")){arm(point,"fail");error("INTERNAL_ERROR",()->call("deposit.resource.finishUpload",m("uploadId",upload),finishContext));}
            var result=call("deposit.resource.finishUpload",m("uploadId",upload),finishContext).get("data");assertEquals(result,call("deposit.resource.finishUpload",m("uploadId",upload),finishContext).get("data"));
        }
    }
    @Test void readLeaseBlocksCollectionUntilTransactionCompletes()throws Exception {
        String resource=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"race");ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",resource));long revision=ResourceAndGeometryIT.seq();String other=BatchMutationIT.login();BatchMutationIT.select(other,ResourceAndGeometryIT.ns);
        try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.resource.downloadChunk($r,$c)",m("r",m("resourceId",resource,"offset",0L,"length",3L),"c",ctx(ResourceAndGeometryIT.token))).consume();
            var entered=new CountDownLatch(1);var pending=pool.submit(()->{entered.countDown();return call("deposit.set.descriptor",m("objectId",ResourceAndGeometryIT.object,"name","F","value",null),write(other,revision));});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(200,TimeUnit.MILLISECONDS));tx.commit();pending.get(10,TimeUnit.SECONDS);
        }ResourceAndGeometryIT.assertStored(resource);
    }
}
