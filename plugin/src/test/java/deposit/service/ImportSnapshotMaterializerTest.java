package deposit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import deposit.common.Json;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportSnapshotMaterializerTest {
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
}
