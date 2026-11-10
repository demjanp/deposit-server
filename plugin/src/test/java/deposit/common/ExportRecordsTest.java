package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportRecordsTest {
    @TempDir Path temp;
    @Test void stagedWritesReserveBeforeMaterializingSpoolAndSemanticIndexes()throws Exception{
        long[] reserved={0},written={0};ExportRecords.StorageBudget budget=new ExportRecords.StorageBudget(){public void reserve(long bytes){reserved[0]+=bytes;}public void materialized(long bytes){written[0]+=bytes;}};
        try(var records=new ExportRecords(temp,()->{},budget)){
            for(int i=0;i<500;i++)records.add("rows",Json.map("id","row-"+i,"value","payload-".repeat(20)));
            Map<String,Object> index=records.index();for(int i=0;i<500;i++)index.put("Object:"+i,(long)i);
        }
        assertTrue(reserved[0]>=written[0]);assertTrue(written[0]>0);
        try(var children=Files.list(temp)){assertEquals(0,children.count());}
    }
    @Test void fixedRevisionRangesKeepEpochBaselinesAndNestedRowsSeparateAfterLaterAppends()throws Exception {
        try(var records=new ExportRecords(temp)){
            records.addRevision("history-revisions",Json.map("revisionId","closed-baseline","changes",List.of(Json.map("entityId","first-object")),"touches",Json.map("Object\u0000first-object",List.of("*")),"dependencies",List.of("Class\u0000first-class")));
            var closed=records.revisions("history-revisions",0,1);
            records.addRevision("history-revisions",Json.map("revisionId","active-baseline","changes",List.of(Json.map("entityId","second-object")),"touches",Json.map("Object\u0000second-object",List.of("value")),"dependencies",List.of("Class\u0000second-class")));
            var active=records.revisions("history-revisions",1,2);
            records.addRevision("history-revisions",Json.map("revisionId","later-revision","changes",List.of(),"touches",Map.of(),"dependencies",List.of()));
            assertEquals(1,closed.size());assertEquals("closed-baseline",closed.getFirst().get("revisionId"));
            assertEquals(1,active.size());assertEquals("active-baseline",active.getFirst().get("revisionId"));
            assertEquals("second-object",ValueCodec.map(((List<?>)active.getFirst().get("changes")).getFirst()).get("entityId"));
            assertEquals(Json.map("Object\u0000second-object",List.of("value")),active.getFirst().get("touches"));
            assertEquals(List.of("Class\u0000second-class"),active.getFirst().get("dependencies"));
            assertEquals(3,records.revisions("history-revisions").size());
        }
    }
    @Test void stagedRowsSlicesAndIndexesPreserveOpaqueValuesAndCleanUp() throws Exception {
        try(var records=new ExportRecords(temp)) {
            String opaque="  not JSON\nλ\u0000";
            records.add("rows",Json.map("id","a","data",opaque,"value",List.of("x",Long.MIN_VALUE)));
            var fixed=records.slice("rows",0,1);
            records.add("rows",Json.map("id","b","data","later"));
            assertEquals(1,fixed.size());assertEquals(opaque,fixed.getFirst().get("data"));
            assertEquals(Long.MIN_VALUE,((List<?>)fixed.getFirst().get("value")).get(1));
            Map<String,Object> index=records.index();index.put("Object:same",0L);index.put("Pattern:same",1L);
            assertEquals(2,index.size());assertEquals(0L,index.get("Object:same"));
            records.addValue("values","raw");assertEquals(List.of("raw"),records.valuesSlice("values",0,1));
        }
        try(var children=Files.list(temp)){assertEquals(0,children.count());}
    }
    @Test void diskProjectionAgreesWithInMemoryProjectionWithoutLeakingOpaqueText() throws Exception {
        var namespace=Json.map("kind","Namespace","id","ns","namespaceId","ns","name","N");
        var object=Json.map("kind","Object","id","o","namespaceId","ns","comment","  original λ\n");
        var changes=List.of(Json.map("entityKind","Namespace","entityId","ns","before",null,"after",namespace),Json.map("entityKind","Object","entityId","o","before",null,"after",object));
        var revision=Json.map("kind","baseline","changes",changes,"touches",Json.map("Object:o",List.of("comment")),"dependencies",List.of("Object:o"));
        var source=Json.map("baseline",revision,"revisions",List.of(revision));
        var expected=HistoryProjection.project(source,Set.of("o"),Set.of(),false);
        try(var records=new ExportRecords(temp)) {
            var actual=HistoryProjection.project(source,Set.of("o"),Set.of(),false,records);
            assertEquals(Json.write(expected.history()),Json.write(actual.history()));
            assertEquals(expected.resourceIds(),actual.resourceIds());
        }
    }
    @Test void sourceStringExceedingDefaultParserLimitAndLazyRevisionRoundTrip() throws Exception {
        String large="λ".repeat(21_000_000);
        try(var records=new ExportRecords(temp)) {
            records.add("tools",Json.map("data",large));assertEquals(large,records.list("tools").getFirst().get("data"));
            var changes=new AbstractList<Map<String,Object>>(){public int size(){return 5000;}public Map<String,Object> get(int i){return Json.map("entityId","o"+i,"before",null,"after",null);}};
            records.addRevision("revisions",Json.map("revisionId","r","changes",changes,"touches",Json.map("Object:o",List.of("position")),"dependencies",List.of("Object:o")));
            var revision=records.revisions("revisions").getFirst();assertEquals(5000,((List<?>)revision.get("changes")).size());assertEquals("o4999",ValueCodec.map(((List<?>)revision.get("changes")).getLast()).get("entityId"));
            assertEquals(List.of("Object:o"),revision.get("dependencies"));assertEquals(Json.map("Object:o",List.of("position")),revision.get("touches"));
        }
    }
    @Test void largeDeclarationGroupAndRevisionMetadataStreamUnderConstrainedHeap()throws Exception{
        Path arguments=temp.resolve("arguments.txt"),log=temp.resolve("probe.log");
        Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx48m","@"+arguments,Probe.class.getName(),temp.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(240,java.util.concurrent.TimeUnit.SECONDS),"Source spool probe timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
    }
    public static class Probe {
        public static void main(String[] args)throws Exception{
            try(var records=new ExportRecords(Path.of(args[0]))){
                Map<String,Object> touches=records.index();Set<String> dependencies=Collections.newSetFromMap(records.<Boolean>index());
                for(int i=0;i<100_000;i++){String id="d"+i;records.add("declarations",Json.map("id",id,"name","Field"+i,"type","String","comment","opaque λ "+i,"order",(long)i));touches.put("ClassDescriptor\u0000"+id,List.of("name","comment"));dependencies.add("ClassDescriptor\u0000"+id);}
                records.add("classes",Json.map("id","c","descriptors",records.slice("declarations",0,100_000)));
                records.add("selected",records.list("classes").getFirst());
                for(String category:List.of("classes","selected")){var row=records.list(category).getFirst();var declarations=(List<?>)row.get("descriptors");if(declarations.size()!=100_000||!ValueCodec.map(declarations.getLast()).get("comment").equals("opaque λ 99999"))throw new AssertionError("Lost declaration fields");Json.write(java.io.OutputStream.nullOutputStream(),row);}
                records.addRevision("revisions",Json.map("revisionId","r","changes",records.slice("declarations",0,100_000),"touches",touches,"dependencies",dependencies));
                Json.write(java.io.OutputStream.nullOutputStream(),Json.map("revisions",records.revisions("revisions")));
            }
            System.out.println("bounded-source-and-revision-passed");
        }
    }
}
