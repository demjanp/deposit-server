package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.DiskJsonList;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportServiceTest {
    @TempDir Path temp;
    private record Link(String source,String target,String label) {}
    private Set<Map<String,Object>> solve(Map<String,List<String>> candidates,List<Link> links,Set<Link> receiving)throws Exception{
        try(var indexes=new ImportScratch(temp,()->{});var solutions=new DiskJsonList(indexes.directory,"solutions-")){
            for(var entry:candidates.entrySet()){indexes.update("INSERT INTO sources(id,className) VALUES(?,?)",entry.getKey(),"Thing");for(String target:entry.getValue())indexes.update("INSERT INTO candidates(source,target) VALUES(?,?)",entry.getKey(),target);}
            for(var link:links)indexes.update("INSERT INTO edges(source,target,label) VALUES(?,?,?)",link.source,link.target,link.label);
            var solver=new MappingSolver(indexes,solutions,(source,target,label)->receiving.contains(new Link(source,target,label)));solver.solve();
            var result=new LinkedHashSet<Map<String,Object>>();for(int ordinal=0;ordinal<solutions.size();ordinal++)assertTrue(result.add(new LinkedHashMap<>(indexes.solution(ordinal))),"Duplicate optimal mapping");
            for(var mapping:result)assertEquals(solver.best,mapping.values().stream().filter(Objects::isNull).count());return result;
        }
    }
    @Test void cyclicSharedAndSelfBindingsReportOnlyTwoMinimumMappings()throws Exception{
        var candidates=new LinkedHashMap<String,List<String>>();candidates.put("a",List.of("x","y"));candidates.put("b",List.of("x","y"));candidates.put("c",List.of("x","y"));
        var links=List.of(new Link("a","b","next"),new Link("b","c","next"),new Link("c","a","next"),new Link("a","a","self"));
        var receiving=Set.of(new Link("x","x","next"),new Link("y","y","next"),new Link("x","x","self"),new Link("y","y","self"));
        assertSolverMatchesOracle(candidates,links,receiving,solve(candidates,links,receiving));
        assertSolverMatchesOracle(candidates,links,Set.of(),solve(candidates,links,Set.of()));
    }
    @Test void randomizedMinimumAndAmbiguityAgreeWithIndependentExhaustiveOracle()throws Exception{
        var random=new Random(8319);
        for(int fixture=0;fixture<40;fixture++){
            int count=1+random.nextInt(5);var candidates=new LinkedHashMap<String,List<String>>();var links=new ArrayList<Link>();var receiving=new HashSet<Link>();
            for(int source=0;source<count;source++){var targets=new ArrayList<String>();for(int target=0;target<3;target++)if(random.nextBoolean())targets.add("t"+target);Collections.shuffle(targets,random);candidates.put("s"+source,targets);}
            for(int source=0;source<count;source++)for(int target=0;target<count;target++)if(random.nextInt(4)==0)links.add(new Link("s"+source,"s"+target,"edge"));
            for(int source=0;source<3;source++)for(int target=0;target<3;target++)if(random.nextBoolean())receiving.add(new Link("t"+source,"t"+target,"edge"));
            assertSolverMatchesOracle(candidates,links,receiving,solve(candidates,links,receiving));
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count(),"Import indexes leaked");}
    }
    @Test void enumerationOrderIsIndependentOfCandidateInsertionOrder()throws Exception{
        var first=new LinkedHashMap<String,List<String>>();first.put("b",List.of("z","a"));first.put("a",List.of("z","a"));
        var second=new LinkedHashMap<String,List<String>>();second.put("a",List.of("a","z"));second.put("b",List.of("a","z"));
        assertEquals(new ArrayList<>(solve(first,List.of(),Set.of())),new ArrayList<>(solve(second,List.of(),Set.of())));
    }
    @Test void candidateHeavyAmbiguityStopsAfterTwoMinimumMappingsAndCleansScratch()throws Exception{
        try(var indexes=new ImportScratch(temp,()->{});var solutions=new DiskJsonList(indexes.directory,"solutions-")){
            for(int source=0;source<8;source++){String id="s"+source;indexes.update("INSERT INTO sources(id,className) VALUES(?,?)",id,"Thing");for(int target=0;target<30;target++)indexes.update("INSERT INTO candidates(source,target) VALUES(?,?)",id,"t"+target);}
            long started=System.nanoTime();var solver=new MappingSolver(indexes,solutions,(source,target,label)->true);solver.solve();
            assertEquals(0,solver.best);assertEquals(2,solutions.size());assertEquals(0L,ValueCodec.map(solutions.get(0)).get("ordinal"));assertEquals(1L,ValueCodec.map(solutions.get(1)).get("ordinal"));
            assertEquals(8,indexes.solution(0).size());assertEquals(8,indexes.solution(1).size());
            assertEquals(16,((Number)indexes.one("SELECT count(*) AS n FROM solution_assignments").get("n")).intValue());
            assertTrue(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(3),"Ambiguous search did not stop after proving non-uniqueness");
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void expiredSharedDeadlineIsHonoredAndScratchIsCleaned()throws Exception{
        var expected=new IllegalStateException("deadline");var expired=new AtomicBoolean();
        try(var indexes=new ImportScratch(temp,()->{if(expired.get())throw expected;});var solutions=new DiskJsonList(indexes.directory,"solutions-")){
            indexes.update("INSERT INTO sources(id,className) VALUES(?,?)","s","Thing");indexes.update("INSERT INTO candidates(source,target) VALUES(?,?)","s","t");
            expired.set(true);
            assertSame(expected,assertThrows(IllegalStateException.class,()->new MappingSolver(indexes,solutions,(source,target,label)->true).solve()));
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void indexedSpoolSupportsInterleavedAppendLookupAndIteration()throws Exception{
        try(var spool=new DiskJsonList(temp,"indexed-")){
            for(int i=0;i<1000;i++)spool.add(Json.map("id",(long)i,"text","α😀".repeat(i%20)));
            assertEquals(999L,ValueCodec.map(spool.get(999)).get("id"));assertEquals(0L,ValueCodec.map(spool.getFirst()).get("id"));
            spool.add(Json.map("id",1000L));assertEquals(1000L,ValueCodec.map(spool.getLast()).get("id"));int count=0;for(Object ignored:spool)count++;assertEquals(1001,count);assertEquals(1001L,spool.longSize());
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void coalescedRowsRejectContradictoryWritesBeforeApply()throws Exception{
        var mappings=Json.map("a","target","b","target","new",null);var rules=Json.map("_tabularIds",Json.map("a","r1/specimen","b","r2/specimen"));
        try(var indexes=new ImportScratch(temp,()->{});var conflicts=new DiskJsonList(temp,"coalesced-conflicts-")){
            var consistent=Json.map("descriptors",List.of(Json.map("objectId","a","name","Value","value","same"),Json.map("objectId","b","name","Value","value","same")));
            ImportCoalescedConflicts.appendCoalescedDescriptorConflicts(consistent,mappings,rules,indexes,conflicts);assertTrue(conflicts.isEmpty());
            var conflicting=Json.map("descriptors",List.of(Json.map("objectId","a","name","Value","value","first"),Json.map("objectId","b","name","Value","value","second"),Json.map("objectId","new","name","Value","value","independent")));
            ImportCoalescedConflicts.appendCoalescedDescriptorConflicts(conflicting,mappings,rules,indexes,conflicts);assertEquals(List.of(Json.map("kind","coalescedValue","sourceIds",List.of("r1/specimen","r2/specimen"),"descriptor","Value")),conflicts);
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void sqliteScratchReservesBeforeEachWriteAndReleasesAfterDeletion()throws Exception{
        var reserved=new java.util.concurrent.atomic.AtomicLong();var directory=new java.util.concurrent.atomic.AtomicReference<Path>();var released=new AtomicBoolean();
        try(var indexes=new ImportScratch(temp,()->{},reserved::addAndGet,()->{released.set(true);assertFalse(Files.exists(directory.get()),"scratch commitment is released only after the directory is removed");})){
            directory.set(indexes.directory);var map=indexes.<String>map();map.put("source-1","target-1");map.put("source-2","target-2");
            assertTrue(reserved.get()>0,"SQLite writes must reserve their input and index allowance before execution");
            assertEquals("target-1",map.get("source-1"));
        }
        assertTrue(released.get());
    }
    @Test void deepInputUsesDiskFramesUnderConstrainedHeapAndStack()throws Exception{
        Path scratch=Files.createDirectory(temp.resolve("probe")),log=temp.resolve("probe.log"),arguments=temp.resolve("arguments.txt");
        Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx48m","-Xss256k","@"+arguments,Probe.class.getName(),scratch.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(90,TimeUnit.SECONDS),"Deep matching timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
        try(var files=Files.list(scratch)){assertEquals(0,files.count(),"Deep search leaked scratch files");}
    }
    public static class Probe {
        public static void main(String[] args)throws Exception{
            Path parent=Path.of(args[0]);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            try(var indexes=new ImportScratch(parent,()->{if(System.nanoTime()-deadline>=0)throw new IllegalStateException("deadline");});var solutions=new DiskJsonList(indexes.directory,"solutions-")){
                for(int i=0;i<12_000;i++){String id=String.format("s%05d",i);indexes.update("INSERT INTO sources(id,className) VALUES(?,?)",id,"Thing");if(i>0)indexes.update("INSERT INTO edges(source,target,label) VALUES(?,?,?)",String.format("s%05d",i-1),id,"next");}
                var solver=new MappingSolver(indexes,solutions,(source,target,label)->true);solver.solve();assertEquals(12_000,solver.best);assertEquals(1,solutions.size());assertEquals(0L,ValueCodec.map(solutions.getFirst()).get("ordinal"));assertEquals(12_000,indexes.solution(0).size());assertEquals(12_000,((Number)indexes.one("SELECT count(*) AS n FROM solution_assignments WHERE solutionOrdinal=0").get("n")).intValue());
            }
        }
    }
    private static Set<Map<String,Object>> oracle(Map<String,List<String>> candidates,List<Link> links,Set<Link> receiving){
        var all=new ArrayList<Map<String,Object>>();visit(new ArrayList<>(candidates.keySet()),0,candidates,new LinkedHashMap<>(),links,receiving,all);long minimum=all.stream().mapToLong(value->value.values().stream().filter(Objects::isNull).count()).min().orElseThrow();var result=new HashSet<Map<String,Object>>();for(var value:all)if(value.values().stream().filter(Objects::isNull).count()==minimum)result.add(value);return result;
    }
    private static void assertSolverMatchesOracle(Map<String,List<String>> candidates,List<Link> links,Set<Link> receiving,Set<Map<String,Object>> actual){
        Set<Map<String,Object>> expected=oracle(candidates,links,receiving);assertFalse(expected.isEmpty());assertTrue(actual.size()<=2);assertTrue(expected.containsAll(actual),"Solver returned a non-optimal or invalid mapping");
        long minimum=expected.stream().mapToLong(mapping->mapping.values().stream().filter(Objects::isNull).count()).min().orElseThrow();for(var mapping:actual)assertEquals(minimum,mapping.values().stream().filter(Objects::isNull).count());
        assertEquals(Math.min(2,expected.size()),actual.size(),"Solver must establish whether the minimum mapping is unique");
    }
    private static void visit(List<String> ids,int index,Map<String,List<String>> candidates,Map<String,Object> value,List<Link> links,Set<Link> receiving,List<Map<String,Object>> results){
        if(index==ids.size()){for(var link:links){String source=(String)value.get(link.source),target=(String)value.get(link.target);if(source!=null&&target!=null&&!receiving.contains(new Link(source,target,link.label)))return;}results.add(new LinkedHashMap<>(value));return;}
        String id=ids.get(index);for(String target:candidates.get(id)){value.put(id,target);visit(ids,index+1,candidates,value,links,receiving,results);}value.put(id,null);visit(ids,index+1,candidates,value,links,receiving,results);value.remove(id);
    }
}
