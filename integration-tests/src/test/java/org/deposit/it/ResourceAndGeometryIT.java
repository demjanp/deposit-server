package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** Public Bolt contracts plus independent filesystem/graph inspection; no implementation imports. */
@Timeout(120)
class ResourceAndGeometryIT {
    static String token,ns,object;static final byte[] ABC="abc".getBytes(StandardCharsets.UTF_8);
    @BeforeAll static void connect4(){connect();ensureAccount();token=BatchMutationIT.login();}
    @AfterAll static void close4(){close();}
    @BeforeEach void fixture(){ns=id(call("deposit.create.namespace",m("name","Resource-"+UUID.randomUUID(),"historyMode","disabled"),write(token,revision(call("deposit.get.user",Map.of(),ctx(token))))));BatchMutationIT.select(token,ns);String clazz=id(mutate("deposit.create.class",m("name","Specimen")));object=id(mutate("deposit.create.object",m("classId",clazz)));}
    static long seq(){return revision(call("deposit.get.activeNamespace",Map.of(),ctx(token)));}
    static org.neo4j.driver.Record mutate(String proc,Map<String,Object> request){return call(proc,request,write(token,seq()));}
    static Map<String,Object> op(){var c=ctx(token);c.put("operationId",UUID.randomUUID().toString());return c;}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new RuntimeException(e);}}
    static Map<String,Object> meta(byte[] bytes,String filename){return m("filename",filename,"bytes",(long)bytes.length,"sha256",hash(bytes),"mediaType","application/octet-stream");}
    static Map<String,Object> chunk(String upload,long offset,byte[] bytes){return m("uploadId",upload,"offset",offset,"bytesBase64",Base64.getEncoder().encodeToString(bytes),"chunkSha256",hash(bytes));}
    static String upload(byte[] bytes,String filename){String upload=call("deposit.resource.beginUpload",meta(bytes,filename),op()).get("data").get("uploadId").asString();if(bytes.length>0)call("deposit.resource.uploadChunk",chunk(upload,0,bytes),op());return call("deposit.resource.finishUpload",m("uploadId",upload),op()).get("data").get("resourceId").asString();}
    static org.neo4j.driver.Record descriptor(String name,String type,Object value){return mutate("deposit.set.descriptor",m("objectId",object,"name",name,"value",m("type",type,"value",value)));}
    static Map<String,Object> geometry(String wkt,Object srid,int dimensions){return m("wkt",wkt,"srid",srid,"verticalSrid",null,"axisOrder",dimensions==3?"xyz":"xy","dimensions",(long)dimensions);}
    // Independent expected 0004 layout; no server implementation helper or stored physical path.
    static Path objectRoot(String id){String h=hash(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));return ROOT.resolve("resources/ocfl").resolve(h.substring(0,3)).resolve(h.substring(3,6)).resolve(h.substring(6,9)).resolve(h);}
    static Path resourcePath(String id){String name=query("MATCH (l:DepositResourceLocation {id:$id}) RETURN l.storedFilename AS name",m("id",id)).getFirst().get("name").asString();return objectRoot(id).resolve("v1/content").resolve(name);}
    static void assertStored(String id)throws Exception{Path path=resourcePath(id);assertTrue(Files.isRegularFile(path));assertTrue(Files.isRegularFile(objectRoot(id).resolve("0=ocfl_object_1.1")));assertFalse(Files.exists(objectRoot(id).resolve("v2")));}
    static long physicalBytes(String id)throws Exception{try(var files=Files.walk(objectRoot(id))){long total=0;for(Path p:files.filter(Files::isRegularFile).toList())total+=Files.size(p);return total;}}
    static void awaitStagingCleanup(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(query("MATCH (l:DepositResourceLocation {id:$id}) RETURN l.stagingPath AS path",m("id",id)).getFirst().get("path").isNull())return;Thread.sleep(50);}fail("Committed staging copy not cleaned");}
    @Test void sequentialChunksReplayResumeAndPublication()throws Exception{
        long before=seq();var beginContext=op();var metadata=meta(ABC,"note.txt");String upload=call("deposit.resource.beginUpload",metadata,beginContext).get("data").get("uploadId").asString();
        var chunkContext=op();var one=chunk(upload,0,new byte[]{97});assertEquals(1,call("deposit.resource.uploadChunk",one,chunkContext).get("data").get("nextOffset").asLong());
        assertEquals(1,call("deposit.resource.beginUpload",metadata,beginContext).get("data").get("nextOffset").asLong());
        error("VALIDATION_FAILED",()->call("deposit.resource.uploadChunk",chunk(upload,2,new byte[]{99}),op()));error("OPERATION_MISMATCH",()->call("deposit.resource.uploadChunk",chunk(upload,0,new byte[]{98}),op()));
        call("deposit.resource.uploadChunk",chunk(upload,1,new byte[]{98,99}),op());assertEquals(1,call("deposit.resource.uploadChunk",one,chunkContext).get("data").get("nextOffset").asLong());
        var finishContext=op();var request=m("uploadId",upload);var finish=call("deposit.resource.finishUpload",request,finishContext).get("data");String id=finish.get("resourceId").asString();assertEquals(finish,call("deposit.resource.finishUpload",request,finishContext).get("data"));assertArrayEquals(ABC,Files.readAllBytes(resourcePath(id)));
        var info=call("deposit.resource.info",m("resourceId",id),ctx(token)).get("data");assertEquals("note-"+hash(ABC)+".txt",info.get("storedFilename").asString());assertEquals(System.getenv("DEPOSIT_TEST_HTTP_BASE")+"/resources/"+ns+"/"+id,info.get("downloadUrl").asString());assertFalse(info.asMap().containsKey("path"));
        var downloaded=call("deposit.resource.downloadChunk",m("resourceId",id,"offset",0L,"length",3L),ctx(token)).get("data");assertEquals("YWJj",downloaded.get("bytesBase64").asString());assertTrue(downloaded.get("eof").asBoolean());assertEquals(before,seq());error("RESOURCE_NOT_READY",()->call("deposit.resource.abortUpload",request,op()));
    }
    @Test void chunkAndFinishRollbackRecoverSameIdentity()throws Exception {
        var beginContext=op();String upload=call("deposit.resource.beginUpload",meta(ABC,"rollback"),beginContext).get("data").get("uploadId").asString();var chunkContext=op();
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.resource.uploadChunk($r,$c)",m("r",chunk(upload,0,ABC),"c",chunkContext)).consume();tx.rollback();}
        assertEquals(0,call("deposit.resource.beginUpload",meta(ABC,"rollback"),beginContext).get("data").get("nextOffset").asLong());call("deposit.resource.uploadChunk",chunk(upload,0,ABC),chunkContext);
        var finishContext=op();String resource;
        try(var s=driver.session();var tx=s.beginTransaction()){resource=tx.run("CALL deposit.resource.finishUpload($r,$c)",m("r",m("uploadId",upload),"c",finishContext)).single().get("data").get("resourceId").asString();tx.rollback();}
        assertEquals(resource,call("deposit.resource.finishUpload",m("uploadId",upload),finishContext).get("data").get("resourceId").asString());assertArrayEquals(ABC,Files.readAllBytes(resourcePath(resource)));
    }
    @Test void independentResourcesFilenameRulesAndEmptyContent()throws Exception {
        String a=upload(ABC,"CON.photo.jpg"),b=upload(ABC,"CON.photo.jpg");assertNotEquals(a,b);assertNotEquals(resourcePath(a),resourcePath(b));assertEquals("_CON.photo-"+hash(ABC)+".jpg",resourcePath(a).getFileName().toString());
        for(String name:List.of("","a b/c\\d_!.txt",".hidden","tail.","x".repeat(140)+".jpg","caf\u00e9.txt")) {String id=upload(new byte[0],name);assertEquals(0,Files.size(resourcePath(id)));assertEquals(name,call("deposit.resource.info",m("resourceId",id),ctx(token)).get("data").get("filename").asString());assertTrue(resourcePath(id).getFileName().toString().matches("[a-zA-Z0-9_.-]+"));}
    }
    @Test void attachmentReplacementNullAndCascadePlacement()throws Exception {
        String id=upload(ABC,"shared");descriptor("File","DResource",m("resourceId",id));descriptor("Other","DResource",m("resourceId",id));
        mutate("deposit.set.descriptor",m("objectId",object,"name","File","value",null));assertStored(id);mutate("deposit.set.descriptor",m("objectId",object,"name","Other","value",null));assertStored(id);
        mutate("deposit.set.descriptor",m("objectId",object,"name","File","value",m("resourceId",id)));assertStored(id);mutate("deposit.delete.object",m("id",object));assertStored(id);assertArrayEquals(ABC,Files.readAllBytes(resourcePath(id)));
    }
    @Test void abortLimitsWrongScopeAndFailedValidationHaveNoEffects() {
        error("LIMIT_EXCEEDED",()->call("deposit.resource.beginUpload",m("filename","x","bytes",2147483649L,"sha256",hash(ABC),"mediaType","x"),op()));String upload=call("deposit.resource.beginUpload",meta(ABC,"abort"),op()).get("data").get("uploadId").asString();
        call("deposit.resource.abortUpload",m("uploadId",upload),op());assertTrue(call("deposit.resource.abortUpload",m("uploadId",upload),op()).get("data").get("aborted").asBoolean());error("NOT_FOUND",()->call("deposit.resource.uploadChunk",chunk(upload,0,ABC),op()));
        String other=id(call("deposit.create.namespace",m("name","other-"+UUID.randomUUID(),"historyMode","disabled"),write(token,revision(call("deposit.get.user",Map.of(),ctx(token))))));BatchMutationIT.select(token,other);error("SCOPE_MISMATCH",()->call("deposit.resource.finishUpload",m("uploadId",upload),op()));BatchMutationIT.select(token,ns);
    }
    @Test void allGeometryKindsXYXYZAndConversions() {
        List<String> xy=List.of("POINT (1 2)","LINESTRING (1 2,3 4)","POLYGON ((0 0,9 0,9 9,0 0),(6 1,7 1,7 2,6 1))","MULTIPOINT ((1 2),(3 4))","MULTILINESTRING ((1 2,3 4),(5 6,7 8))","MULTIPOLYGON (((0 0,9 0,9 9,0 0)),((20 20,30 20,30 30,20 20)))");
        List<String> xyz=List.of("POINT Z (1 2 7)","LINESTRING Z (1 2 7,3 4 8)","POLYGON Z ((0 0 1,9 0 2,9 9 3,0 0 1),(6 1 4,7 1 5,7 2 6,6 1 4))","MULTIPOINT Z ((1 2 7),(3 4 8))","MULTILINESTRING Z ((1 2 7,3 4 8),(5 6 9,7 8 10))","MULTIPOLYGON Z (((0 0 1,9 0 2,9 9 3,0 0 1)),((20 20 4,30 20 5,30 30 6,20 20 4)))");
        int i=0;for(var pair:List.of(xy,xyz))for(String wkt:pair) {
            int dimensions=pair==xy?2:3;var expected=call("deposit.utils.wktToCoordinates",m("wkt",wkt),ctx(token)).get("data");var row=descriptor("G"+(i++),"DGeometry",geometry(wkt,null,dimensions));assertFalse(row.get("data").get("geometryId").isNull());
            var round=call("deposit.utils.coordinatesToWKT",m("type",expected.get("geometryType").asString(),"coordinates",expected.get("coordinates").asObject()),ctx(token)).get("data");assertEquals(expected,round);assertEquals(expected.get("wkt"),row.get("data").get("value").get("wkt"));
        }
        assertEquals(12,query("MATCH (g:DGeometry {namespaceId:$ns}) RETURN count(g) AS n",m("ns",ns)).getFirst().get("n").asInt());
    }
    @Test void geometryEditIdentityNoOpNullCascadeAndSrid() {
        var first=descriptor("G","DGeometry",geometry("POINT Z (1 2 3)",4326L,3));String geometry=first.get("data").get("geometryId").asString();long before=seq();
        var same=mutate("deposit.set.descriptor",m("objectId",object,"name","G","value",geometry("POINT Z(1 2 3)",4326L,3)));assertEquals(before,revision(same));assertFalse(same.get("data").get("changed").asBoolean());
        var edit=mutate("deposit.set.descriptor",m("objectId",object,"name","G","value",geometry("POINT Z (9 8 7)",999999L,3)));assertEquals(geometry,edit.get("data").get("geometryId").asString());
        mutate("deposit.set.descriptor",m("objectId",object,"name","G","value",null));assertEquals(0,query("MATCH (g:DGeometry {namespaceId:$ns}) RETURN count(g) AS n",m("ns",ns)).getFirst().get("n").asInt());
        mutate("deposit.set.descriptor",m("objectId",object,"name","G","value",geometry("POINT (5 6)",null,2)));mutate("deposit.delete.object",m("id",object));assertEquals(0,query("MATCH (g:DGeometry {namespaceId:$ns}) RETURN count(g) AS n",m("ns",ns)).getFirst().get("n").asInt());
    }
    @Test void invalidGeometryAndCollectionsRollbackSchemaAndBatch() {
        long before=seq();for(String invalid:List.of("POINT EMPTY","POINT M (1 2 3)","POINT Z (1 2)","POLYGON ((0 0,2 2,0 2,2 0,0 0))"))error("VALIDATION_FAILED",()->descriptor("Invalid","DGeometry",geometry(invalid,null,2)));
        for(String type:List.of("DGeometry","DResource")){error("VALIDATION_FAILED",()->descriptor("Invalid","List",m("itemType",type,"items",List.of())));error("VALIDATION_FAILED",()->descriptor("Invalid","DDict",m("entry",m("type",type,"value",null))));}
        var commands=List.of(BatchMutationIT.command("g","deposit.set.descriptor",m("objectId",object,"name","G","value",m("type","DGeometry","value",geometry("POINT (1 2)",null,2)))),BatchMutationIT.command("bad","deposit.set.object",m("id",UUID.randomUUID().toString())));
        error("NOT_FOUND",()->mutate("deposit.apply.batch",m("commands",commands)));assertEquals(before,seq());assertEquals(0,query("MATCH (g:DGeometry {namespaceId:$ns}) RETURN count(g) AS n",m("ns",ns)).getFirst().get("n").asInt());
    }
    @Test void pruneSchedulingRollbackReplayStatusAndReadProtection()throws Exception {
        String id=upload(ABC,"collect");descriptor("F","DResource",m("resourceId",id));
        var dry=call("deposit.admin.pruneResources",m("minimumAgeHours",0L),ctx(token));assertFalse(dry.get("data").get("candidateIds").asList().contains(id));
        mutate("deposit.set.descriptor",m("objectId",object,"name","F","value",null));assertStored(id);
        // Independent fixture expires the lease and dates an orphan; production clocks remain unmodified.
        query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",id));
        awaitStagingCleanup(id);long expectedPhysical=physicalBytes(id);var req=m("dryRun",false,"minimumAgeHours",0L);var context=op();
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.admin.pruneResources($r,$c)",m("r",req,"c",context)).consume();tx.rollback();}assertTrue(Files.exists(resourcePath(id)));
        var job=call("deposit.admin.pruneResources",req,context).get("data");assertEquals(job,call("deposit.admin.pruneResources",req,context).get("data"));String prune=job.get("pruneId").asString();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);org.neo4j.driver.Value status;
        do{status=call("deposit.admin.pruneResourcesStatus",m("pruneId",prune),ctx(token)).get("data");if(status.get("state").asString().equals("completed"))break;Thread.sleep(50);}while(System.nanoTime()<deadline);
        assertEquals("completed",status.get("state").asString());assertEquals(expectedPhysical,status.get("reclaimedBytes").asLong());assertFalse(Files.exists(resourcePath(id)));
    }
    @Test void persistRecoveryFixture()throws Exception {
        String resource=upload(ABC,"persist.txt");var g=descriptor("G","DGeometry",geometry("POINT Z (12 34 56)",4326L,3));descriptor("F","DResource",m("resourceId",resource));
        var properties=new Properties();properties.setProperty("namespace",ns);properties.setProperty("object",object);properties.setProperty("resource",resource);properties.setProperty("geometry",g.get("data").get("geometryId").asString());properties.setProperty("sequence",Long.toString(seq()));try(var out=Files.newOutputStream(ROOT.resolve("resource-fixture.properties"))){properties.store(out,"resource recovery fixture");}
    }
}
