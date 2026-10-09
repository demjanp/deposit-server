package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;

/** Real termination of the recorded test-owned JVM at persisted storage barriers. */
@Timeout(90)
class Stage4CrashIT {
    static final Path FIXTURE=ROOT.resolve("stage4-crash.properties");
    @Test void trigger()throws Exception {
        connect();try {
            Stage4ProbeIT.ensureAccount();Stage4CoreIT.token=Stage3CoreIT.login();new Stage4CoreIT().fixture();
            String point=System.getenv("STAGE4_CRASH_POINT");assertNotNull(point);String token=Stage4CoreIT.token;
            var p=new Properties();p.setProperty("point",point);p.setProperty("namespace",Stage4CoreIT.ns);p.setProperty("object",Stage4CoreIT.object);
            var begin=Stage4CoreIT.op();p.setProperty("begin",(String)begin.get("operationId"));String upload=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,point),begin).get("data").get("uploadId").asString();p.setProperty("upload",upload);
            var chunk=Stage4CoreIT.op();p.setProperty("chunk",(String)chunk.get("operationId"));var finish=Stage4CoreIT.op();p.setProperty("finish",(String)finish.get("operationId"));
            String resource=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.resourceId AS id",m("id",upload)).getFirst().get("id").asString();p.setProperty("resource",resource);
            if(!point.equals("chunk-flushed"))call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,Stage4CoreIT.ABC),chunk);
            if(point.startsWith("relocation")||point.startsWith("collection")){call("deposit.resource.finishUpload",m("uploadId",upload),finish);Stage4CoreIT.descriptor("F","DResource",m("resourceId",resource));}
            if(point.startsWith("collection")){Stage4CoreIT.mutate("deposit.set.descriptor",m("objectId",Stage4CoreIT.object,"name","F","value",null));Stage4CoreIT.awaitPath(resource,"deleted");query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",resource));}
            Stage4ProbeIT.arm(point,"pause");
            try(var pool=Executors.newSingleThreadExecutor()) {
                var task=pool.submit(()->{
                    if(point.equals("chunk-flushed"))return call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,Stage4CoreIT.ABC),chunk);
                    if(point.startsWith("publication")||point.equals("ready-before-commit"))return call("deposit.resource.finishUpload",m("uploadId",upload),finish);
                    if(point.startsWith("relocation"))return Stage4CoreIT.mutate("deposit.set.descriptor",m("objectId",Stage4CoreIT.object,"name","F","value",null));
                    return call("deposit.admin.pruneResources",m("dryRun",false,"minimumAgeHours",0L),Stage4CoreIT.op());
                });
                Path marker=ROOT.resolve("stage4-pause.json");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(50);
                assertTrue(Files.exists(marker),"Storage barrier not reached: "+point);assertTrue(Files.readString(marker).contains(point));
                if(point.startsWith("collection"))p.setProperty("prune",task.get(5,TimeUnit.SECONDS).get("data").get("pruneId").asString());
                try(var out=Files.newOutputStream(FIXTURE)){p.store(out,"Stage 4 real crash fixture");}
                String record=Files.readString(ROOT.resolve("process.json"));Matcher pid=Pattern.compile("\"pid\"\\s*:\\s*(\\d+)").matcher(record);assertTrue(pid.find());ProcessHandle process=ProcessHandle.of(Long.parseLong(pid.group(1))).orElseThrow();
                // Windows ProcessHandle does not expose commandLine reliably; the native helper
                // checks the recorded PID, creation time, installation path and Neo4j command.
                Path helper=Path.of("..","scripts","Kill-TestOwnedJvm.ps1").toAbsolutePath().normalize();assertTrue(Files.isRegularFile(helper));
                Process killer=new ProcessBuilder("powershell.exe","-NoProfile","-File",helper.toString()).redirectErrorStream(true).start();assertTrue(killer.waitFor(15,TimeUnit.SECONDS));assertEquals(0,killer.exitValue(),new String(killer.getInputStream().readAllBytes()));process.onExit().get(15,TimeUnit.SECONDS);
                try{task.get(10,TimeUnit.SECONDS);}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof org.neo4j.driver.exceptions.Neo4jException);}
                Files.writeString(Path.of(System.getenv("STAGE0_EVIDENCE")).resolve("storage-kill.txt"),point+"\npid="+process.pid()+"\n");
            }
        }finally{close();}
    }
    @Test void verify()throws Exception {
        connect();try {
            var p=new Properties();try(var in=Files.newInputStream(FIXTURE)){p.load(in);}String token=Stage3CoreIT.login();Stage4CoreIT.token=token;Stage3CoreIT.select(token,p.getProperty("namespace"));String point=p.getProperty("point");
            if(point.startsWith("collection")) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);org.neo4j.driver.Value status;do{status=call("deposit.admin.pruneResourcesStatus",m("pruneId",p.getProperty("prune")),ctx(token)).get("data");if(status.get("state").asString().equals("completed"))break;Thread.sleep(50);}while(System.nanoTime()<deadline);
                assertEquals("completed",status.get("state").asString());assertEquals(3,status.get("reclaimedBytes").asLong());assertFalse(Files.exists(Stage4CoreIT.resourcePath(p.getProperty("resource"))));
            }else {
                if(!point.startsWith("relocation")) {
                    var begin=ctx(token);begin.put("operationId",p.getProperty("begin"));long offset=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,point),begin).get("data").get("nextOffset").asLong();assertEquals(point.equals("chunk-flushed")?0:3,offset);
                    if(offset==0){var c=ctx(token);c.put("operationId",p.getProperty("chunk"));call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(p.getProperty("upload"),0,Stage4CoreIT.ABC),c);}
                    var finish=ctx(token);finish.put("operationId",p.getProperty("finish"));assertEquals(p.getProperty("resource"),call("deposit.resource.finishUpload",m("uploadId",p.getProperty("upload")),finish).get("data").get("resourceId").asString());
                }else Stage4CoreIT.awaitPath(p.getProperty("resource"),"deleted");
                assertArrayEquals(Stage4CoreIT.ABC,Files.readAllBytes(Stage4CoreIT.resourcePath(p.getProperty("resource"))));
            }
        }finally{close();}
    }
}
