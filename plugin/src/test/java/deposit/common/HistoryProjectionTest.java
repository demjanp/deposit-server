package deposit.common;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class HistoryProjectionTest {
    @TempDir Path temp;
    private Map<String,Object> image(String kind,String id,Object... fields){var row=Json.map("kind",kind,"id",id,"namespaceId","ns");row.putAll(Json.map(fields));return row;}
    private Map<String,Object> change(Map<String,Object> image){return Json.map("entityKind",image.get("kind"),"entityId",image.get("id"),"before",null,"after",image);}
    @Test void fixedObjectsRetainDeletedDependenciesWithoutUnrelatedObjectsOrTools(){
        var rows=new ArrayList<Map<String,Object>>();
        rows.add(change(image("Namespace","ns","name","N")));
        rows.add(change(image("Class","c","name","Specimen")));
        rows.add(change(image("ClassDescriptor","decl","classId","c","name","File","type","DResource")));
        rows.add(change(image("Object","a")));rows.add(change(image("Object","b")));
        rows.add(change(image("Membership","ma","objectId","a","classId","c")));
        rows.add(change(image("Membership","mb","objectId","b","classId","c")));
        var descriptor=image("Descriptor","d","objectId","a","name","File","value",Json.map("resourceId","old-resource"));
        rows.add(change(descriptor));rows.add(change(image("UserTool","tool","data","opaque")));
        var baseline=Json.map("kind","baseline","changes",rows);
        var deletion=Json.map("changes",List.of(Json.map("entityKind","Descriptor","entityId","d","before",descriptor,"after",null)));
        var source=Json.map("baseline",baseline,"revisions",List.of(baseline,deletion));
        var result=HistoryProjection.project(source,Set.of("a"),Set.of(),false);
        assertEquals(Set.of("old-resource"),result.resourceIds());
        var revisions=(List<?>)result.history().get("revisions");
        var changes=(List<?>)ValueCodec.map(revisions.getFirst()).get("changes");
        assertTrue(changes.stream().anyMatch(raw->"d".equals(ValueCodec.map(raw).get("entityId"))));
        assertFalse(changes.stream().anyMatch(raw->Set.of("b","mb","tool").contains(ValueCodec.map(raw).get("entityId"))));
        assertEquals(1,((List<?>)ValueCodec.map(revisions.get(1)).get("changes")).size());
        assertEquals(revisions.getFirst(),result.history().get("baseline"));
    }
    @Test void wholeNamespacePreservesOpaqueHistory(){var baseline=Json.map("changes",List.of(change(image("UserTool","t","data","  not JSON"))));var source=Json.map("baseline",baseline,"revisions",List.of(baseline));assertSame(source,HistoryProjection.project(source,Set.of(),Set.of(),true).history());}
    @Test void wholeEpochsRetainClearedResourcesUsingEachRevisionsSchema()throws Exception{
        var declaration=image("ClassDescriptor","decl","classId","c","name","File","type","DResource");
        var baseline=Json.map("changes",List.of(change(image("Class","c","name","Specimen")),change(declaration),
            change(image("Object","a")),change(image("Membership","m","objectId","a","classId","c")),
            change(image("Descriptor","d","objectId","a","name","File","value",null))));
        var attached=Json.map("changes",List.of(change(image("Descriptor","d","objectId","a","name","File","value",Json.map("resourceId","intermediate-resource")))));
        var cleared=Json.map("changes",List.of(change(image("Descriptor","d","objectId","a","name","File","value",null))));
        var retyped=Json.map("changes",List.of(change(image("ClassDescriptor","decl","classId","c","name","File","type","String")),
            change(image("Descriptor","d","objectId","a","name","File","value","current text"))));
        var closed=Json.map("closed",true,"revisions",List.of(baseline,attached,cleared,retyped));
        var activeBaseline=Json.map("changes",List.of(change(image("Class","c","name","Specimen")),change(declaration),
            change(image("Object","a")),change(image("Membership","m","objectId","a","classId","c")),
            change(image("Descriptor","d","objectId","a","name","File","value",Json.map("resourceId","active-resource")))));
        var source=Json.map("epochs",List.of(closed,Json.map("closed",false,"revisions",List.of(activeBaseline))));
        var expected=Set.of("intermediate-resource","active-resource");
        var inMemory=HistoryProjection.project(source,Set.of(),Set.of(),true);
        assertSame(source,inMemory.history());assertEquals(expected,inMemory.resourceIds());
        try(var records=new ExportRecords(temp)){
            var disk=HistoryProjection.project(source,Set.of(),Set.of(),true,records);
            assertSame(source,disk.history());assertEquals(expected,disk.resourceIds());
        }
        try(var files=Files.list(temp)){assertEquals(0,files.count(),"Historical projection must release its disk indexes");}
    }
}
