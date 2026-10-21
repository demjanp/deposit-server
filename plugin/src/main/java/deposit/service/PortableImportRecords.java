package deposit.service;

import deposit.common.Json;
import java.util.*;

/** Pure constructors for the portable records shared by import expansion paths. */
final class PortableImportRecords {
    private PortableImportRecords() {}

    static Map<String,Object> classDescriptor(String id,String namespaceId,String classId,String name,String type,long order,String comment) {
        return Json.map("kind","ClassDescriptor","id",id,"namespaceId",namespaceId,"classId",classId,"name",name,"type",type,"order",order,"comment",comment);
    }

    static Map<String,Object> clazz(String id,String namespaceId,String name,List<?> descriptors) {
        return Json.map("kind","Class","id",id,"namespaceId",namespaceId,"name",name,"position",List.of(0.0,0.0),"order",0L,"comment","","descriptors",descriptors);
    }

    static Map<String,Object> object(String id,String namespaceId,String classId,List<Double> position) {
        return Json.map("kind","Object","id",id,"namespaceId",namespaceId,"classId",classId,"position",position);
    }

    static Map<String,Object> membership(String id,String namespaceId,String objectId,String classId) {
        return Json.map("kind","Membership","id",id,"namespaceId",namespaceId,"objectId",objectId,"classId",classId);
    }

    static Map<String,Object> descriptor(String id,String namespaceId,String objectId,String name,Object encodedValue) {
        return Json.map("kind","Descriptor","id",id,"namespaceId",namespaceId,"objectId",objectId,"name",name,"value",encodedValue);
    }

    static Map<String,Object> geometry(Map<String,Object> normalized,String id,String namespaceId,String descriptorId,String geometryType) {
        var geometry=new LinkedHashMap<String,Object>(normalized);
        geometry.put("kind","DGeometry");geometry.put("id",id);geometry.put("namespaceId",namespaceId);geometry.put("descriptorId",descriptorId);geometry.put("geometryType",geometryType);
        return geometry;
    }

    static Map<String,Object> relationship(String id,String namespaceId,String sourceId,String targetId,String label) {
        return Json.map("kind","Relationship","id",id,"namespaceId",namespaceId,"sourceId",sourceId,"targetId",targetId,"label",label);
    }

    static Map<String,Object> classRelationship(String id,String namespaceId,String sourceId,String targetId,String label) {
        return Json.map("kind","ClassRelationship","id",id,"namespaceId",namespaceId,"sourceId",sourceId,"targetId",targetId,"label",label);
    }

    static Map<String,Object> resource(String id,String filename,long bytes,String mediaType,String checksum) {
        return Json.map("kind","DResource","id",id,"filename",filename,"bytes",bytes,"mediaType",mediaType,"sha256",checksum,"entry","resources/"+checksum);
    }
}
