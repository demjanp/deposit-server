package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortableImportRecordsTest {
    @Test void constructorsRetainLiteralPortableFieldsAndCallerIdentities(){
        assertEquals(Json.map("kind","ClassDescriptor","id","declaration-id","namespaceId","namespace-id","classId","class-id","name","Count","type","Integer","order",1L,"comment",""),PortableImportRecords.classDescriptor("declaration-id","namespace-id","class-id","Count","Integer",1L,""));
        assertEquals(Json.map("kind","Class","id","class-id","namespaceId","namespace-id","name","Sample","position",List.of(0.0,0.0),"order",0L,"comment","","descriptors",List.of(Json.map("kind","ClassDescriptor","id","declaration-id"))),PortableImportRecords.clazz("class-id","namespace-id","Sample",List.of(Json.map("kind","ClassDescriptor","id","declaration-id"))));
        assertEquals(Json.map("kind","Object","id","object-id","namespaceId","namespace-id","classId","class-id","position",List.of(0.0,0.0)),PortableImportRecords.object("object-id","namespace-id","class-id",List.of(0.0,0.0)));
        assertEquals(Json.map("kind","Membership","id","membership-id","namespaceId","namespace-id","objectId","object-id","classId","class-id"),PortableImportRecords.membership("membership-id","namespace-id","object-id","class-id"));
        assertEquals(Json.map("kind","Descriptor","id","descriptor-id","namespaceId","namespace-id","objectId","object-id","name","Shape","value",Json.map("wkt","POINT (1 2)","srid",4326L)),PortableImportRecords.descriptor("descriptor-id","namespace-id","object-id","Shape",Json.map("wkt","POINT (1 2)","srid",4326L)));
        assertEquals(Json.map("kind","Relationship","id","relationship-id","namespaceId","namespace-id","sourceId","source-id","targetId","target-id","label","near"),PortableImportRecords.relationship("relationship-id","namespace-id","source-id","target-id","near"));
        assertEquals(Json.map("kind","ClassRelationship","id","class-edge-id","namespaceId","namespace-id","sourceId","source-class","targetId","target-class","label","near"),PortableImportRecords.classRelationship("class-edge-id","namespace-id","source-class","target-class","near"));
        assertEquals(Json.map("kind","DResource","id","resource-id","filename","photo.png","bytes",12L,"mediaType","image/png","sha256","a".repeat(64),"entry","resources/"+"a".repeat(64)),PortableImportRecords.resource("resource-id","photo.png",12L,"image/png","a".repeat(64)));
    }

    @Test void geometryConstructorCopiesNormalizedPayloadAndAppendsExplicitLinkage(){
        var normalized=new LinkedHashMap<String,Object>();normalized.put("wkt","POINT Z (1 2 3)");normalized.put("srid",4326L);normalized.put("verticalSrid",5703L);normalized.put("axisOrder","xy");normalized.put("dimensions",3L);
        var geometry=PortableImportRecords.geometry(normalized,"geometry-id","namespace-id","descriptor-id","Point");
        assertEquals(List.of("wkt","srid","verticalSrid","axisOrder","dimensions","kind","id","namespaceId","descriptorId","geometryType"),new ArrayList<>(geometry.keySet()));
        assertEquals("geometry-id",geometry.get("id"));assertEquals("descriptor-id",geometry.get("descriptorId"));assertEquals("Point",geometry.get("geometryType"));
        assertEquals(List.of("wkt","srid","verticalSrid","axisOrder","dimensions"),new ArrayList<>(normalized.keySet()));
    }
}
