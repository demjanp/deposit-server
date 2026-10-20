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
    @Test void validatedPackageCallbackExceptionsRemainDomainExceptions()throws Exception{
        var ns=manifest();Path graph=temp.resolve("callback-graph.json");Json.write(Files.newOutputStream(graph),Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"resources",List.of(),"userTools",List.of()));Path zip=archive(graph,ns);var expected=new IllegalStateException("caller failure");
        var actual=assertThrows(IllegalStateException.class,()->ExchangePackageCodec.withValidatedPackage(zip,()->{},pkg->{throw expected;}));assertSame(expected,actual);
    }
    @Test void checkpointSignalsKeepTheirIdentityAndRemoveWorkspaceAtEveryValidationPhase()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString();
        var tools=new ArrayList<Object>();for(int i=0;i<400;i++)tools.add(Json.map("kind","UserTool","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Tool "+i,"data","opaque "+i));
        var graph=Json.map("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Specimen","descriptors",List.of(Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"classId",clazz,"name","Payload","type","List")))),
            "objects",List.of(Json.map("kind","Object","id",object,"namespaceId",ns,"classId",clazz)),
            "memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"classId",clazz)),
            "descriptors",List.of(Json.map("kind","Descriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"name","Payload","value",Json.map("itemType","String","items",Collections.nCopies(1500,"scientific value")))),
            "geometries",List.of(),"relationships",List.of(),"classRelationships",List.of(),"resources",List.of(),"userTools",tools,
            "patterns",List.of(Json.map("kind","Pattern","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Specimen pattern","definition",Json.map("objects",Json.map("specimen",Json.map("className","Specimen","root",true))))));
        Path zip=temp.resolve("checkpoint-phases.zip");ExchangePackageCodec.write(zip,manifest,graph,null,Map.of());
        assertEquals(400L,ExchangePackageCodec.validateSummary(zip).counts().get("userTools"));
        for(boolean interrupt:List.of(false,true))for(String phase:List.of("archive","parse","index","value","pattern","callback")){
            var before=validationDirectories();var expected=new IllegalStateException("cancel "+phase);boolean[] callback={false};
            Runnable checkpoint=()->{
                boolean reached=callback[0]&&phase.equals("callback")||StackWalker.getInstance().walk(frames->frames.anyMatch(frame->switch(phase){
                    case "archive"->frame.getClassName().equals(PackageArchiveIO.class.getName())&&frame.getMethodName().equals("read");
                    case "parse"->frame.getClassName().endsWith("PackageWorkspace$Parser");
                    case "index"->frame.getClassName().equals(PackageGraphValidator.class.getName())&&frame.getMethodName().equals("validate");
                    case "value"->frame.getClassName().equals(PackageValueCodec.class.getName())&&frame.getMethodName().equals("validateValue");
                    case "pattern"->frame.getClassName().equals(PackageGraphValidator.class.getName())&&frame.getMethodName().equals("validatePatternRows");
                    default->false;
                }));
                if(reached){if(interrupt)Thread.currentThread().interrupt();else throw expected;}
            };
            java.util.function.Function<ExchangePackageCodec.PackageData,Object> traverse=pkg->{
                callback[0]=true;
                for(Object raw:(List<?>)pkg.graph().get("userTools")){var row=ValueCodec.map(raw);for(String key:List.of("id","namespaceId","name","data"))assertNotNull(row.get(key));}
                return null;
            };
            if(interrupt){try{assertThrows(InterruptedIOException.class,()->ExchangePackageCodec.withValidatedPackage(zip,checkpoint,traverse),phase);}finally{Thread.interrupted();}}
            else assertSame(expected,assertThrows(IllegalStateException.class,()->ExchangePackageCodec.withValidatedPackage(zip,checkpoint,traverse),phase),phase);
            assertEquals(before,validationDirectories(),"Workspace leaked after "+phase);
        }
    }
    @Test void callbackUncheckedIoFailureRetainsIdentityAndRemovesWorkspace()throws Exception{
        var manifest=manifest();Path graph=temp.resolve("callback-io.json");Files.writeString(graph,Json.write(Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"resources",List.of(),"userTools",List.of())));
        Path zip=archive(graph,manifest);var expected=new UncheckedIOException(new IOException("callback IO"));var before=validationDirectories();
        assertSame(expected,assertThrows(UncheckedIOException.class,()->ExchangePackageCodec.withValidatedPackage(zip,()->{},pkg->{throw expected;})));
        assertEquals(before,validationDirectories());
    }
    private Set<Path> validationDirectories()throws IOException{
        try(var paths=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){return paths.filter(path->path.getFileName().toString().startsWith("deposit-package-validation-")).collect(java.util.stream.Collectors.toSet());}
    }
    @Test void validatedCallbackPreservesOriginalTextDictionaryKeysAndPatternComments()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),classId=UUID.randomUUID().toString(),objectId=UUID.randomUUID().toString();
        String longText="original α 😀\n".repeat(4000),longKey="scientific-key-".repeat(3000),scientificId=UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        manifest.put("description",longText);
        var dictionary=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","comment","description","data","summary",longKey))dictionary.put(key,Json.map("type","String","value",key.equals("id")?scientificId:longText));
        dictionary.put("nested",Json.map("type","DDict","value",Json.map("objectId",Json.map("type","String","value",scientificId),"rootIds",Json.map("type","List","value",Json.map("itemType","String","items",List.of(scientificId))))));
        var definition=Json.map("objects",Json.map("specimen",Json.map("className","Specimen","root",true,"comment",longText,"descriptors",List.of(Json.map("name","Payload","comment",longText)))));
        var graph=Json.map("classes",List.of(Json.map("kind","Class","id",classId,"namespaceId",ns,"name","Specimen","comment",longText,"descriptors",List.of(Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"classId",classId,"name","Payload","type","DDict","comment",longText)))),
            "objects",List.of(Json.map("kind","Object","id",objectId,"namespaceId",ns,"classId",classId)),
            "descriptors",List.of(Json.map("kind","Descriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",objectId,"name","Payload","value",dictionary)),
            "memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",objectId,"classId",classId)),
            "geometries",List.of(),"relationships",List.of(),"classRelationships",List.of(),"resources",List.of(),
            "patterns",List.of(Json.map("kind","Pattern","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","SpecimenKey","comment",longText,"definition",definition)),
            "userTools",List.of(Json.map("kind","UserTool","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Opaque","data",longText,"comment",longText)));
        Path zip=temp.resolve("payload.zip");ExchangePackageCodec.write(zip,manifest,graph,null,Map.of());
        var expected=ExchangePackageCodec.validate(zip);
        ExchangePackageCodec.withValidatedPackage(zip,()->{},pkg->{
            assertEquals(expected.manifest(),pkg.manifest());assertEquals(expected.graph(),pkg.graph());assertEquals(Json.write(expected.graph()),Json.write(pkg.graph()));
            var descriptor=ValueCodec.map(((List<?>)pkg.graph().get("descriptors")).getFirst());var actualDictionary=ValueCodec.map(descriptor.get("value"));
            assertTrue(actualDictionary.containsKey(longKey));assertEquals(longText,ValueCodec.map(actualDictionary.get(longKey)).get("value"));
            assertEquals(dictionary,ExchangePackageCodec.decodeValue("DDict",actualDictionary));
            assertEquals(PatternCodec.normalize(definition),PatternCodec.normalize(ValueCodec.map(ValueCodec.map(((List<?>)pkg.graph().get("patterns")).getFirst()).get("definition"))));
            return null;
        });
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
        String summary="original history α ".repeat(3000);var image=Json.map("kind","Namespace","id",ns,"namespaceId",ns,"name","N");var baseline=Json.map("id",id,"revisionId",id,"namespaceId",ns,"historyEpochId",epoch,"sequence",0L,"kind","baseline","summary",summary,"parentRevisionId",null,"changes",List.of(Json.map("entityKind","Namespace","entityId",ns,"before",null,"after",image)),"touches",lower,"dependencies",List.of());
        var first=new LinkedHashMap<>(baseline);first.put("touches",upper);var epochRecord=Json.map("id",epoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",id,"headRevisionId",id,"baseline",baseline,"revisions",List.of(first));var history=Json.map("namespaceId",ns,"historyMode","persistent","activeEpochId",epoch,"headRevisionId",id,"epochs",List.of(epochRecord),"complete",true);
        var graph=Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"resources",List.of(),"userTools",List.of(Json.map("kind","UserTool","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Tool","data","opaque")));
        Path zip=temp.resolve("journal.zip");ExchangePackageCodec.write(zip,manifest,graph,history,Map.of());runProbe(zip);
        ExchangePackageCodec.withValidatedPackage(zip,()->{},pkg->{var epochView=ValueCodec.map(((List<?>)pkg.history().get("epochs")).getFirst());assertEquals(summary,ValueCodec.map(epochView.get("baseline")).get("summary"));assertEquals(summary,ValueCodec.map(((List<?>)epochView.get("revisions")).getFirst()).get("summary"));return null;});
    }
    private void runProbe(Path archive)throws Exception{
        Path scratch=Files.createDirectory(temp.resolve("scratch"));Path log=temp.resolve("probe.log");String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Path arguments=temp.resolve("probe-args.txt");Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        Process process=new ProcessBuilder(java,"-Xmx48m","-Djava.io.tmpdir="+scratch,"@"+arguments,Probe.class.getName(),archive.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Constrained-heap validation timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
        try(var paths=Files.list(scratch)){assertEquals(0,paths.count(),"Validation workspace leaked");}
    }
    public static class Probe {public static void main(String[] args)throws Exception{var archive=Path.of(args[0]);var result=ExchangePackageCodec.validateSummary(archive);if(result.counts().get("userTools")!=1L)throw new AssertionError(result);ExchangePackageCodec.withValidatedPackage(archive,()->{},pkg->{if(((List<?>)pkg.graph().get("userTools")).size()!=1)throw new AssertionError();return null;});System.out.println("bounded-validation-passed");}}
}
