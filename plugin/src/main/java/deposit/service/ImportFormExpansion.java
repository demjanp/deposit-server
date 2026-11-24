package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Builds the selected Pattern form snapshot, keeping form-specific root semantics separate from row expansion. */
final class ImportFormExpansion {
    @FunctionalInterface interface ResourceResolver { FormResource stage(String resourceId)throws IOException; }
    record FormResource(Path path,String id,String filename,String mediaType,long bytes,String checksum) {}
    record FormSnapshot(Path path,Map<String,Object> rules) {}

    private final RequestContext c;
    private final CoreRepository repo;
    private final ResourceRepository resources;
    ImportFormExpansion(RequestContext c,CoreRepository repo,ResourceRepository resources){this.c=c;this.repo=repo;this.resources=resources;}
    private Path stagedPath(String relative){try{return resources.files.path(relative);}catch(IOException e){throw new UncheckedIOException(e);}}
    private void formConflict(String kind,String message){throw ExceptionHandler.error("IMPORT_CONFLICT",message,c,false,Json.map("conflicts",List.of(Json.map("kind",kind))));}

    FormSnapshot expand(Node pattern,Map<String,Object> values,Map<String,Object> rules,ResourceResolver resourceResolver)throws IOException{
        Map<String,Object> definition=PatternCodec.normalize(Json.read((String)pattern.getProperty("definition"))),roles=ValueCodec.map(definition.get("objects"));
        for(String role:values.keySet())if(!roles.containsKey(role))c.fail("VALIDATION_FAILED","Unknown Pattern Object in values: "+role);
        var effective=new LinkedHashMap<>(rules);String rootRole=null;
        if(rules.containsKey("rootId")){
            for(var entry:roles.entrySet())if(Boolean.TRUE.equals(ValueCodec.map(entry.getValue()).get("root"))){if(rootRole!=null)formConflict("rootCount","rootId requires exactly one Pattern root");rootRole=entry.getKey();}
            if(rootRole==null||ImportSemantics.connectedRoles(rootRole,roles).size()!=roles.size())formConflict("rootConnectivity","rootId requires one connected Pattern root");
            Node root=repo.entity("Object",c.namespaceId,(String)rules.get("rootId"));if(root==null)formConflict("rootUnavailable","Root Object is not available in the active Namespace");
            var rootSpec=ValueCodec.map(roles.get(rootRole));String rootClass=(String)rootSpec.get("className");
            if(!rootClass.equals(repo.objectClass(root).getProperty("name")))formConflict("rootClass","Root Object Class differs from the Pattern root Class");
            if(Boolean.TRUE.equals(rootSpec.get("alwaysCreate"))||ImportSemantics.forcedClass(rootClass,rules))formConflict("rootAlwaysCreate","rootId conflicts with forced creation of the root");
        }
        var schema=ImportSemantics.namespaceSchema(repo,c.namespaceId);var classNames=new TreeSet<String>();
        for(Object raw:roles.values()){var role=ValueCodec.map(raw);String className=(String)role.get("className");classNames.add(className);var fields=schema.computeIfAbsent(className,k->new TreeMap<>());for(Object rd:(List<?>)role.get("descriptors")){var d=ValueCodec.map(rd);if(d.containsKey("type"))fields.putIfAbsent((String)d.get("name"),(String)d.get("type"));}}
        var classes=new ArrayList<Object>();var classIds=new TreeMap<String,String>();
        for(String className:classNames){String id=UUID.randomUUID().toString();classIds.put(className,id);var declarations=new ArrayList<Object>();for(var field:schema.get(className).entrySet())declarations.add(PortableImportRecords.classDescriptor(UUID.randomUUID().toString(),c.namespaceId,id,field.getKey(),field.getValue(),(long)declarations.size(),""));classes.add(PortableImportRecords.clazz(id,c.namespaceId,className,declarations));}
        var objects=new ArrayList<Object>();var descriptors=new ArrayList<Object>();var geometries=new ArrayList<Object>();var memberships=new ArrayList<Object>();var relationships=new ArrayList<Object>();var classRelationships=new ArrayList<Object>();var resourceRows=new ArrayList<Object>();var resourceFiles=new LinkedHashMap<String,Path>();var temporary=new ArrayList<Path>();var objectIds=new LinkedHashMap<String,String>();var seenResources=new HashSet<String>();
        Path staged=stagedPath("staging/imports/form-"+UUID.randomUUID()+".zip");
        try{
            Files.createDirectories(staged.getParent());
            for(var entry:roles.entrySet()){String roleName=entry.getKey();var role=ValueCodec.map(entry.getValue());String id=UUID.randomUUID().toString(),className=(String)role.get("className"),classId=classIds.get(className);objectIds.put(roleName,id);objects.add(PortableImportRecords.object(id,c.namespaceId,classId,List.of(0.0,0.0)));memberships.add(PortableImportRecords.membership(UUID.randomUUID().toString(),c.namespaceId,id,classId));
                Map<String,Object> supplied=values.containsKey(roleName)?ValueCodec.map(values.get(roleName)):Map.of();var allowedFields=new HashSet<String>();for(Object rawField:(List<?>)role.get("descriptors"))allowedFields.add((String)ValueCodec.map(rawField).get("name"));for(String suppliedField:supplied.keySet())if(!allowedFields.contains(suppliedField))c.fail("VALIDATION_FAILED","Form value is outside the selected Pattern: "+roleName+"."+suppliedField);
                for(var field:supplied.entrySet()){String type=schema.get(className).get(field.getKey());if(type==null)c.fail("VALIDATION_FAILED","Form value lacks a declaration: "+field.getKey());Object value=ValueCodec.normalize(type,field.getValue());String descriptorId=UUID.randomUUID().toString();var descriptor=PortableImportRecords.descriptor(descriptorId,c.namespaceId,id,field.getKey(),ExchangePackageCodec.encodeValue(type,value));
                    if("DGeometry".equals(type)&&value!=null){String geometryId=UUID.randomUUID().toString();descriptor.put("geometryId",geometryId);Map<String,Object> normalized=ValueCodec.map(value);String geometryType=(String)GeometryCodec.fromWkt(normalized.get("wkt"),normalized.get("srid"),normalized.get("verticalSrid")).get("geometryType");geometries.add(PortableImportRecords.geometry(normalized,geometryId,c.namespaceId,descriptorId,geometryType));}
                    if("DResource".equals(type)&&value!=null){String resourceId=(String)ValueCodec.map(value).get("resourceId");if(seenResources.add(resourceId)){FormResource resource=resourceResolver.stage(resourceId);temporary.add(resource.path());resourceFiles.put(resource.checksum(),resource.path());resourceRows.add(PortableImportRecords.resource(resource.id(),resource.filename(),resource.bytes(),resource.mediaType(),resource.checksum()));}}
                    descriptors.add(descriptor);
                }
            }
            var scopes=new ArrayList<Object>();var classEdges=new HashSet<String>();
            for(var entry:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(entry.getValue()).get("relationships")){var edge=ValueCodec.map(raw);String from=entry.getKey(),to=(String)edge.get("targetName"),label=(String)edge.get("label"),fromClass=(String)ValueCodec.map(entry.getValue()).get("className"),toClass=(String)ValueCodec.map(roles.get(to)).get("className");relationships.add(PortableImportRecords.relationship(UUID.randomUUID().toString(),c.namespaceId,objectIds.get(from),objectIds.get(to),label));if(classEdges.add(fromClass+"\u0000"+label+"\u0000"+toClass))classRelationships.add(PortableImportRecords.classRelationship(UUID.randomUUID().toString(),c.namespaceId,classIds.get(fromClass),classIds.get(toClass),label));if(from.equals(rootRole))scopes.add(Json.map("direction","outgoing","label",label,"className",toClass));if(to.equals(rootRole))scopes.add(Json.map("direction","incoming","label",label,"className",fromClass));}
            if(rootRole!=null){effective.put("_formRootSource",objectIds.get(rootRole));var fields=new ArrayList<Object>();for(Object raw:(List<?>)ValueCodec.map(roles.get(rootRole)).get("descriptors"))fields.add(ValueCodec.map(raw).get("name"));effective.put("_formRootDescriptors",fields);effective.put("_formRootRelationships",scopes);}
            var graph=Json.map("classes",classes,"objects",objects,"descriptors",descriptors,"geometries",geometries,"memberships",memberships,"relationships",relationships,"classRelationships",classRelationships,"patterns",List.of(Json.map("kind","Pattern","id",CoreRepository.id(pattern),"namespaceId",c.namespaceId,"name",pattern.getProperty("name"),"definition",definition,"comment",pattern.getProperty("comment",""))),"userTools",List.of(),"resources",resourceRows);
            var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",c.namespaceId,"name","Pattern form","description","","depositVersion",DepositInfo.version(),"historyMode","disabled","exportedRevision",c.revision,"createdAt",c.timestamp.toString(),"rootIds",List.of(),"scope","namespace");
            ExchangePackageCodec.write(staged,manifest,graph,null,resourceFiles);
            return new FormSnapshot(staged,effective);
        }catch(IOException|RuntimeException failure){try{Files.deleteIfExists(staged);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}finally{for(Path path:temporary)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    }
}
