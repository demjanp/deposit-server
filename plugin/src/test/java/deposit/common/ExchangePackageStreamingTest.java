package deposit.common;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExchangePackageStreamingTest {
    @TempDir Path temp;
    private Map<String,Object> manifest(){return Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",UUID.randomUUID().toString(),"name","Streaming","description","","depositVersion","test","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace","includeHistory",false);}
    private Path archive(Path graph,Map<String,Object> manifest)throws Exception{
        manifest.put("files",Json.map("graph.json",Json.map("bytes",Files.size(graph),"sha256",ResourceFiles.checksum(graph))));Path archive=temp.resolve(UUID.randomUUID()+".zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("graph.json"));Files.copy(graph,zip);zip.closeEntry();zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(Json.write(manifest).getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return archive;
    }
    @Test void opaqueStringLargerThanHeapValidatesAndWorkspaceIsRemoved()throws Exception{
        Path graph=temp.resolve("large.json");var manifest=manifest();
        try(var out=Files.newBufferedWriter(graph)){out.write("{\"classes\":[],\"objects\":[],\"descriptors\":[],\"geometries\":[],\"memberships\":[],\"relationships\":[],\"classRelationships\":[],\"patterns\":[],\"resources\":[],\"userTools\":[{\"kind\":\"UserTool\",\"id\":\""+UUID.randomUUID()+"\",\"namespaceId\":\""+manifest.get("sourceNamespaceId")+"\",\"name\":\"Opaque\",\"data\":\"");String chunk=" x\\t\\u03b1".repeat(4096);for(int i=0;i<2048;i++)out.write(chunk);out.write("\"}]}");}
        assertTrue(Files.size(graph)>48L*1024*1024);runProbe(archive(graph,manifest));
    }
    @Test void duplicateJsonPropertiesAndTrailingInputAreRejected()throws Exception{
        for(String json:List.of("{\"classes\":[],\"classes\":[]}","{} {}","{\"x\":01}","{\"x\":\"bad\nstring\"}")){
            Path graph=temp.resolve(UUID.randomUUID()+".json");Files.writeString(graph,json);Path zip=archive(graph,manifest());assertThrows(IOException.class,()->ExchangePackageCodec.validateSummary(zip));
        }
    }
    @Test void spillMapRetainsUpdatesDeletesAndCategoryQualifiedKeys()throws Exception{
        try(var workspace=new PackageWorkspace(temp)){Map<String,String> map=workspace.map();for(int i=0;i<6000;i++)map.put("Object\u0000"+i,"v"+i);for(int i=0;i<6000;i+=2)map.remove("Object\u0000"+i);for(int i=0;i<6000;i++)map.put("Pattern\u0000"+i,"p"+i);assertEquals(9000,map.size());for(int i=0;i<6000;i++){assertEquals("p"+i,map.get("Pattern\u0000"+i));assertEquals(i%2==0?null:"v"+i,map.get("Object\u0000"+i));}map.clear();assertTrue(map.isEmpty());map.put("Object\u0000same","one");map.put("Pattern\u0000same","two");assertEquals("one",map.get("Object\u0000same"));}
        try(var paths=Files.list(temp)){assertEquals(0,paths.count());}
    }
    @Test void nestedDiskViewsPreserveLookupAndReusableCollectionLinks()throws Exception{
        try(var workspace=new PackageWorkspace(temp)){Map<String,Object> map=workspace.map();map.put("first",Json.map("items",List.of(Json.map("name","A"),Json.map("name","B"))));var original=ValueCodec.map(map.get("first"));var items=(List<?>)original.get("items");assertEquals("A",ValueCodec.map(items.getFirst()).get("name"));map.put("second",Json.map("reused",items));assertEquals(items,ValueCodec.map(map.get("second")).get("reused"));assertTrue(ValueCodec.map(((List<?>)ValueCodec.map(map.get("second")).get("reused")).getFirst()).containsKey("name"));map.put("first",Json.map("items",List.of(Json.map("name","C"))));assertEquals("A",ValueCodec.map(items.getFirst()).get("name"));}
    }
    private static String collidingKey(int value){var key=new StringBuilder();for(int bit=0;bit<13;bit++)key.append((value&(1<<bit))==0?"Aa":"BB");return key.toString();}
    @Test void deliberateJavaHashCollisionsHaveBoundedProbesAndExactContents()throws Exception{
        assertEquals(collidingKey(0).hashCode(),collidingKey(8191).hashCode());
        try(var workspace=new PackageWorkspace(temp)){
            Map<String,Long> map=workspace.map();long start=workspace.probeCount();
            for(int i=0;i<8192;i++)assertNull(map.put(collidingKey(i),(long)i));
            for(int i=0;i<8192;i++)assertEquals((long)i,map.get(collidingKey(i)));
            for(int i=0;i<8192;i+=3)assertEquals((long)i,map.put(collidingKey(i),-(long)i));
            for(int i=0;i<8192;i+=4)assertNotNull(map.remove(collidingKey(i)));
            assertEquals(6144,map.size());
            for(int i=0;i<8192;i++)assertEquals(i%4==0?null:i%3==0?-(long)i:(long)i,map.get(collidingKey(i)));
            assertTrue(workspace.probeCount()-start<8192L*50,"Spill probing grew quadratically");
        }
    }
    @Test void mixedCaseBaselineJournalUsesBoundedIndexedComparison()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId"),epoch=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        var lower=new LinkedHashMap<String,Object>();var upper=new LinkedHashMap<String,Object>();
        for(int i=0;i<12000;i++){String entity=String.format("abcdefab-cdef-4abc-8def-%012x",i);lower.put(HistoryCodec.key("Object",entity),List.of("name"));upper.put(HistoryCodec.key("Object",entity.toUpperCase(Locale.ROOT)),List.of("name"));}
        var image=Json.map("kind","Namespace","id",ns,"namespaceId",ns,"name","N");var baseline=Json.map("id",id,"revisionId",id,"namespaceId",ns,"historyEpochId",epoch,"sequence",0L,"kind","baseline","parentRevisionId",null,"changes",List.of(Json.map("entityKind","Namespace","entityId",ns,"before",null,"after",image)),"touches",lower,"dependencies",List.of());
        var first=new LinkedHashMap<>(baseline);first.put("touches",upper);var epochRecord=Json.map("id",epoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",id,"headRevisionId",id,"baseline",baseline,"revisions",List.of(first));var history=Json.map("namespaceId",ns,"historyMode","persistent","activeEpochId",epoch,"headRevisionId",id,"epochs",List.of(epochRecord),"complete",true);
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"resources",List.of(),"userTools",List.of(Json.map("kind","UserTool","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Tool","data","opaque")));
        Path zip=temp.resolve("journal.zip");ExchangePackageCodec.write(zip,manifest,graph,history,Map.of());runProbe(zip);
    }
    private void runProbe(Path archive)throws Exception{
        Path scratch=Files.createDirectory(temp.resolve("scratch"));Path log=temp.resolve("probe.log");String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Path arguments=temp.resolve("probe-args.txt");Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        Process process=new ProcessBuilder(java,"-Xmx48m","-Djava.io.tmpdir="+scratch,"@"+arguments,Probe.class.getName(),archive.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Constrained-heap validation timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
        try(var paths=Files.list(scratch)){assertEquals(0,paths.count(),"Validation workspace leaked");}
    }
    public static class Probe {public static void main(String[] args)throws Exception{var result=ExchangePackageCodec.validateSummary(Path.of(args[0]));if(result.counts().get("userTools")!=1L)throw new AssertionError(result);System.out.println("bounded-validation-passed");}}
}
