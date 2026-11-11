package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryWorkspaceTest {
    @TempDir Path temporary;
    private static Map<String,Object> image(String kind,String id,Object... fields){var result=Json.map("kind",kind,"id",id,"namespaceId","ns");for(int i=0;i<fields.length;i+=2)result.put((String)fields[i],fields[i+1]);return result;}
    private static Map<String,Map<String,Object>> fixture(){
        var result=new TreeMap<String,Map<String,Object>>();
        for(var row:List.of(image("Namespace","ns","name","N","description",""),image("Class","c","name","Specimen"),image("Object","o","position",List.of(0.0,0.0)),image("Membership","m","classId","c","objectId","o"),image("ClassDescriptor","decl","classId","c","name","Mass","type","Integer"),image("Descriptor","d","objectId","o","name","Mass","value",20L),image("Pattern","p","name","ByMass","comment","old","definition",Json.map("objects",Json.map("root",Json.map("className","Specimen","root",true,"descriptors",List.of(Json.map("name","Mass"))))))))result.put(HistoryCodec.key(row),row);
        return result;
    }
    private static Map<String,Map<String,Object>> store(HistoryWorkspace workspace,Map<String,Map<String,Object>> state){var result=workspace.newState();state.forEach(result::put);return result;}
    @Test void diskJournalMatchesCompleteImageTouchesAndDependencySemanticsAcrossCheckpoints()throws Exception{
        var state=fixture();var expected=new ChangeSet(state);var reserved=new AtomicLong();var released=new AtomicBoolean();
        try(var workspace=new HistoryWorkspace(temporary,()->{},reserved::addAndGet,()->released.set(true))){
            var actual=workspace.journal(store(workspace,state));
            state.get("Descriptor\u0000d").put("value",null);state.get("Pattern\u0000p").put("comment","new");state.put("UserTool\u0000temporary",image("UserTool","temporary","name","Tool","data","opaque"));
            expected.checkpoint(state);actual.checkpoint(store(workspace,state));
            state.get("Descriptor\u0000d").put("value",20L);state.remove("UserTool\u0000temporary");state.remove("Object\u0000o");state.remove("Membership\u0000m");state.remove("Descriptor\u0000d");
            expected.checkpoint(state);actual.checkpoint(store(workspace,state));
            assertEquals(expected.before,actual.before);assertEquals(expected.after,actual.after);assertEquals(expected.touches,actual.touches);assertEquals(expected.dependencies,actual.dependencies);assertEquals(expected.kinds,actual.kinds);
            assertTrue(actual.touches.containsKey("UserTool\u0000temporary"));assertTrue(actual.dependencies.contains("Descriptor\u0000d"));assertTrue(reserved.get()>0);
        }
        assertTrue(released.get());try(var paths=Files.list(temporary)){assertEquals(0,paths.count());}
    }
    @Test void diskSchemaDependenciesMatchClassAndDeclarationPatternReferences()throws Exception{
        var state=fixture();var expected=new ChangeSet(state);
        try(var workspace=new HistoryWorkspace(temporary,()->{},ignored->{},()->{})){
            var actual=workspace.journal(store(workspace,state));state.get("Class\u0000c").put("name","Renamed");state.get("ClassDescriptor\u0000decl").put("comment","updated");
            expected.checkpoint(state);actual.checkpoint(store(workspace,state));assertEquals(expected.touches,actual.touches);assertEquals(expected.dependencies,actual.dependencies);
        }
    }
    @Test void cancellationAndReservationFailureCloseOnlyOwnedScratch()throws Exception{
        Path unrelated=Files.writeString(temporary.resolve("keep"),"keep");var cancelled=new AtomicBoolean();var expected=new IllegalStateException("cancelled");var released=new AtomicBoolean();
        try(var workspace=new HistoryWorkspace(temporary,()->{if(cancelled.get())throw expected;},ignored->{},()->released.set(true))){var before=store(workspace,fixture());var journal=workspace.journal(before);cancelled.set(true);assertSame(expected,assertThrows(IllegalStateException.class,()->journal.checkpoint(before)));}
        assertTrue(released.get());assertEquals("keep",Files.readString(unrelated));
        var closed=new AtomicBoolean();assertThrows(IllegalStateException.class,()->new HistoryWorkspace(temporary,()->{},ignored->{throw new IllegalStateException("pressure");},()->closed.set(true)));assertTrue(closed.get());
        try(var paths=Files.list(temporary)){assertEquals(List.of(unrelated),paths.toList());}
    }
    @Test void statesLargerThanHeapAreDiffedWithoutWholeStateCopies()throws Exception{
        Path scratch=Files.createDirectory(temporary.resolve("large")),log=temporary.resolve("probe.log"),arguments=temporary.resolve("java.args");
        Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx48m","@"+arguments,Probe.class.getName(),scratch.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(120,TimeUnit.SECONDS),"History probe timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
        try(var paths=Files.list(scratch)){assertEquals(0,paths.count());}
    }
    public static final class Probe {
        public static void main(String[] args)throws Exception{
            try(var workspace=new HistoryWorkspace(Path.of(args[0]),()->{},ignored->{},()->{})){
                var before=workspace.newState();String text="x".repeat(8192);int count=8000;
                for(int i=0;i<count;i++){var row=image("UserTool",String.format("%08d",i),"name","Tool"+i,"data",text);before.put(HistoryCodec.key(row),row);}
                var journal=workspace.journal(before);var after=workspace.newState();
                for(int i=0;i<count;i++){var row=image("UserTool",String.format("%08d",i),"name","Tool"+i,"data",text+"changed");after.put(HistoryCodec.key(row),row);}
                journal.checkpoint(after);if(journal.touches.size()!=count||journal.before.size()!=count||journal.after.size()!=count)throw new AssertionError("Missing changes");
            }
        }
    }
}
