package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImportSemanticsRegressionTest {
    @Test void absentRelationshipsMeansNoTypedRoleRelations() {
        Map<String,Object> one=Json.map("className","Specimen","root",true);
        assertEquals(Set.of("root"),ImportSemantics.connectedRoles("root",Map.of("root",one)));
        Map<String,Object> remaining=Json.map("className","Specimen","matchEmpty",true,"relationships",List.of());
        assertEquals(Set.of("root"),ImportSemantics.connectedRoles("root",Map.of("root",one,"other",remaining)));
    }
}