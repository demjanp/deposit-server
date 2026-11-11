package deposit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import deposit.common.Json;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;

class ImportSnapshotMaterializerTest {
    @TempDir Path temporary;
    @Test void existingTargetReachabilityUsesOnlyTypedCurrentStateResourceDescriptors() {
        var graph = Json.map(
            "classes", List.of(Json.map("id", "class-1", "descriptors", List.of(
                Json.map("name", "File", "type", "DResource"),
                Json.map("name", "Text", "type", "String")))),
            "objects", List.of(Json.map("id", "object-1", "classId", "class-1")),
            "descriptors", List.of(
                Json.map("objectId", "object-1", "name", "File", "value", Json.map("resourceId", "current-resource")),
                Json.map("objectId", "object-1", "name", "Text", "value", Json.map("resourceId", "untyped-decoy"))),
            "resources", List.of(
                Json.map("id", "current-resource"),
                Json.map("id", "history-only-resource")));

        assertEquals(Set.of("current-resource"), ImportSnapshotMaterializer.currentDescriptorResourceIds(graph));
    }

    @Test void diskBackedReachabilityAndOrderedMappingsAvoidPackageSizedHashMaps()throws IOException {
        var objects=new java.util.ArrayList<Object>();var descriptors=new java.util.ArrayList<Object>();
        for(int i=0;i<2_000;i++){String id="object-"+i;objects.add(Json.map("id",id,"classId","class-1"));descriptors.add(Json.map("objectId",id,"name","File","value",i==1_999?Json.map("resourceId","current-resource"):null));}
        var graph=Json.map("classes",List.of(Json.map("id","class-1","descriptors",List.of(Json.map("name","File","type","DResource")))),"objects",objects,"descriptors",descriptors,"resources",List.of());
        try(var scratch=new ImportScratch(temporary,()->{})){
            Set<String> reachable=ImportSnapshotMaterializer.currentDescriptorResourceIds(graph,scratch);
            assertEquals(1,reachable.size());assertEquals(Set.of("current-resource"),Set.of(reachable.iterator().next()));
            var mapping=scratch.<String>orderedMap();for(int i=1_999;i>=0;i--)mapping.put("source-"+i,"target-"+i);
            var keys=mapping.keySet().iterator();assertEquals("source-0",keys.next());assertEquals("source-1",keys.next());assertEquals(2_000,mapping.size());
        }
    }
}
