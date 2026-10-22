package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;

/** Real termination of the recorded test-owned JVM at persisted storage barriers. */
@Timeout(90)
class ResourceCrashRecoveryIT {
    static final Path FIXTURE=ROOT.resolve("resource-crash.properties");
    @Test void trigger()throws Exception {
        connect();try {
            ResourceFailureProbeIT.ensureAccount();ResourceAndGeometryIT.token=BatchMutationIT.login();new ResourceAndGeometryIT().fixture();
            String point=System.getenv("DEPOSIT_TEST_RESOURCE_CRASH_POINT");assertNotNull(point);String token=ResourceAndGeometryIT.token;
            var p=new Properties();p.setProperty("point",point);p.setProperty("namespace",ResourceAndGeometryIT.ns);p.setProperty("object",ResourceAndGeometryIT.object);
            var begin=ResourceAndGeometryIT.op();p.setProperty("begin",(String)begin.get("operationId"));String upload=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,point),begin).get("data").get("uploadId").asString();p.setProperty("upload",upload);
            var chunk=ResourceAndGeometryIT.op();p.setProperty("chunk",(String)chunk.get("operationId"));var finish=ResourceAndGeometryIT.op();p.setProperty("finish",(String)finish.get("operationId"));
            String resource=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.resourceId AS id",m("id",upload)).getFirst().get("id").asString();p.setProperty("resource",resource);
            if(!point.equals("chunk-flushed"))call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,ResourceAndGeometryIT.ABC),chunk);
            if(point.startsWith("collection")){call("deposit.resource.finishUpload",m("uploadId",upload),finish);ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",resource));}
            if(point.startsWith("collection")){ResourceAndGeometryIT.mutate("deposit.set.descriptor",m("objectId",ResourceAndGeometryIT.object,"name","F","value",null));ResourceAndGeometryIT.assertStored(resource);ResourceAndGeometryIT.awaitStagingCleanup(resource);p.setProperty("physicalBytes",Long.toString(ResourceAndGeometryIT.physicalBytes(resource)));query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",resource));}
            if(point.equals("collection-partial"))query("CALL testprobe.storage.partialPurge()",Map.of());
            ResourceFailureProbeIT.arm(point,"pause");
            try(var pool=Executors.newSingleThreadExecutor()) {
                var task=pool.submit(()->{
                    if(point.equals("chunk-flushed"))return call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,ResourceAndGeometryIT.ABC),chunk);
                    if(point.startsWith("publication")||point.equals("ready-before-commit")||point.startsWith("staging-cleanup"))return call("deposit.resource.finishUpload",m("uploadId",upload),finish);
                    return call("deposit.admin.pruneResources",m("dryRun",false,"minimumAgeHours",0L),ResourceAndGeometryIT.op());
                });
                if(point.startsWith("collection")) {
                    var prune=task.get(10,TimeUnit.SECONDS).get("data");
                    p.setProperty("prune",prune.get("pruneId").asString());
                    var candidates=prune.get("candidateIds").asList(v->v.asString());
                    String eligibility=collectionEligibility(resource);
                    assertTrue(candidates.contains(resource),"Target was not scheduled for collection; candidates="+candidates+"; target="+eligibility);
                }
                Path marker=TestInstallationPaths.resourcePauseMarker();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(50);
                assertTrue(Files.exists(marker),"Storage barrier not reached: "+point);assertTrue(Files.readString(marker).contains(point));
                try(var out=Files.newOutputStream(FIXTURE)){p.store(out,"resource real crash fixture");}
                String record=Files.readString(ROOT.resolve("process.json"));Matcher pid=Pattern.compile("\"pid\"\\s*:\\s*(\\d+)").matcher(record);assertTrue(pid.find());ProcessHandle process=ProcessHandle.of(Long.parseLong(pid.group(1))).orElseThrow();
                // Windows ProcessHandle does not expose commandLine reliably; the native helper
                // checks the recorded PID, creation time, installation path and Neo4j command.
                Path helper=Path.of("..","scripts","Kill-TestOwnedJvm.ps1").toAbsolutePath().normalize();assertTrue(Files.isRegularFile(helper));
                Process killer=new ProcessBuilder("powershell.exe","-NoProfile","-File",helper.toString()).redirectErrorStream(true).start();assertTrue(killer.waitFor(15,TimeUnit.SECONDS));assertEquals(0,killer.exitValue(),new String(killer.getInputStream().readAllBytes()));process.onExit().get(15,TimeUnit.SECONDS);
                try{task.get(10,TimeUnit.SECONDS);}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof org.neo4j.driver.exceptions.Neo4jException);}
            }
        }finally{close();}
    }
    private static String collectionEligibility(String resource) {
        var row=query("MATCH (l:DepositResourceLocation {id:$id}) " +
                "OPTIONAL MATCH (r:DResource {id:$id}) " +
                "OPTIONAL MATCH (r)-[:HAS_RESOURCE]-() " +
                "WITH l,r,count(*) AS attachmentEdges " +
                "OPTIONAL MATCH (r)-[:RETAINS_RESOURCE]-() " +
                "WITH l,r,attachmentEdges,count(*) AS retainedEdges " +
                "OPTIONAL MATCH (g:DepositExportGrant {resourceId:$id}) " +
                "WITH l,r,attachmentEdges,retainedEdges,count(g) AS grants " +
                "OPTIONAL MATCH (lease:DepositResourceLease {resourceId:$id}) " +
                "WITH l,r,attachmentEdges,retainedEdges,grants,count(lease) AS leases " +
                "OPTIONAL MATCH (t:DepositTransfer {resourceId:$id}) " +
                "RETURN properties(l) AS location,properties(r) AS resource,attachmentEdges,retainedEdges,grants,leases,collect(properties(t)) AS transfers",
                m("id",resource)).getFirst();
        return "location="+row.get("location")+", resource="+row.get("resource")+
                ", attachmentEdges="+row.get("attachmentEdges")+
                ", retainedEdges="+row.get("retainedEdges")+
                ", grants="+row.get("grants")+", leases="+row.get("leases")+
                ", transfers="+row.get("transfers");
    }
    @Test void verify()throws Exception {
        connect();try {
            var p=new Properties();try(var in=Files.newInputStream(FIXTURE)){p.load(in);}String token=BatchMutationIT.login();ResourceAndGeometryIT.token=token;BatchMutationIT.select(token,p.getProperty("namespace"));String point=p.getProperty("point");
            if(point.startsWith("collection")) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);org.neo4j.driver.Value status;do{status=call("deposit.admin.pruneResourcesStatus",m("pruneId",p.getProperty("prune")),ctx(token)).get("data");if(status.get("state").asString().equals("completed"))break;Thread.sleep(50);}while(System.nanoTime()<deadline);
                assertEquals("completed",status.get("state").asString());assertEquals(Long.parseLong(p.getProperty("physicalBytes")),status.get("reclaimedBytes").asLong());assertFalse(Files.exists(ResourceAndGeometryIT.objectRoot(p.getProperty("resource"))));
            }else {
                {
                    var begin=ctx(token);begin.put("operationId",p.getProperty("begin"));long offset=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,point),begin).get("data").get("nextOffset").asLong();assertEquals(point.equals("chunk-flushed")?0:3,offset);
                    if(offset==0){var c=ctx(token);c.put("operationId",p.getProperty("chunk"));call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(p.getProperty("upload"),0,ResourceAndGeometryIT.ABC),c);}
                    var finish=ctx(token);finish.put("operationId",p.getProperty("finish"));assertEquals(p.getProperty("resource"),call("deposit.resource.finishUpload",m("uploadId",p.getProperty("upload")),finish).get("data").get("resourceId").asString());
                }
                ResourceAndGeometryIT.assertStored(p.getProperty("resource"));ResourceAndGeometryIT.awaitStagingCleanup(p.getProperty("resource"));
                assertArrayEquals(ResourceAndGeometryIT.ABC,Files.readAllBytes(ResourceAndGeometryIT.resourcePath(p.getProperty("resource"))));
            }
        }finally{close();}
    }
}
