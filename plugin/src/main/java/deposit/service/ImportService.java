package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.BiConsumer;
import java.util.*;
import java.util.zip.ZipFile;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.RelationshipType;

/** Fixed-source import plans and atomic application. */
public final class ImportService extends ActionService {
    private final ResourceRepository resources;
    public ImportService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.import.analyze"->analyze(request);
            case "deposit.get.importPlan"->getPlan(request);
            case "deposit.import.apply"->apply(request);
            case "deposit.import.dataByPattern"->dataByPattern(request);
            case "deposit.import.namespaceData"->cloneNamespace(request);
            default->{c.fail("VALIDATION_FAILED","Unsupported import operation");yield Map.of();}
        };
    }

    /** Expand tabular rows into the same immutable package plan used by the atomic import path. */
    public Map<String,Object> importRows(Map<String,Object> request){
        fields(request,"source","targets","relationships","rules");
        Map<String,Object> source=ValueCodec.map(request.get("source")),targets=ValueCodec.map(request.get("targets"));
        fields(source,"rows","tableId");if(source.containsKey("rows")==source.containsKey("tableId"))c.fail("VALIDATION_FAILED","Exactly one of rows or tableId is required");
        List<?> relationships=request.get("relationships")==null?List.of():request.get("relationships") instanceof List<?> list?list:null;if(relationships==null)c.fail("VALIDATION_FAILED","relationships must be a list");
        Map<String,Object> rules=request.get("rules")==null?Map.of():ValueCodec.map(request.get("rules"));validateRules(rules);
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.rows",request,false,"editor",()->{
                String tableId=null;Iterable<Map<String,Object>> rows;List<Map<String,Object>> columns=null;RowSource tableRows=null;
                if(source.containsKey("rows")){Object raw=source.get("rows");if(!(raw instanceof List<?> list))c.fail("VALIDATION_FAILED","rows must be a list");rows=inlineRows((List<?>)raw);}
                else {tableId=RequestContext.uuid(source.get("tableId"));Node table=repo.find("DepositTabularTable","id",tableId);if(table!=null&&!c.namespaceId.equals(table.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Table belongs to another Namespace");if(table==null||number(table,"expiresAt",0)<=resources.now()||(!c.actorId.equals(table.getProperty("creatorId"))&& !"owner".equals(effectiveRole(c.namespaceId))))c.fail("NOT_FOUND","Table is unavailable");if(!Boolean.TRUE.equals(table.getProperty("valid")))c.fail("VALIDATION_FAILED","Invalid staged table cannot be imported");table.setProperty("lastAccessAt",resources.now());table.setProperty("expiresAt",resources.now()+ResourceFiles.LEASE);columns=(List<Map<String,Object>>)(List<?>)Json.readList((String)table.getProperty("columnsJson"));try{tableRows=new RowSource(resources.files.path((String)table.getProperty("path")));}catch(IOException e){throw new UncheckedIOException(e);}rows=tableRows;}
                try {
                    try(TabularExpansion expansion=tabularPackage(rows,columns,targets,relationships,rules)){
                        Map<String,Object> expanded=expansion.data();Map<String,Object> manifest=ValueCodec.map(expanded.get("manifest")),graph=ValueCodec.map(expanded.get("graph"));Map<String,Object> rowIds=ValueCodec.map(expanded.get("rowIds")),clears=ValueCodec.map(expanded.get("clears"));Map<String,Object> effectiveRules=tabularRules(rules,rowIds,ValueCodec.map(expanded.get("blanks")),clears);
                        Path staged=stagedPath("staging/imports/rows-"+UUID.randomUUID()+".zip");retained[0]=staged;writeTabularPackage(staged,manifest,graph);Map<String,Object> summary=withValidatedPackage(staged,pkg->analyzeSnapshot(pkg,effectiveRules));
                        var advertised=new LinkedHashMap<String,Object>();for(var entry:rowIds.entrySet()){Object target=ValueCodec.map(summary.get("objectMappings")).get(entry.getKey());advertised.put((String)entry.getValue(),target);}
                        summary.put("rowMappings",advertised);String id=UUID.randomUUID().toString(),relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now();Node plan=c.tx.createNode(Label.label("DepositImportPlan"));plan.setProperty("id",id);plan.setProperty("namespaceId",c.namespaceId);plan.setProperty("actorId",c.actorId);plan.setProperty("sourcePackageId",tableId==null?"tabular-inline":tableId);plan.setProperty("stagingPath",relative);if(tableId!=null)plan.setProperty("tableId",tableId);plan.setProperty("rowIdsJson",encode(rowIds));plan.setProperty("targetRevision",repo.sequence(c.namespaceId));plan.setProperty("createdAt",now);plan.setProperty("lastActivity",now);plan.setProperty("expiresAt",now+ResourceFiles.LEASE);plan.setProperty("state","ready");plan.setProperty("rules",Json.write(effectiveRules));plan.setProperty("summary",Json.write(summary));
                        return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"),"rowMappings",advertised)),false);
                    }
                } catch(IOException e){throw new UncheckedIOException(e);} finally {if(tableRows!=null)try{tableRows.close();}catch(IOException e){throw new UncheckedIOException(e);}}
            });
            if(c.replayed){if(retained[0]!=null)Files.deleteIfExists(retained[0]);}else retained[0]=null;return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage tabular import");}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to build tabular import plan");}
        finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }

    private Iterable<Map<String,Object>> inlineRows(List<?> input){return ()->new Iterator<>(){final Iterator<?> source=input.iterator();public boolean hasNext(){return source.hasNext();}public Map<String,Object> next(){c.state.kernel.assertOpen();Map<String,Object> row=ValueCodec.map(source.next());fields(row,"id","values");Map<String,Object> values=ValueCodec.map(row.get("values"));var presence=new LinkedHashMap<String,Object>();values.keySet().forEach(k->presence.put(k,true));return Json.map("rowId",row.get("id"),"values",values,"presence",presence);}};}
    private final class RowSource implements Iterable<Map<String,Object>>,AutoCloseable {
        private final Json.ArrayReader reader;
        RowSource(Path path)throws IOException {reader=new Json.ArrayReader(path,"rows");}
        public Iterator<Map<String,Object>> iterator(){return new Iterator<>(){Map<String,Object> next=read();private Map<String,Object> read(){try{return reader.next();}catch(IOException e){throw new UncheckedIOException(e);}}public boolean hasNext(){return next!=null;}public Map<String,Object> next(){if(next==null)throw new NoSuchElementException();Map<String,Object> raw=next;next=read();return Json.map("rowId",raw.get("rowId"),"values",ValueCodec.map(raw.get("values")),"presence",ValueCodec.map(raw.get("presence")));}};}
        public void close()throws IOException{reader.close();}
    }
    private void writeTabularPackage(Path destination,Map<String,Object> manifest,Map<String,Object> graph){try{Files.createDirectories(destination.getParent());ExchangePackageCodec.write(destination,manifest,graph,null,Map.of());}catch(IOException e){throw new UncheckedIOException(e);}}

    private static final class TabularExpansion implements AutoCloseable {
        private final Map<String,Object> data;private final List<DiskJsonList> spools;private final ImportScratch indexes;
        TabularExpansion(Map<String,Object> data,List<DiskJsonList> spools,ImportScratch indexes){this.data=data;this.spools=spools;this.indexes=indexes;}
        Map<String,Object> data(){return data;}
        public void close(){for(DiskJsonList spool:spools)try{spool.close();}catch(IOException ignored){}try{indexes.close();}catch(IOException ignored){}}
    }
    private TabularExpansion tabularPackage(Iterable<Map<String,Object>> rows,List<Map<String,Object>> columns,Map<String,Object> rawTargets,List<?> rawRelationships,Map<String,Object> rules)throws IOException{
        var spools=new ArrayList<DiskJsonList>();ImportScratch indexes=scratch();
        try {
        var targets=new LinkedHashMap<String,Map<String,Object>>();var templates=new TreeMap<String,String>();var schemaByClass=new TreeMap<String,TreeMap<String,String>>();var sourceTypes=new HashMap<String,String>();if(columns!=null)for(Map<String,Object> column:columns)sourceTypes.put((String)column.get("name"),ValueCodec.type(column.get("type")));
        for(var entry:rawTargets.entrySet()){
            String column=entry.getKey();Map<String,Object> spec=ValueCodec.map(entry.getValue());fields(spec,"object","className","descriptor","type","conversion","srid","verticalSrid");String template=string(spec.get("object")),className=BaseService.name(spec.get("className")),descriptor=BaseService.name(spec.get("descriptor"));if(template.isBlank()||template.contains("/")||className.isBlank()||descriptor.isBlank())c.fail("VALIDATION_FAILED","Target template, Class, and Descriptor names must be nonempty; templates cannot contain '/'");
            String oldTemplate=templates.putIfAbsent(template,className);if(oldTemplate!=null&&!oldTemplate.equals(className))c.fail("VALIDATION_FAILED","One Object template cannot map to multiple Classes");Node clazz=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",className));Node declaration=clazz==null?null:repo.declaration(clazz,descriptor);Object declared=spec.get("type");String type=declared==null?(declaration==null?null:(String)declaration.getProperty("type")):ValueCodec.type(declared);if(type==null)c.fail("VALIDATION_FAILED","A new Descriptor target requires an explicit type");if(declaration!=null&&!type.equals(declaration.getProperty("type")))c.fail("TYPE_CONFLICT","Target type differs from its receiving declaration");
            String key=className+"\u0000"+descriptor;String prior=schemaByClass.computeIfAbsent(className,k->new TreeMap<>()).putIfAbsent(descriptor,type);if(prior!=null&&!prior.equals(type))c.fail("TYPE_CONFLICT","Targets disagree on a Class Descriptor type");String conversion=string(spec.getOrDefault("conversion","identity"));if(!Set.of("identity","integerToFloat","floatToIntegerExact").contains(conversion))c.fail("VALIDATION_FAILED","Unsupported tabular conversion");if(conversion.equals("integerToFloat")&&!type.equals("Float")||conversion.equals("floatToIntegerExact")&&!type.equals("Integer"))c.fail("TYPE_CONFLICT","Conversion does not match its target type");spec.put("object",template);spec.put("className",className);spec.put("descriptor",descriptor);spec.put("type",type);spec.put("conversion",conversion);targets.put(column,spec);
        }
        if(columns!=null)for(String name:sourceTypes.keySet())if(!targets.containsKey(name))c.fail("VALIDATION_FAILED","Every staged table column must have an explicit target");
        for(Object raw:rawRelationships){Map<String,Object> edge=ValueCodec.map(raw);fields(edge,"source","label","target","weight");String source=string(edge.get("source")),target=string(edge.get("target")),label=BaseService.name(edge.get("label"));if(!templates.containsKey(source)||!templates.containsKey(target)||label.isBlank())c.fail("VALIDATION_FAILED","Relationship templates and label must be declared by targets");edge.put("label",label);}
        var classIds=new TreeMap<String,String>();var classes=new ArrayList<Map<String,Object>>();for(var classEntry:schemaByClass.entrySet()){String classId=UUID.randomUUID().toString();classIds.put(classEntry.getKey(),classId);var decls=new ArrayList<Map<String,Object>>();for(var decl:classEntry.getValue().entrySet())decls.add(Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"classId",classId,"name",decl.getKey(),"type",decl.getValue(),"order",(long)decls.size(),"comment",""));classes.add(Json.map("kind","Class","id",classId,"namespaceId",c.namespaceId,"name",classEntry.getKey(),"position",List.of(0.0,0.0),"order",0L,"comment","","descriptors",decls));}
        Path spoolRoot=stagedPath("staging/imports");var objects=new DiskJsonList(spoolRoot,"objects-");spools.add(objects);var memberships=new DiskJsonList(spoolRoot,"memberships-");spools.add(memberships);var descriptors=new DiskJsonList(spoolRoot,"descriptors-");spools.add(descriptors);var geometries=new DiskJsonList(spoolRoot,"geometries-");spools.add(geometries);var relationships=new DiskJsonList(spoolRoot,"relationships-");spools.add(relationships);Map<String,Object> rowIds=indexes.map(),clearByObject=indexes.map(),blankByObject=indexes.map(),seenRows=indexes.map();
        for(Map<String,Object> row:rows){indexes.check();String rowId=string(row.get("rowId"));if(rowId.isBlank()||rowId.contains("/")||seenRows.putIfAbsent(rowId,true)!=null)c.fail("VALIDATION_FAILED","Row IDs must be unique, nonempty, and must not contain '/'");Map<String,Object> values=ValueCodec.map(row.get("values")),presence=ValueCodec.map(row.get("presence"));for(String name:values.keySet())if(!targets.containsKey(name))c.fail("VALIDATION_FAILED","Supplied column has no target: "+name);
            var objectsForRow=new HashMap<String,String>();for(var template:templates.entrySet()){String inputId=UUID.randomUUID().toString(),classId=classIds.get(template.getValue());objectsForRow.put(template.getKey(),inputId);rowIds.put(inputId,rowId+"/"+template.getKey());objects.add(Json.map("kind","Object","id",inputId,"namespaceId",c.namespaceId,"classId",classId,"position",List.of(0.0,0.0)));memberships.add(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"objectId",inputId,"classId",classId));}
            var suppliedByTemplate=new HashMap<String,Set<String>>();for(var target:targets.entrySet()){String column=target.getKey();Map<String,Object> spec=target.getValue();String template=(String)spec.get("object"),field=(String)spec.get("descriptor");boolean present=Boolean.TRUE.equals(presence.get(column))&&values.containsKey(column);Object rawValue=values.get(column);if(!present||rawValue==null||rawValue instanceof String s&&s.isEmpty()){String objectId=objectsForRow.get(template);var names=new ArrayList<Object>((List<?>)blankByObject.getOrDefault(objectId,List.of()));if(!names.contains(field))names.add(field);blankByObject.put(objectId,names);continue;}suppliedByTemplate.computeIfAbsent(template,k->new HashSet<>()).add(field);String type=(String)spec.get("type"),conversion=(String)spec.get("conversion");Object value=convertTabularValue(type,conversion,rawValue,sourceTypes.get(column),spec);if("DResource".equals(type))c.fail("VALIDATION_FAILED","Tabular DResource import requires a resolved same-Namespace resource reference");String objectId=objectsForRow.get(template),descriptorId=UUID.randomUUID().toString();var descriptor=Json.map("kind","Descriptor","id",descriptorId,"namespaceId",c.namespaceId,"objectId",objectId,"name",field,"value",ExchangePackageCodec.encodeValue(type,value));if("DGeometry".equals(type)&&value!=null){String geometryId=UUID.randomUUID().toString();descriptor.put("geometryId",geometryId);var geometry=new LinkedHashMap<>(ValueCodec.map(value));geometry.put("kind","DGeometry");geometry.put("id",geometryId);geometry.put("namespaceId",c.namespaceId);geometry.put("descriptorId",descriptorId);geometry.put("geometryType",GeometryCodec.fromWkt(ValueCodec.map(value).get("wkt"),ValueCodec.map(value).get("srid"),ValueCodec.map(value).get("verticalSrid")).get("geometryType"));geometries.add(geometry);}descriptors.add(descriptor);}
            for(Object raw:rawRelationships){Map<String,Object> edge=ValueCodec.map(raw);Map<String,Object> relation=Json.map("kind","Relationship","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"sourceId",objectsForRow.get(edge.get("source")),"targetId",objectsForRow.get(edge.get("target")),"label",edge.get("label"));if(edge.get("weight") instanceof Number weight)relation.put("weight",weight.doubleValue());relationships.add(relation);}
        }
        var classRelationships=new ArrayList<Map<String,Object>>();var classEdges=new HashSet<String>();for(Object raw:rawRelationships){Map<String,Object> edge=ValueCodec.map(raw);String sourceClass=templates.get(edge.get("source")),targetClass=templates.get(edge.get("target")),label=(String)edge.get("label"),key=sourceClass+"\u0000"+targetClass+"\u0000"+label;if(classEdges.add(key))classRelationships.add(Json.map("kind","ClassRelationship","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"sourceId",classIds.get(sourceClass),"targetId",classIds.get(targetClass),"label",label));}
        var graph=Json.map("classes",classes,"objects",objects,"descriptors",descriptors,"geometries",geometries,"memberships",memberships,"relationships",relationships,"classRelationships",classRelationships,"patterns",List.of(),"userTools",List.of(),"resources",List.of());var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",c.namespaceId,"name","Tabular import","description","","depositVersion",DepositInfo.version(),"historyMode","disabled","exportedRevision",repo.sequence(c.namespaceId),"createdAt",c.timestamp.toString(),"rootIds",List.of(),"scope","namespace","files",Map.of(),"includeHistory",false);return new TabularExpansion(Json.map("manifest",manifest,"graph",graph,"rowIds",rowIds,"clears",clearByObject,"blanks",blankByObject),spools,indexes);
        } catch(RuntimeException|IOException error){for(DiskJsonList spool:spools)try{spool.close();}catch(IOException ignored){}try{indexes.close();}catch(IOException suppressed){error.addSuppressed(suppressed);}throw error;}
    }
    private Object convertTabularValue(String targetType,String conversion,Object raw,String sourceType,Map<String,Object> spec){
        try{Object value=raw;if(conversion.equals("integerToFloat")){if(!"Integer".equals(sourceType)&&sourceType!=null)c.fail("TYPE_CONFLICT","integerToFloat requires an Integer source column");long n=value instanceof Number number?number.longValue():Long.parseLong(value.toString());return (double)n;}
            if(conversion.equals("floatToIntegerExact")){if(!"Float".equals(sourceType)&&sourceType!=null)c.fail("TYPE_CONFLICT","floatToIntegerExact requires a Float source column");double d=value instanceof Number number?number.doubleValue():Double.parseDouble(value.toString());if(!Double.isFinite(d))throw new ArithmeticException();return java.math.BigDecimal.valueOf(d).longValueExact();}
            if(raw instanceof String s&&!"String".equals(targetType)){value=switch(targetType){case "Integer"->Long.parseLong(s);case "Float"->{double d=Double.parseDouble(s);if(!Double.isFinite(d))throw new IllegalArgumentException();yield d;}case "Boolean"->{if(!Set.of("true","false").contains(s))throw new IllegalArgumentException();yield Boolean.valueOf(s);}case "List","DDict"->Json.read(s);case "Point"->{Object srid=spec.get("srid");if(!(srid instanceof Number))throw new IllegalArgumentException("Point mapping requires srid");Map<String,Object> geometry=TabularService.parseGeometryValue(s,srid,spec.get("verticalSrid"));if(!"Point".equals(geometry.get("geometryType")))throw new IllegalArgumentException("Point value must be a POINT");yield Json.map("srid",((Number)srid).longValue(),"coordinates",geometry.get("coordinates"));}case "DGeometry"->TabularService.parseGeometryValue(s,spec.get("srid"),spec.get("verticalSrid"));default->s;};}
            if("DGeometry".equals(targetType)&&raw instanceof Map<?,?> geometry&&sourceType!=null&&sourceType.equals("DGeometry")){Map<String,Object> supplied=ValueCodec.map(raw);if(spec.get("srid") instanceof Number n&&!Objects.equals(n.longValue(),supplied.get("srid")))c.fail("VALIDATION_FAILED","Geometry SRID conflicts with mapping");if(spec.get("verticalSrid") instanceof Number n&&!Objects.equals(n.longValue(),supplied.get("verticalSrid")))c.fail("VALIDATION_FAILED","Geometry vertical SRID conflicts with mapping");}
            return ValueCodec.normalize(targetType,value);
        }catch(ExceptionHandler.DepositException e){throw e;}catch(RuntimeException e){c.fail("VALIDATION_FAILED","Row value cannot be converted to "+targetType);return null;}
    }

    /** Form input resolves a stored Pattern and becomes the same immutable analyze/apply plan. */
    private Map<String,Object> dataByPattern(Map<String,Object> request){
        fields(request,"pattern","values","rules");Map<String,Object> rules=request.get("rules")==null?Map.of():ValueCodec.map(request.get("rules"));validateRules(rules,true);
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.dataByPattern",request,false,"editor",()->{
                Object selector=request.get("pattern");Node pattern;
                if(selector instanceof Map<?,?>){var reference=ValueCodec.map(selector);fields(reference,"id");pattern=repo.entity("Pattern",c.namespaceId,RequestContext.uuid(reference.get("id")));}
                else pattern=repo.find("Pattern",Map.of("namespaceId",c.namespaceId,"name",BaseService.name(selector)));
                if(pattern==null)c.fail("NOT_FOUND","Pattern not found");
                Map<String,Object> definition=PatternCodec.normalize(Json.read((String)pattern.getProperty("definition"))),roles=ValueCodec.map(definition.get("objects")),values=ValueCodec.map(request.get("values"));
                for(String role:values.keySet())if(!roles.containsKey(role))c.fail("VALIDATION_FAILED","Unknown Pattern Object in values: "+role);
                var effective=new LinkedHashMap<>(rules);String rootRole=null;
                if(rules.containsKey("rootId")){
                    for(var entry:roles.entrySet())if(Boolean.TRUE.equals(ValueCodec.map(entry.getValue()).get("root"))){if(rootRole!=null)formConflict("rootCount","rootId requires exactly one Pattern root");rootRole=entry.getKey();}
                    if(rootRole==null||connectedRoles(rootRole,roles).size()!=roles.size())formConflict("rootConnectivity","rootId requires one connected Pattern root");
                    Node root=repo.entity("Object",c.namespaceId,(String)rules.get("rootId"));if(root==null)formConflict("rootUnavailable","Root Object is not available in the active Namespace");
                    var rootSpec=ValueCodec.map(roles.get(rootRole));String rootClass=(String)rootSpec.get("className");
                    if(!rootClass.equals(repo.objectClass(root).getProperty("name")))formConflict("rootClass","Root Object Class differs from the Pattern root Class");
                    if(Boolean.TRUE.equals(rootSpec.get("alwaysCreate"))||forcedClass(rootClass,rules))formConflict("rootAlwaysCreate","rootId conflicts with forced creation of the root");
                }
                var schema=namespaceSchema(c.namespaceId);var classNames=new TreeSet<String>();
                for(Object raw:roles.values()){var role=ValueCodec.map(raw);String className=(String)role.get("className");classNames.add(className);var fields=schema.computeIfAbsent(className,k->new TreeMap<>());for(Object rd:(List<?>)role.get("descriptors")){var d=ValueCodec.map(rd);if(d.containsKey("type"))fields.putIfAbsent((String)d.get("name"),(String)d.get("type"));}}
                var classes=new ArrayList<Object>();var classIds=new TreeMap<String,String>();
                for(String className:classNames){String id=UUID.randomUUID().toString();classIds.put(className,id);var declarations=new ArrayList<Object>();for(var field:schema.get(className).entrySet())declarations.add(Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"classId",id,"name",field.getKey(),"type",field.getValue(),"order",(long)declarations.size(),"comment",""));classes.add(Json.map("kind","Class","id",id,"namespaceId",c.namespaceId,"name",className,"position",List.of(0.0,0.0),"order",0L,"comment","","descriptors",declarations));}
                var objects=new ArrayList<Object>();var descriptors=new ArrayList<Object>();var geometries=new ArrayList<Object>();var memberships=new ArrayList<Object>();var relationships=new ArrayList<Object>();var classRelationships=new ArrayList<Object>();var resourceRows=new ArrayList<Object>();var resourceFiles=new LinkedHashMap<String,Path>();var temporary=new ArrayList<Path>();var objectIds=new LinkedHashMap<String,String>();var seenResources=new HashSet<String>();
                Path staged=stagedPath("staging/imports/form-"+UUID.randomUUID()+".zip");retained[0]=staged;
                try{
                    Files.createDirectories(staged.getParent());
                    for(var entry:roles.entrySet()){String roleName=entry.getKey();var role=ValueCodec.map(entry.getValue());String id=UUID.randomUUID().toString(),className=(String)role.get("className"),classId=classIds.get(className);objectIds.put(roleName,id);objects.add(Json.map("kind","Object","id",id,"namespaceId",c.namespaceId,"classId",classId,"position",List.of(0.0,0.0)));memberships.add(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"objectId",id,"classId",classId));
                        Map<String,Object> supplied=values.containsKey(roleName)?ValueCodec.map(values.get(roleName)):Map.of();var allowedFields=new HashSet<String>();for(Object rawField:(List<?>)role.get("descriptors"))allowedFields.add((String)ValueCodec.map(rawField).get("name"));for(String suppliedField:supplied.keySet())if(!allowedFields.contains(suppliedField))c.fail("VALIDATION_FAILED","Form value is outside the selected Pattern: "+roleName+"."+suppliedField);
                        for(var field:supplied.entrySet()){String type=schema.get(className).get(field.getKey());if(type==null)c.fail("VALIDATION_FAILED","Form value lacks a declaration: "+field.getKey());Object value=ValueCodec.normalize(type,field.getValue());String descriptorId=UUID.randomUUID().toString();var descriptor=Json.map("kind","Descriptor","id",descriptorId,"namespaceId",c.namespaceId,"objectId",id,"name",field.getKey(),"value",ExchangePackageCodec.encodeValue(type,value));
                            if("DGeometry".equals(type)&&value!=null){String geometryId=UUID.randomUUID().toString();descriptor.put("geometryId",geometryId);var geometry=new LinkedHashMap<>(ValueCodec.map(value));geometry.put("kind","DGeometry");geometry.put("id",geometryId);geometry.put("namespaceId",c.namespaceId);geometry.put("descriptorId",descriptorId);geometry.put("geometryType",GeometryCodec.fromWkt(ValueCodec.map(value).get("wkt"),ValueCodec.map(value).get("srid"),ValueCodec.map(value).get("verticalSrid")).get("geometryType"));geometries.add(geometry);}
                            if("DResource".equals(type)&&value!=null){String resourceId=(String)ValueCodec.map(value).get("resourceId");resources.lock(resourceId);Node resource=resources.resource(c.namespaceId,resourceId);if(resource==null)c.fail("NOT_FOUND","Form resource not found");if(!resources.accessible(resource,c.actorId,effectiveRole(c.namespaceId),false))c.fail("FORBIDDEN","Form resource access required");if(seenResources.add(resourceId)){var content=resources.verify(resources.location(resourceId));Path copy=stagedPath("staging/imports/form-content-"+UUID.randomUUID());temporary.add(copy);try(var input=resources.storage.open(content,0,content.bytes())){Files.copy(input,copy);}resourceFiles.put(content.checksum(),copy);resourceRows.add(Json.map("kind","DResource","id",resourceId,"filename",resource.getProperty("filename"),"bytes",content.bytes(),"mediaType",resource.getProperty("mediaType"),"sha256",content.checksum(),"entry","resources/"+content.checksum()));}}
                            descriptors.add(descriptor);
                        }
                    }
                    var scopes=new ArrayList<Object>();var classEdges=new HashSet<String>();
                    for(var entry:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(entry.getValue()).get("relationships")){var edge=ValueCodec.map(raw);String from=entry.getKey(),to=(String)edge.get("targetName"),label=(String)edge.get("label"),fromClass=(String)ValueCodec.map(entry.getValue()).get("className"),toClass=(String)ValueCodec.map(roles.get(to)).get("className");relationships.add(Json.map("kind","Relationship","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"sourceId",objectIds.get(from),"targetId",objectIds.get(to),"label",label));if(classEdges.add(fromClass+"\u0000"+label+"\u0000"+toClass))classRelationships.add(Json.map("kind","ClassRelationship","id",UUID.randomUUID().toString(),"namespaceId",c.namespaceId,"sourceId",classIds.get(fromClass),"targetId",classIds.get(toClass),"label",label));if(from.equals(rootRole))scopes.add(Json.map("direction","outgoing","label",label,"className",toClass));if(to.equals(rootRole))scopes.add(Json.map("direction","incoming","label",label,"className",fromClass));}
                    if(rootRole!=null){effective.put("_formRootSource",objectIds.get(rootRole));var fields=new ArrayList<Object>();for(Object raw:(List<?>)ValueCodec.map(roles.get(rootRole)).get("descriptors"))fields.add(ValueCodec.map(raw).get("name"));effective.put("_formRootDescriptors",fields);effective.put("_formRootRelationships",scopes);}
                    var graph=Json.map("classes",classes,"objects",objects,"descriptors",descriptors,"geometries",geometries,"memberships",memberships,"relationships",relationships,"classRelationships",classRelationships,"patterns",List.of(Json.map("kind","Pattern","id",CoreRepository.id(pattern),"namespaceId",c.namespaceId,"name",pattern.getProperty("name"),"definition",definition,"comment",pattern.getProperty("comment",""))),"userTools",List.of(),"resources",resourceRows);
                    var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",c.namespaceId,"name","Pattern form","description","","depositVersion",DepositInfo.version(),"historyMode","disabled","exportedRevision",repo.sequence(c.namespaceId),"createdAt",c.timestamp.toString(),"rootIds",List.of(),"scope","namespace");
                    ExchangePackageCodec.write(staged,manifest,graph,null,resourceFiles);Map<String,Object> summary=withValidatedPackage(staged,pkg->analyzeSnapshot(pkg,effective));
                    String id=UUID.randomUUID().toString();long now=resources.now();Node plan=c.tx.createNode(Label.label("DepositImportPlan"));plan.setProperty("id",id);plan.setProperty("namespaceId",c.namespaceId);plan.setProperty("actorId",c.actorId);plan.setProperty("sourcePackageId","pattern-form");plan.setProperty("stagingPath",c.server.storage.files.root.relativize(staged).toString().replace('\\','/'));plan.setProperty("targetRevision",repo.sequence(c.namespaceId));plan.setProperty("createdAt",now);plan.setProperty("lastActivity",now);plan.setProperty("expiresAt",now+ResourceFiles.LEASE);plan.setProperty("state","ready");plan.setProperty("rules",Json.write(effective));plan.setProperty("summary",Json.write(summary));return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"))),false);
                }catch(IOException e){throw new UncheckedIOException(e);}finally{for(Path path:temporary)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
            });
            if(c.replayed){if(retained[0]!=null)Files.deleteIfExists(retained[0]);}else retained[0]=null;return outcome.data();
        }catch(IOException|UncheckedIOException error){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage Pattern form");}finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }
    private void formConflict(String kind,String message){throw ExceptionHandler.error("IMPORT_CONFLICT",message,c,false,Json.map("conflicts",List.of(Json.map("kind",kind))));}
    private Set<String> connectedRoles(String root,Map<String,Object> roles){var visited=new HashSet<String>();var queue=new ArrayDeque<String>();visited.add(root);queue.add(root);while(!queue.isEmpty()){String role=queue.remove();for(var entry:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(entry.getValue()).get("relationships")){String target=(String)ValueCodec.map(raw).get("targetName"),next=entry.getKey().equals(role)?target:target.equals(role)?entry.getKey():null;if(next!=null&&visited.add(next))queue.add(next);}}return visited;}
    private TreeMap<String,Map<String,String>> namespaceSchema(String namespace){var schema=new TreeMap<String,Map<String,String>>();for(Node clazz:repo.nodes("Class",Map.of("namespaceId",namespace))){var fields=new TreeMap<String,String>();for(Node d:repo.children(clazz,"ClassDescriptor"))fields.put((String)d.getProperty("name"),(String)d.getProperty("type"));schema.put((String)clazz.getProperty("name"),fields);}return schema;}

    private Map<String,Object> analyze(Map<String,Object> request){
        fields(request,"source","rules");Map<String,Object> rules=request.get("rules") instanceof Map<?,?>?ValueCodec.map(request.get("rules")):Map.of();
        validateRules(rules);c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.analyze",request,false,"editor",()->{
                Map<String,Object> src=ValueCodec.map(request.get("source"));fields(src,"packageResourceId");String packageId=RequestContext.uuid(src.get("packageResourceId"));
                Path staged=copyAuthorizedPackage(packageId);retained[0]=staged;
                Map<String,Object> summary=withValidatedPackage(staged,pkg->{if(!"namespace".equals(pkg.manifest().get("scope")))throw ExceptionHandler.fail("VALIDATION_FAILED","Import source must be a complete validated snapshot");return analyzeSnapshot(pkg,rules);});
                String id=UUID.randomUUID().toString(),relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now();
                Node plan=c.tx.createNode(Label.label("DepositImportPlan"));plan.setProperty("id",id);plan.setProperty("namespaceId",c.namespaceId);plan.setProperty("actorId",c.actorId);plan.setProperty("sourcePackageId",packageId);plan.setProperty("stagingPath",relative);plan.setProperty("targetRevision",repo.sequence(c.namespaceId));plan.setProperty("createdAt",now);plan.setProperty("lastActivity",now);plan.setProperty("expiresAt",now+24L*60*60*1000);plan.setProperty("state","ready");plan.setProperty("rules",Json.write(rules));plan.setProperty("summary",Json.write(summary));
                return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"))),false);
            });
            retained[0]=null;return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage or validate import package");}
        finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }

    private Map<String,Object> getPlan(Map<String,Object> request){
        fields(request,"planId");scoped("viewer");String id=RequestContext.uuid(request.get("planId"));repo.lockNamespace(c.namespaceId);Node plan=repo.find("DepositImportPlan","id",id);if(plan!=null&&!c.namespaceId.equals(plan.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Import plan belongs to another Namespace");if(plan==null||number(plan,"expiresAt",0)<=resources.now())c.fail("NOT_FOUND","Import plan is unavailable");if(!c.actorId.equals(plan.getProperty("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("NOT_FOUND","Import plan is unavailable");plan.setProperty("lastActivity",resources.now());plan.setProperty("expiresAt",resources.now()+24L*60*60*1000);return planData(plan);
    }

    private Map<String,Object> apply(Map<String,Object> request){
        fields(request,"planId");String id=RequestContext.uuid(request.get("planId"));
        // The staged plan owns its independent target revision; session refresh cannot replace it.
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);c.historyKind="import";
        return action("deposit.import.apply",request,false,"editor",()->{
            repo.lockNamespace(c.namespaceId);Node plan=repo.find("DepositImportPlan","id",id);if(plan!=null&&!c.namespaceId.equals(plan.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Import plan belongs to another Namespace");
            if(plan==null||number(plan,"expiresAt",0)<=resources.now()||!"ready".equals(plan.getProperty("state")))c.fail("NOT_FOUND","Unapplied import plan is unavailable");
            if(!c.actorId.equals(plan.getProperty("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("FORBIDDEN","Import plan belongs to another actor");
            if(repo.sequence(c.namespaceId)!=number(plan,"targetRevision",-1))c.fail("PLAN_STALE","Receiving Namespace changed after import analysis");
            Path path=stagedPath((String)plan.getProperty("stagingPath"));Map<String,Object> rules=Json.read((String)plan.getProperty("rules"));String savedSummary=(String)plan.getProperty("summary");
            Map<String,Object> result=withValidatedPackage(path,pkg->{
                try(var indexes=scratch();var candidates=new DiskJsonList(indexes.directory,"candidates-");var conflictRows=new DiskJsonList(indexes.directory,"conflicts-")){
                Map<String,Object> resolved=applicationSummary(savedSummary,candidates,conflictRows,indexes::check);
                List<?> conflicts=(List<?>)resolved.get("conflicts");if(!conflicts.isEmpty())c.fail("IMPORT_CONFLICT","Import has unresolved analysis conflicts");
                Map<String,Object> applied=applySnapshot(pkg,rules,resolved,path);
                if(plan.hasProperty("rowIdsJson")){Map<String,Object> objectIds=ValueCodec.map(ValueCodec.map(applied.get("identityMappings")).get("Object"));var rowMappings=new LinkedHashMap<String,Object>();jsonEntries((String)plan.getProperty("rowIdsJson"),(internal,temp)->{c.state.kernel.assertOpen();rowMappings.put((String)temp,objectIds.get(internal));});applied.put("rowMappings",rowMappings);}
                return applied;
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            plan.setProperty("state","applied");plan.setProperty("lastActivity",resources.now());plan.setProperty("expiresAt",resources.now()+24L*60*60*1000);
            return new Change(new Outcome(null,null,result),true);
        }).data();
    }

    private Map<String,Object> cloneNamespace(Map<String,Object> request){
        fields(request,"packageResourceId");String id=RequestContext.uuid(request.get("packageResourceId"));
        return action("deposit.import.namespaceData",request,true,"viewer",()->{
            Path path=copyAuthorizedPackage(id);try{
                ExchangePackageCodec.PackageData pkg=validate(path);Map<String,Object> manifest=pkg.manifest();if(!"namespace".equals(manifest.get("scope")))c.fail("VALIDATION_FAILED","Namespace cloning requires a full-Namespace package");
                if(repo.find("Namespace","name",manifest.get("name"))!=null)c.fail("DUPLICATE","Namespace name already exists");
                Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",manifest.get("name"),"description",manifest.get("description"),"depositVersion",DepositInfo.version(),"historyMode",manifest.get("historyMode"),"sequence",0L));String nsId=CoreRepository.id(ns);repo.setRole(c.actorId,nsId,"owner");
                Map<String,Object> result=materialize(pkg,path,nsId,Map.of());
                if("persistent".equals(manifest.get("historyMode"))){var resourceMap=new HashMap<String,String>();ValueCodec.map(result.get("resourceMapping")).forEach((source,target)->{if(target instanceof String mappedId)resourceMap.put(source,mappedId);});new HistoryCloneService(c.tx,repo,nsId,c.actorId,c.server.runId,ValueCodec.map(result.get("identityMappings")),resourceMap).importHistory(pkg.history());ns.setProperty("sequence",((Number)manifest.get("exportedRevision")).longValue());}
                else if("transient".equals(manifest.get("historyMode")))new HistoryRepository(c.tx,repo).baseline(nsId,c.actorId,0,c.server.runId,c.timestamp);
                result.put("namespaceId",nsId);result.put("name",manifest.get("name"));return new Change(new Outcome(ns,null,result),true);
            }finally{try{Files.deleteIfExists(path);}catch(IOException ignored){}}
        }).data();
    }

    private Map<String,Object> analyzeSnapshot(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules){
        long deadline=System.nanoTime()+30_000_000_000L;
        try(var indexes=new ImportScratch(stagedPath("staging/imports"),()->{c.state.kernel.assertOpen();if(System.nanoTime()-deadline>=0)c.fail("LIMIT_EXCEEDED","Import matching exceeded its fixed time limit");});var solutions=new DiskJsonList(indexes.directory,"optimal-")){
        Map<String,Object> graph=pkg.graph();
        var conflicts=new ArrayList<Map<String,Object>>();var groups=new ArrayList<Map<String,Object>>();var schema=new TreeMap<String,Map<String,String>>();
        for(Node clazz:repo.nodes("Class",Map.of("namespaceId",c.namespaceId))){var fields=new TreeMap<String,String>();for(Node d:repo.children(clazz,"ClassDescriptor"))fields.put((String)d.getProperty("name"),(String)d.getProperty("type"));schema.put((String)clazz.getProperty("name"),fields);}
        Map<String,String> mapped=new HashMap<>();for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);String source=(String)clazz.get("name"),target=source;mapped.put(source,target);Map<String,String> declarations=schema.computeIfAbsent(target,k->new TreeMap<>());for(Object rd:(List<?>)clazz.get("descriptors")){var d=ValueCodec.map(rd);String old=declarations.putIfAbsent((String)d.get("name"),(String)d.get("type"));if(old!=null&&!old.equals(d.get("type")))conflicts.add(Json.map("kind","schema","className",target,"descriptor",d.get("name"),"sourceType",d.get("type"),"targetType",old));}}
        var patterns=new ArrayList<Map<String,Object>>();var patternDetails=new ArrayList<Map<String,Object>>();
        for(Object raw:(List<?>)graph.get("patterns")){var p=ValueCodec.map(raw);Map<String,Object> def=PatternCodec.normalize(ValueCodec.map(p.get("definition")));patterns.add(def);patternDetails.add(Json.map("name",p.get("name"),"objects",def.get("objects")));Node existing=repo.find("Pattern",Map.of("namespaceId",c.namespaceId,"name",p.get("name")));if(existing!=null&&(!samePatternDefinition((String)existing.getProperty("definition"),def,schema)||!Objects.equals(existing.getProperty("comment",""),p.getOrDefault("comment",""))))conflicts.add(Json.map("kind","patternName","name",p.get("name")));}
        for(Node stored:repo.nodes("Pattern",Map.of("namespaceId",c.namespaceId))){var definition=Json.read((String)stored.getProperty("definition"));patterns.add(definition);patternDetails.add(Json.map("name",stored.getProperty("name"),"objects",definition.get("objects")));}
        try{PatternCodec.validate(patterns,schema);}catch(RuntimeException invalid){conflicts.add(Json.map("kind","patternCompatibility","message","Incoming and receiving Patterns or schema are incompatible","patterns",patternDetails,"classes",new ArrayList<>(schema.keySet())));}
        for(Object raw:(List<?>)graph.get("userTools")){var tool=ValueCodec.map(raw);Node existing=repo.find("UserTool",Map.of("namespaceId",c.namespaceId,"name",tool.get("name")));if(existing!=null&&!Objects.equals(existing.getProperty("data"),tool.get("data")))conflicts.add(Json.map("kind","userToolName","name",tool.get("name"),"incomingId",tool.get("id"),"receivingId",CoreRepository.id(existing)));}
        for(Object raw:(List<?>)graph.get("descriptors")){var descriptor=ValueCodec.map(raw);indexes.update("INSERT INTO descriptors(owner,name,body) VALUES(?,?,?)",descriptor.get("objectId"),descriptor.get("name"),encode(Json.map("value",descriptor.get("value"))));}
        for(Object raw:(List<?>)graph.get("relationships")){var edge=ValueCodec.map(raw);indexes.update("INSERT INTO edges(source,target,label) VALUES(?,?,?)",edge.get("sourceId"),edge.get("targetId"),edge.get("label"));}
        for(Object raw:(List<?>)graph.get("objects")){var object=ValueCodec.map(raw);indexes.update("INSERT INTO sources(id,className) VALUES(?,?)",object.get("id"),sourceClassName(graph,(String)object.get("classId")));}
        if(!rules.containsKey("_formRootSource"))indexApplicablePatterns(graph,indexes);
        for(Object raw:(List<?>)graph.get("objects")){indexes.check();var object=ValueCodec.map(raw);String id=(String)object.get("id"),className=sourceClassName(graph,(String)object.get("classId"));Node targetClass=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",className));
            boolean valueKeys=hasMatchingInput(id,className,graph,rules,indexes);indexes.update("UPDATE sources SET keyless=? WHERE id=?",valueKeys?0:1,id);
            boolean structuralInput=indexes.one("SELECT source FROM edges WHERE source=? OR target=? LIMIT 1",id,id)!=null;
            if(!rules.containsKey("_formRootSource")&&targetClass!=null&&(valueKeys||structuralInput)&&!alwaysCreate(id,className,rules,indexes))try(var members=targetClass.getRelationships(Direction.INCOMING,RelationshipType.withName("MEMBER_OF"))){for(var membership:members){indexes.check();Node candidate=membership.getStartNode();if(matchesObject(id,className,candidate,graph,rules,indexes))indexes.update("INSERT INTO candidates(source,target) VALUES(?,?)",id,CoreRepository.id(candidate));}}
        }
        // Keyless Objects need a potentially reused neighbor; an edge to forced-new input cannot identify them.
        do{indexes.update("DELETE FROM candidates WHERE source IN (SELECT s.id FROM sources s WHERE s.keyless=1 AND NOT EXISTS (SELECT 1 FROM edges e JOIN candidates neighbor ON neighbor.source=CASE WHEN e.source=s.id THEN e.target ELSE e.source END WHERE e.source=s.id OR e.target=s.id))");}while(((Number)indexes.one("SELECT changes() AS n").get("n")).intValue()>0);
        var solver=new MappingSolver(indexes,solutions,(source,target,label)->{Node a=repo.entity("Object",c.namespaceId,source),b=repo.entity("Object",c.namespaceId,target);return a!=null&&b!=null&&repo.relationship(a,b,label)!=null;});
        if(rules.get("_formRootSource") instanceof String rootSource){var mapping=new LinkedHashMap<String,Object>();for(Object raw:(List<?>)graph.get("objects")){String id=(String)ValueCodec.map(raw).get("id");mapping.put(id,id.equals(rootSource)?rules.get("rootId"):null);}solutions.add(mapping);solver.count=mapping.size();solver.best=solver.count-1;}else solver.solve();
        if(solutions.isEmpty())c.fail("IMPORT_CONFLICT","No complete import mapping satisfies the source structure");
        // These complete advertised mappings are an unavoidable response/plan floor, allocated only after the optimum is known.
        var advertised=new ArrayList<Map<String,Object>>();for(Object solution:solutions){indexes.check();advertised.add(ValueCodec.map(solution));}
        Map<String,Object> objectMappings=advertised.size()==1?advertised.getFirst():Map.of();
        if(advertised.size()>1)conflicts.add(Json.map("kind","mappingAmbiguity","conflictId","mapping","candidateMappings",advertised));
        if(!rules.containsKey("_formRootSource"))for(Object raw:(List<?>)graph.get("relationships")){indexes.check();var edge=ValueCodec.map(raw);String s=(String)objectMappings.get(edge.get("sourceId")),t=(String)objectMappings.get(edge.get("targetId"));if(s!=null&&t!=null&&repo.relationship(repo.entity("Object",c.namespaceId,s),repo.entity("Object",c.namespaceId,t),(String)edge.get("label"))==null)c.fail("IMPORT_CONFLICT","Mapped Objects do not satisfy a source relationship");}
        if(advertised.size()==1)for(Object raw:(List<?>)graph.get("descriptors")){indexes.check();var descriptor=ValueCodec.map(raw);String sourceId=(String)descriptor.get("objectId"),targetId=(String)objectMappings.get(sourceId);if(targetId==null||sourceId.equals(rules.get("_formRootSource")))continue;Node target=repo.entity("Object",c.namespaceId,targetId),existing=repo.descriptor(target,(String)descriptor.get("name"));if(existing==null)continue;Node declaration=repo.declaration(repo.objectClass(target),(String)descriptor.get("name"));if(declaration==null)continue;String type=(String)declaration.getProperty("type");Object incoming=ExchangePackageCodec.decodeValue(type,descriptor.get("value")),current=repo.descriptorValue(existing);boolean equal="DResource".equals(type)?resourceEquivalent(incoming,current,graph,c.namespaceId):Objects.equals(incoming,current);if(!equal)conflicts.add(Json.sourceRecord(encode(Json.map("kind","value","conflictId","field:"+sourceId+":"+descriptor.get("name"),"sourceId",ValueCodec.map(rules.getOrDefault("_tabularIds",Map.of())).getOrDefault(sourceId,sourceId),"descriptor",descriptor.get("name"),"incoming",descriptor.get("value"),"receiving",current))));}
        if(advertised.size()==1)conflicts.addAll(coalescedDescriptorConflicts(graph,objectMappings,rules,indexes));
        if(!conflicts.isEmpty())throw ExceptionHandler.error("IMPORT_CONFLICT","Import analysis found conflicts",c,false,Json.map("conflicts",conflicts));
        groups.add(Json.map("groupId","all","objects",((List<?>)graph.get("objects")).size(),"dependencies",List.of()));
        return Json.map("classMap",mapped,"objectMappings",objectMappings,"candidateMappings",advertised,"groups",groups,"conflicts",conflicts,"summary",Json.map("classes",((List<?>)graph.get("classes")).size(),"objects",((List<?>)graph.get("objects")).size(),"relationships",((List<?>)graph.get("relationships")).size(),"createObjects",(long)solver.best,"reuseObjects",(long)solver.count-solver.best));
        }catch(IOException failure){throw new UncheckedIOException(failure);}
    }

    private String sourceClassName(Map<String,Object> graph,String classId){for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);if(classId.equals(clazz.get("id")))return (String)clazz.get("name");}c.fail("VALIDATION_FAILED","Object Class is missing from package");return "";}
    private boolean alwaysCreate(String source,String className,Map<String,Object> rules,ImportScratch indexes){if(forcedClass(className,rules))return true;try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",source)){for(var role:roles)if(Boolean.TRUE.equals(Json.sourceRecord((String)role.get("body")).get("alwaysCreate")))return true;}return false;}
    private boolean forcedClass(String className,Map<String,Object> rules){for(Object raw:(List<?>)rules.getOrDefault("alwaysCreate",List.of()))if(className.equals(BaseService.name(raw)))return true;return false;}
    private boolean matchesObject(String sourceId,String sourceClass,Node candidate,Map<String,Object> graph,Map<String,Object> rules,ImportScratch indexes){
        boolean patternRole=false;List<?> blanks=(List<?>)ValueCodec.map(rules.getOrDefault("_tabularBlanks",Map.of())).getOrDefault(sourceId,List.of());
        try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",sourceId)){for(var raw:roles){var spec=Json.sourceRecord((String)raw.get("body"));patternRole=true;boolean matchEmpty=Boolean.TRUE.equals(spec.get("matchEmpty"));for(Object descriptor:(List<?>)spec.get("descriptors")){indexes.check();String field=(String)ValueCodec.map(descriptor).get("name");if(blanks.contains(field))continue;var value=indexes.one("SELECT body FROM descriptors WHERE owner=? AND name=?",sourceId,field);if(!matchesField(candidate,field,value!=null,value==null?null:Json.sourceRecord((String)value.get("body")).get("value"),matchEmpty,graph))return false;}}}
        if(!patternRole){boolean matchEmpty=Boolean.TRUE.equals(rules.getOrDefault("matchEmpty",false));try(var values=indexes.query("SELECT name,body FROM descriptors WHERE owner=? ORDER BY name",sourceId)){for(var value:values){Object incoming=Json.sourceRecord((String)value.get("body")).get("value");if((matchEmpty||incoming!=null)&&!matchesField(candidate,(String)value.get("name"),true,incoming,matchEmpty,graph))return false;}}}
        return true;
    }
    /** Applicability depends only on complete source Class/edge bindings, never Descriptor values. */
    private void indexApplicablePatterns(Map<String,Object> graph,ImportScratch indexes){
        for(Object raw:(List<?>)graph.get("patterns")){var roles=ValueCodec.map(ValueCodec.map(ValueCodec.map(raw).get("definition")).get("objects"));var visited=new HashSet<String>();
            for(String start:roles.keySet()){if(visited.contains(start))continue;var component=connectedRoles(start,roles);visited.addAll(component);var order=new ArrayList<>(component);Collections.sort(order);var chosen=new LinkedHashMap<String,String>();String[] after=new String[order.size()];int depth=0;
                while(depth>=0){indexes.check();String role=order.get(depth),className=(String)ValueCodec.map(roles.get(role)).get("className");Map<String,Object> next=after[depth]==null?indexes.one("SELECT id FROM sources WHERE className=? ORDER BY id LIMIT 1",className):indexes.one("SELECT id FROM sources WHERE className=? AND id>? ORDER BY id LIMIT 1",className,after[depth]);
                    if(next==null){after[depth]=null;chosen.remove(role);depth--;continue;}String id=(String)next.get("id");after[depth]=id;chosen.put(role,id);if(!sourceBindingCompatible(roles,chosen,indexes)){chosen.remove(role);continue;}
                    if(depth==order.size()-1){for(var binding:chosen.entrySet())indexes.update("INSERT OR IGNORE INTO matching_rules(source,body) VALUES(?,?)",binding.getValue(),encode(roles.get(binding.getKey())));chosen.remove(role);}else{depth++;after[depth]=null;}
                }
            }
        }
    }
    private boolean sourceBindingCompatible(Map<String,Object> roles,Map<String,String> chosen,ImportScratch indexes){for(var entry:chosen.entrySet())for(Object raw:(List<?>)ValueCodec.map(roles.get(entry.getKey())).get("relationships")){var edge=ValueCodec.map(raw);String target=chosen.get(edge.get("targetName"));if(target!=null&&indexes.one("SELECT 1 AS found FROM edges WHERE source=? AND target=? AND label=? LIMIT 1",entry.getValue(),target,edge.get("label"))==null)return false;}return true;}
    private boolean matchesField(Node candidate,String field,boolean present,Object incoming,boolean matchEmpty,Map<String,Object> graph){Node target=repo.descriptor(candidate,field);if(!present)return !matchEmpty||target==null;if(incoming==null&&!matchEmpty)return true;if(target==null)return false;Node declaration=repo.declaration(repo.objectClass(candidate),field);if(declaration==null)return false;String type=(String)declaration.getProperty("type");Object decoded;try{decoded=ExchangePackageCodec.decodeValue(type,incoming);}catch(RuntimeException invalid){return false;}Object actual=repo.descriptorValue(target);return "DResource".equals(type)?resourceEquivalent(decoded,actual,graph,candidate.getProperty("namespaceId").toString()):Objects.equals(decoded,actual);}
    private boolean resourceEquivalent(Object incoming,Object target,Map<String,Object> graph,String targetNamespace){if(incoming==null||target==null)return Objects.equals(incoming,target);String sourceId=(String)ValueCodec.map(incoming).get("resourceId"),targetId=(String)ValueCodec.map(target).get("resourceId");Map<String,Object> source=null;for(Object raw:(List<?>)graph.get("resources")){var row=ValueCodec.map(raw);if(sourceId.equals(row.get("id"))){source=row;break;}}Node dest=resources.resource(targetNamespace,targetId);return source!=null&&dest!=null&&Objects.equals(source.get("sha256"),dest.getProperty("checksum",null))&&Objects.equals(source.get("bytes"),dest.getProperty("bytes",null))&&Objects.equals(source.get("mediaType"),dest.getProperty("mediaType",null))&&Objects.equals(source.get("filename"),dest.getProperty("filename",null));}

    @FunctionalInterface interface EdgeMatch {boolean matches(String source,String target,String label);}
    /** Iterative branch-and-bound, followed by complete enumeration at the proven minimum. */
    static final class MappingSolver {
        final ImportScratch indexes;final DiskJsonList solutions;final EdgeMatch edges;
        int count,best,forced,depth,created;
        MappingSolver(ImportScratch indexes,DiskJsonList solutions,EdgeMatch edges){this.indexes=indexes;this.solutions=solutions;this.edges=edges;}
        void solve(){
            indexes.update("UPDATE sources SET candidateCount=(SELECT count(*) FROM candidates WHERE source=sources.id)");
            indexes.update("CREATE TABLE ordering AS SELECT row_number() OVER (ORDER BY candidateCount,id)-1 AS position,id,sum(CASE WHEN candidateCount=0 THEN 1 ELSE 0 END) OVER (ORDER BY candidateCount,id ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) AS forced FROM sources");
            indexes.update("CREATE UNIQUE INDEX ordering_position ON ordering(position)");
            count=((Number)indexes.one("SELECT count(*) AS n FROM sources").get("n")).intValue();best=count;
            var first=indexes.one("SELECT forced FROM ordering WHERE position=0");forced=first==null?0:((Number)first.get("forced")).intValue();
            if(best>forced)pass(false);
            indexes.update("DELETE FROM assignments");indexes.update("DELETE FROM frames");pass(true);
        }
        private void pass(boolean enumerate){depth=0;created=0;
            while(depth>=0){indexes.check();
                if(depth==count){if(!completeKeylessSupport()){retreat();continue;}if(enumerate&&created==best){try{solutions.add(indexes.assignments());if(solutions.size()>=2)return;}catch(RuntimeException failure){throw indexes.interrupted==null?failure:indexes.interrupted;}}else if(!enumerate&&created<best){best=created;if(best==forced)return;}retreat();continue;}
                var source=indexes.one("SELECT id,forced FROM ordering WHERE position=?",depth);String id=(String)source.get("id");int lower=created+((Number)source.get("forced")).intValue();
                if(lower>best||!enumerate&&lower>=best){retreat();continue;}
                var frame=indexes.one("SELECT lastTarget,creation FROM frames WHERE position=?",depth);
                if(frame==null){indexes.update("INSERT INTO frames(position) VALUES(?)",depth);frame=Json.map("lastTarget",null,"creation",0);}
                if(((Number)frame.get("creation")).intValue()!=0){retreat();continue;}
                Object after=frame.get("lastTarget");var next=after==null?indexes.one("SELECT target FROM candidates WHERE source=? ORDER BY target LIMIT 1",id):indexes.one("SELECT target FROM candidates WHERE source=? AND target>? ORDER BY target LIMIT 1",id,after);
                if(next!=null){String target=(String)next.get("target");indexes.update("UPDATE frames SET lastTarget=? WHERE position=?",target,depth);indexes.update("INSERT INTO assignments(source,target) VALUES(?,?) ON CONFLICT(source) DO UPDATE SET target=excluded.target",id,target);if(compatible(id)){depth++;indexes.update("DELETE FROM frames WHERE position=?",depth);}continue;}
                indexes.update("UPDATE frames SET creation=1 WHERE position=?",depth);indexes.update("INSERT INTO assignments(source,target) VALUES(?,NULL) ON CONFLICT(source) DO UPDATE SET target=NULL",id);created++;depth++;indexes.update("DELETE FROM frames WHERE position=?",depth);
            }
        }
        private boolean completeKeylessSupport(){
            return indexes.one("SELECT 1 AS invalid FROM assignments a JOIN sources s ON s.id=a.source WHERE s.keyless=1 AND a.target IS NOT NULL AND NOT EXISTS (SELECT 1 FROM edges e JOIN assignments neighbor ON neighbor.source=CASE WHEN e.source=a.source THEN e.target ELSE e.source END WHERE (e.source=a.source OR e.target=a.source) AND neighbor.target IS NOT NULL) LIMIT 1")==null;
        }
        private void retreat(){
            if(depth<count){var current=indexes.one("SELECT id FROM ordering WHERE position=?",depth);indexes.update("DELETE FROM assignments WHERE source=?",current.get("id"));indexes.update("DELETE FROM frames WHERE position=?",depth);}
            depth--;if(depth<0)return;var parent=indexes.one("SELECT id FROM ordering WHERE position=?",depth);var assignment=indexes.one("SELECT target FROM assignments WHERE source=?",parent.get("id"));if(assignment!=null&&assignment.get("target")==null)created--;indexes.update("DELETE FROM assignments WHERE source=?",parent.get("id"));
        }
        private boolean compatible(String id){
            try(var links=indexes.query("SELECT a.target AS source,b.target AS target,e.label FROM edges e JOIN assignments a ON a.source=e.source JOIN assignments b ON b.source=e.target WHERE (e.source=? OR e.target=?) AND a.target IS NOT NULL AND b.target IS NOT NULL",id,id)){
                for(var link:links){indexes.check();if(!edges.matches((String)link.get("source"),(String)link.get("target"),(String)link.get("label")))return false;}return true;
            }
        }
    }

    private Map<String,Object> applySnapshot(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules,Map<String,Object> summary,Path path){
        Map<String,Object> mappings=ValueCodec.map(summary.get("objectMappings"));
        applyFormScopes(pkg.graph(),mappings,rules);
        return materialize(pkg,path,c.namespaceId,ValueCodec.map(summary.get("classMap")),mappings);

    }

    /** Application needs one selected mapping and conflict metadata; the full advertised set stays on disk. */
    static Map<String,Object> applicationSummary(String source,DiskJsonList candidates,DiskJsonList conflicts,Runnable checkpoint){
        var result=new LinkedHashMap<String,Object>();var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
        try(var parser=mapper.createParser(source)){
            if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected import summary object");
            while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){checkpoint.run();String name=parser.currentName();var token=parser.nextToken();
                if(name.equals("candidateMappings")){if(token!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IOException("Expected advertised mappings");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){checkpoint.run();candidates.add(mapper.readValue(parser,Map.class));}}
                else if(name.equals("conflicts")){if(token!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IOException("Expected import conflicts");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){checkpoint.run();if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected import conflict");var metadata=new LinkedHashMap<String,Object>();while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){String field=parser.currentName();parser.nextToken();if(Set.of("kind","conflictId","options").contains(field))metadata.put(field,mapper.readValue(parser,Object.class));else parser.skipChildren();}conflicts.add(metadata);}}
                else if(name.equals("classMap")||name.equals("objectMappings"))result.put(name,mapper.readValue(parser,Map.class));
                else parser.skipChildren();
            }
        }catch(IOException failure){throw new UncheckedIOException(failure);}
        result.put("candidateMappings",candidates);result.put("conflicts",conflicts);return result;
    }

    private Map<String,Object> materialize(ExchangePackageCodec.PackageData pkg,Path archive,String namespace,Map<String,Object> classMap){return materialize(pkg,archive,namespace,classMap,Map.of());}
    private Map<String,Object> materialize(ExchangePackageCodec.PackageData pkg,Path archive,String namespace,Map<String,Object> classMap,Map<String,Object> objectMappings){
        try(var imported=resources.beginImport(namespace,c.actorId)){
        Map<String,Object> graph=pkg.graph();Map<String,String> classes=new HashMap<>(),objects=new HashMap<>(),resourcesMap=new HashMap<>(),geometriesMap=new HashMap<>(),descriptors=new HashMap<>(),memberships=new HashMap<>(),relationships=new HashMap<>(),classRelationships=new HashMap<>(),patterns=new HashMap<>(),userTools=new HashMap<>();
        for(Object raw:(List<?>)graph.get("classes")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id"),targetName=BaseService.name(classMap.getOrDefault(source.get("name"),(String)source.get("name")));Node target=repo.find("Class",Map.of("namespaceId",namespace,"name",targetName));
            if(target==null)target=repo.create("Class",namespace,c.actorId,Json.map("name",targetName,"position",source.get("position"),"order",source.getOrDefault("order",0L),"comment",source.getOrDefault("comment","")));
            for(Object rd:(List<?>)source.get("descriptors")){var d=ValueCodec.map(rd);String field=(String)d.get("name"),type=(String)d.get("type");Node existing=repo.declaration(target,field);if(existing==null)repo.attach("ClassDescriptor",target,c.actorId,Json.map("name",field,"type",type,"order",d.getOrDefault("order",0L),"comment",d.getOrDefault("comment","")));else if(!type.equals(existing.getProperty("type")))c.fail("IMPORT_CONFLICT","Class declaration changed after analysis");}
            classes.put(sourceId,CoreRepository.id(target));
        }
        for(Object raw:(List<?>)graph.get("resources")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id"),checksum=(String)source.get("sha256");Path staged=null;try(ZipFile zip=new ZipFile(archive.toFile())){var entry=zip.getEntry("resources/"+checksum);if(entry==null)c.fail("RESOURCE_CORRUPT","Package resource content is missing");staged=resources.files.path("staging/imports/content-"+UUID.randomUUID());Files.createDirectories(staged.getParent());try(InputStream in=zip.getInputStream(entry);OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}if(Files.size(staged)!=((Number)source.get("bytes")).longValue()||!checksum.equals(ResourceFiles.checksum(staged)))c.fail("RESOURCE_CORRUPT","Package resource content changed");
                String targetId=imported.create(staged,(String)source.get("filename"),(String)source.getOrDefault("mediaType","application/octet-stream"),((Number)source.get("bytes")).longValue(),checksum,c.timestamp);resourcesMap.put(sourceId,targetId);
            }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to materialize package resource");}finally{if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){}}}
        for(Object raw:(List<?>)graph.get("objects")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id"),classId=classes.get((String)source.get("classId"));if(classId==null)c.fail("VALIDATION_FAILED","Object Class is outside the package");Object selected=objectMappings.get(sourceId);Node object=selected instanceof String targetId?repo.entity("Object",namespace,targetId):null;if(selected instanceof String&&object==null)c.fail("PLAN_STALE","A selected matching Object is no longer available");if(object==null){object=repo.create("Object",namespace,c.actorId,Json.map("position",source.getOrDefault("position",List.of(0.0,0.0))));repo.assign(object,repo.entity("Class",namespace,classId),c.actorId);}else if(!Objects.equals(CoreRepository.id(repo.objectClass(object)),classId))c.fail("PLAN_STALE","A selected matching Object changed Class");objects.put(sourceId,CoreRepository.id(object));}
        for(Object raw:(List<?>)graph.get("memberships")){var m=ValueCodec.map(raw);String objectId=objects.get(m.get("objectId"));Node object=objectId==null?null:repo.entity("Object",namespace,objectId);if(object==null)c.fail("VALIDATION_FAILED","Membership Object is outside the package");memberships.put((String)m.get("id"),CoreRepository.id(repo.membership(object)));}
        for(Object raw:(List<?>)graph.get("descriptors")){var source=ValueCodec.map(raw);String sourceObject=(String)source.get("objectId"),targetObject=objects.get(sourceObject);if(targetObject==null)c.fail("VALIDATION_FAILED","Descriptor owner is outside the package");Node object=repo.entity("Object",namespace,targetObject),clazz=repo.objectClass(object);String name=(String)source.get("name");Node declaration=repo.declaration(clazz,name);if(declaration==null)c.fail("VALIDATION_FAILED","Descriptor declaration is missing");String type=(String)declaration.getProperty("type");Object incoming=ExchangePackageCodec.decodeValue(type,source.get("value")),value=incoming;if("DResource".equals(type)&&value!=null){String mappedResource=resourcesMap.get((String)ValueCodec.map(value).get("resourceId"));if(mappedResource==null)c.fail("RESOURCE_CORRUPT","Package resource reference is missing");value=Json.map("resourceId",mappedResource);}Node descriptor=repo.descriptor(object,name);if(descriptor==null){descriptor=repo.attach("Descriptor",object,c.actorId,Json.map("name",name));repo.writeImportedValue(descriptor,type,value,c.actorId,imported);}else {boolean equal="DResource".equals(type)?resourceEquivalent(incoming,repo.descriptorValue(descriptor),graph,namespace):Objects.equals(repo.descriptorValue(descriptor),value);if(!equal){c.fail("PLAN_STALE","Matched Descriptor changed after analysis");}}descriptors.put((String)source.get("id"),CoreRepository.id(descriptor));if("DGeometry".equals(type)&&source.get("geometryId") instanceof String sourceGeometry){Node geometry=new GeometryRepository(c.tx,repo).geometry(descriptor);if(geometry!=null)geometriesMap.put(sourceGeometry,CoreRepository.id(geometry));} }
        for(Object raw:(List<?>)graph.get("relationships")){var edge=ValueCodec.map(raw);Node source=repo.entity("Object",namespace,objects.get(edge.get("sourceId"))),target=repo.entity("Object",namespace,objects.get(edge.get("targetId")));if(source==null||target==null)c.fail("VALIDATION_FAILED","Relationship endpoint is outside the package");var relation=repo.relationship(source,target,(String)edge.get("label"));if(relation==null)relation=repo.createRelationship(source,target,(String)edge.get("label"),edge.containsKey("weight")?Json.map("weight",edge.get("weight")):Map.of(),c.actorId);relationships.put((String)edge.get("id"),CoreRepository.id(relation));}
        for(Object raw:(List<?>)graph.get("classRelationships")){var edge=ValueCodec.map(raw);Node source=repo.entity("Class",namespace,classes.get(edge.get("sourceId"))),target=repo.entity("Class",namespace,classes.get(edge.get("targetId")));if(source==null||target==null)c.fail("VALIDATION_FAILED","Class Relationship endpoint is outside the package");var relation=repo.relationship(source,target,(String)edge.get("label"));if(relation==null)relation=repo.createRelationship(source,target,(String)edge.get("label"),Map.of(),c.actorId);classRelationships.put((String)edge.get("id"),CoreRepository.id(relation));}
        Map<String,String> mappedNames=new HashMap<>();classMap.forEach((k,v)->mappedNames.put(k,(String)v));for(Object raw:(List<?>)graph.get("patterns")){var p=ValueCodec.map(raw);Map<String,Object> definition=mapPatternClasses(ValueCodec.map(p.get("definition")),mappedNames);Node existing=repo.find("Pattern",Map.of("namespaceId",namespace,"name",p.get("name")));if(existing==null)existing=repo.create("Pattern",namespace,c.actorId,Json.map("name",p.get("name"),"definition",Json.write(definition),"comment",p.getOrDefault("comment","")));else if(!samePatternDefinition((String)existing.getProperty("definition"),definition,namespaceSchema(namespace))||!Objects.equals(existing.getProperty("comment",""),p.getOrDefault("comment","")))c.fail("IMPORT_CONFLICT","Pattern name conflicts");patterns.put((String)p.get("id"),CoreRepository.id(existing));}
        for(Object raw:(List<?>)graph.get("userTools")){var tool=ValueCodec.map(raw);Node existing=repo.find("UserTool",Map.of("namespaceId",namespace,"name",tool.get("name")));if(existing==null)existing=repo.create("UserTool",namespace,c.actorId,Json.map("name",tool.get("name"),"data",tool.get("data"),"comment",tool.getOrDefault("comment","")));else if(!Objects.equals(existing.getProperty("data"),tool.get("data")))c.fail("IMPORT_CONFLICT","User Tool name conflicts");userTools.put((String)tool.get("id"),CoreRepository.id(existing));}
        new PatternService(c).validateNamespace(namespace);var mapping=new TreeMap<String,String>();mapping.putAll(classes);mapping.putAll(objects);mapping.putAll(resourcesMap);mapping.putAll(geometriesMap);var identityMappings=Json.map("Namespace",Map.of((String)pkg.manifest().get("sourceNamespaceId"),namespace),"Class",classes,"Object",objects,"DResource",resourcesMap,"DGeometry",geometriesMap,"Descriptor",descriptors,"Membership",memberships,"Relationship",relationships,"ClassRelationship",classRelationships,"Pattern",patterns,"UserTool",userTools);return Json.map("mapping",mapping,"identityMappings",identityMappings,"resourceMapping",resourcesMap,"createdClasses",(long)classes.size(),"createdObjects",(long)objects.size(),"createdResources",(long)resourcesMap.size(),"createdRelationships",((List<?>)graph.get("relationships")).size());
        }
    }

    private Map<String,Object> planData(Node plan){Map<String,Object> summary=Json.read((String)plan.getProperty("summary"));var data=Json.map("planId",CoreRepository.id(plan),"state",plan.getProperty("state"),"targetRevision",plan.getProperty("targetRevision"),"expiresAt",plan.getProperty("expiresAt"),"groups",summary.get("groups"),"summary",summary.get("summary"));if(summary.containsKey("rowMappings"))data.put("rowMappings",summary.get("rowMappings"));return data;}
    private Map<String,Object> mapPatternClasses(Map<String,Object> definition,Map<String,String> mapping){var normalized=PatternCodec.normalize(definition);var objects=new LinkedHashMap<String,Object>();ValueCodec.map(normalized.get("objects")).forEach((role,raw)->{var spec=new LinkedHashMap<>(ValueCodec.map(raw));spec.put("className",mapping.getOrDefault(spec.get("className"), (String)spec.get("className")));objects.put(role,spec);});return Json.map("objects",objects);}
    private boolean samePatternDefinition(String stored,Map<String,Object> incoming){return Objects.equals(PatternCodec.normalize(Json.read(stored)),PatternCodec.normalize(incoming));}
    private boolean samePatternDefinition(String stored,Map<String,Object> incoming,Map<String,? extends Map<String,String>> schema){return Objects.equals(canonicalPattern(Json.read(stored),schema),canonicalPattern(incoming,schema));}
    private Map<String,Object> canonicalPattern(Map<String,Object> definition,Map<String,? extends Map<String,String>> schema){var normalized=PatternCodec.normalize(definition);for(Object raw:ValueCodec.map(normalized.get("objects")).values()){var role=ValueCodec.map(raw);Map<String,String> fields=schema.get(role.get("className"));for(Object rd:(List<?>)role.get("descriptors")){var descriptor=ValueCodec.map(rd);if(fields!=null&&Objects.equals(descriptor.get("type"),fields.get(descriptor.get("name"))))descriptor.remove("type");}}return normalized;}
    private boolean hasMatchingInput(String source,String className,Map<String,Object> graph,Map<String,Object> rules,ImportScratch indexes){
        boolean applicable=false;try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",source)){for(var raw:roles){var role=Json.sourceRecord((String)raw.get("body"));applicable=true;for(Object d:(List<?>)role.get("descriptors")){String name=(String)ValueCodec.map(d).get("name");if(((List<?>)ValueCodec.map(rules.getOrDefault("_tabularBlanks",Map.of())).getOrDefault(source,List.of())).contains(name))continue;var value=indexes.one("SELECT body FROM descriptors WHERE owner=? AND name=?",source,name);if(Boolean.TRUE.equals(role.get("matchEmpty"))||value!=null&&Json.sourceRecord((String)value.get("body")).get("value")!=null)return true;}}}
        if(applicable)return false;
        try(var values=indexes.query("SELECT body FROM descriptors WHERE owner=?",source)){for(var value:values){if(Boolean.TRUE.equals(rules.get("matchEmpty"))||Json.sourceRecord((String)value.get("body")).get("value")!=null)return true;}}return false;
    }
    private Path copyAuthorizedPackage(String packageId){
        try{Node location=resources.location(packageId);if(location==null)c.fail("NOT_FOUND","Package resource not found");String sourceNs=(String)location.getProperty("namespaceId",null);Node resource=sourceNs==null?null:resources.resource(sourceNs,packageId);if(resource==null||!"ready".equals(resource.getProperty("state",null)))c.fail("NOT_FOUND","Package resource not found");resources.lock(packageId);String role=repo.role(c.actorId,sourceNs);if(!resources.accessible(resource,c.actorId,role,false))c.fail("NOT_FOUND","Package resource not found");ResourceStorage.Content content=resources.verify(location);Path path=resources.files.path("staging/imports/"+UUID.randomUUID()+".zip");Files.createDirectories(path.getParent());try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}return path;}catch(IOException e){throw new UncheckedIOException(e);}
    }
    private ExchangePackageCodec.PackageData validate(Path path){try{return ExchangePackageCodec.validate(path);}catch(IOException e){throw new UncheckedIOException(e);}}
    private <T> T withValidatedPackage(Path path,java.util.function.Function<ExchangePackageCodec.PackageData,T> action){try{return ExchangePackageCodec.withValidatedPackage(path,()->c.state.kernel.assertOpen(),action);}catch(IOException e){throw new UncheckedIOException(e);}}
    /** Disposable indexes share no transaction or state with the receiving Neo4j database. */
    static final class ImportScratch implements AutoCloseable {
        final Path directory;final Connection db;final Runnable checkpoint;
        private final Map<String,PreparedStatement> statements=new HashMap<>();
        private final Set<RowCursor> cursors=new HashSet<>();private int nextMap;private RuntimeException interrupted;
        ImportScratch(Path parent,Runnable checkpoint)throws IOException {
            this.checkpoint=checkpoint;Files.createDirectories(parent);directory=Files.createTempDirectory(parent,"import-index-");Connection opened=null;
            try {
                opened=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"));db=opened;
                try(var statement=db.createStatement()){statement.execute("PRAGMA journal_mode=OFF");statement.execute("PRAGMA synchronous=OFF");statement.execute("PRAGMA temp_store=FILE");statement.execute("PRAGMA cache_size=-2048");}
                db.setAutoCommit(false);
                org.sqlite.ProgressHandler.setHandler(db,1000,new org.sqlite.ProgressHandler(){protected int progress(){try{check();return 0;}catch(RuntimeException failure){interrupted=failure;return 1;}}});
                update("CREATE TABLE records(bucket INTEGER,key TEXT,body TEXT,PRIMARY KEY(bucket,key))");
                update("CREATE TABLE sources(id TEXT PRIMARY KEY,className TEXT,candidateCount INTEGER NOT NULL DEFAULT 0,keyless INTEGER NOT NULL DEFAULT 0)");
                update("CREATE TABLE candidates(source TEXT,target TEXT,PRIMARY KEY(source,target))");
                update("CREATE TABLE edges(source TEXT,target TEXT,label TEXT)");update("CREATE INDEX edges_source ON edges(source)");update("CREATE INDEX edges_target ON edges(target)");
                update("CREATE TABLE descriptors(owner TEXT,name TEXT,body TEXT,PRIMARY KEY(owner,name))");
                update("CREATE TABLE matching_rules(source TEXT,body TEXT,PRIMARY KEY(source,body))");
                update("CREATE TABLE assignments(source TEXT PRIMARY KEY,target TEXT)");
                update("CREATE TABLE frames(position INTEGER PRIMARY KEY,lastTarget TEXT,creation INTEGER NOT NULL DEFAULT 0)");
            }catch(SQLException|RuntimeException failure){if(opened!=null)try{opened.close();}catch(SQLException suppressed){failure.addSuppressed(suppressed);}removeDirectory();if(failure instanceof RuntimeException runtime)throw runtime;throw new IOException("Unable to open import indexes",failure);}
        }
        void check(){if(interrupted!=null)throw interrupted;try{checkpoint.run();}catch(RuntimeException failure){interrupted=failure;throw failure;}}
        private RuntimeException failure(SQLException failure){return interrupted!=null?interrupted:new UncheckedIOException(new IOException("Private import index failed",failure));}
        private void bind(PreparedStatement statement,Object... values)throws SQLException{statement.clearParameters();for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);}
        void update(String sql,Object... values){check();try{PreparedStatement statement=statements.get(sql);if(statement==null){statement=db.prepareStatement(sql);statements.put(sql,statement);}bind(statement,values);statement.executeUpdate();}catch(SQLException failure){throw failure(failure);}}
        RowCursor query(String sql,Object... values){check();try{var statement=db.prepareStatement(sql);try{bind(statement,values);var cursor=new RowCursor(statement);cursors.add(cursor);return cursor;}catch(SQLException|RuntimeException failure){statement.close();throw failure;}}catch(SQLException failure){throw failure(failure);}}
        Map<String,Object> one(String sql,Object... values){try(var rows=query(sql,values)){return rows.hasNext()?rows.next():null;}}
        final class RowCursor implements Iterator<Map<String,Object>>,Iterable<Map<String,Object>>,AutoCloseable {
            final PreparedStatement statement;final ResultSet rows;final String[] names;boolean next,closed;
            RowCursor(PreparedStatement statement)throws SQLException{this.statement=statement;rows=statement.executeQuery();var metadata=rows.getMetaData();names=new String[metadata.getColumnCount()];for(int i=0;i<names.length;i++)names[i]=metadata.getColumnLabel(i+1);next=rows.next();}
            public Iterator<Map<String,Object>> iterator(){return this;}public boolean hasNext(){return next;}
            public Map<String,Object> next(){if(!next)throw new NoSuchElementException();check();try{var value=new LinkedHashMap<String,Object>();for(int i=0;i<names.length;i++)value.put(names[i],rows.getObject(i+1));next=rows.next();if(!next)close();return value;}catch(SQLException failure){throw failure(failure);}}
            public void close(){if(closed)return;closed=true;next=false;try{rows.close();statement.close();}catch(SQLException failure){throw failure(failure);}finally{cursors.remove(this);}}
        }
        <T> Map<String,T> map(){int bucket=nextMap++;return new AbstractMap<>(){
            @SuppressWarnings("unchecked") public T get(Object key){if(!(key instanceof String))return null;var row=one("SELECT body FROM records WHERE bucket=? AND key=?",bucket,key);return row==null?null:(T)Json.sourceRecord((String)row.get("body")).get("value");}
            public boolean containsKey(Object key){return key instanceof String&&one("SELECT 1 AS found FROM records WHERE bucket=? AND key=?",bucket,key)!=null;}
            public T put(String key,T value){T previous=get(key);update("INSERT INTO records(bucket,key,body) VALUES(?,?,?) ON CONFLICT(bucket,key) DO UPDATE SET body=excluded.body",bucket,key,Json.write(Json.map("value",value)));return previous;}
            public T remove(Object key){T previous=get(key);update("DELETE FROM records WHERE bucket=? AND key=?",bucket,key);return previous;}
            public void clear(){update("DELETE FROM records WHERE bucket=?",bucket);}
            public int size(){return ((Number)one("SELECT count(*) AS n FROM records WHERE bucket=?",bucket).get("n")).intValue();}
            public Set<Entry<String,T>> entrySet(){return new AbstractSet<>(){
                public int size(){return ((Number)one("SELECT count(*) AS n FROM records WHERE bucket=?",bucket).get("n")).intValue();}
                public Iterator<Entry<String,T>> iterator(){var rows=query("SELECT key,body FROM records WHERE bucket=? ORDER BY rowid",bucket);return new Iterator<>(){public boolean hasNext(){return rows.hasNext();}@SuppressWarnings("unchecked") public Entry<String,T> next(){var row=rows.next();return new SimpleImmutableEntry<>((String)row.get("key"),(T)Json.sourceRecord((String)row.get("body")).get("value"));}};}
            };}
        };}
        Map<String,Object> assignments(){return new AbstractMap<>(){
            public Object get(Object key){var row=one("SELECT target FROM assignments WHERE source=?",key);return row==null?null:row.get("target");}
            public int size(){return ((Number)one("SELECT count(*) AS n FROM assignments").get("n")).intValue();}
            public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return ImportScratch.this.assignments().size();}public Iterator<Entry<String,Object>> iterator(){var rows=query("SELECT source,target FROM assignments ORDER BY source");return new Iterator<>(){public boolean hasNext(){return rows.hasNext();}public Entry<String,Object> next(){var row=rows.next();return new SimpleImmutableEntry<>((String)row.get("source"),row.get("target"));}};}};}
        };}
        private void removeDirectory()throws IOException{if(Files.exists(directory))try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        public void close()throws IOException{try{for(var cursor:new ArrayList<>(cursors))cursor.close();for(var statement:statements.values())statement.close();org.sqlite.ProgressHandler.clearHandler(db);db.close();}catch(SQLException failure){throw new IOException("Unable to close import indexes",failure);}finally{removeDirectory();}}
    }
    private ImportScratch scratch()throws IOException{return new ImportScratch(stagedPath("staging/imports"),()->c.state.kernel.assertOpen());}
    private static String encode(Object value){var bytes=new ByteArrayOutputStream();Json.write(bytes,value);return bytes.toString(StandardCharsets.UTF_8);}
    private static void jsonEntries(String source,BiConsumer<String,Object> consumer){
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
        try(var parser=mapper.createParser(source)){if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected object");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){String key=parser.currentName();parser.nextToken();consumer.accept(key,mapper.readValue(parser,Object.class));}}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private Path stagedPath(String relative){try{return resources.files.path(relative);}catch(IOException e){throw new UncheckedIOException(e);}}
    /** Blank and public row IDs are internal immutable matching metadata. */
    private Map<String,Object> tabularRules(Map<String,Object> rules,Map<String,Object> rowIds,Map<String,Object> blanks,Map<String,Object> clears){
        var effective=new LinkedHashMap<>(rules);effective.put("_tabularBlanks",blanks);effective.put("_tabularIds",rowIds);return effective;
    }
    static List<Map<String,Object>> coalescedDescriptorConflicts(Map<String,Object> graph,Map<String,Object> mappings,Map<String,Object> rules,ImportScratch indexes){
        Map<String,Object> values=indexes.map(),sources=indexes.map();var conflicts=new ArrayList<Map<String,Object>>();var publicIds=ValueCodec.map(rules.getOrDefault("_tabularIds",Map.of()));
        for(Object raw:(List<?>)graph.get("descriptors")){indexes.check();var descriptor=ValueCodec.map(raw);String source=(String)descriptor.get("objectId");Object target=mappings.get(source);if(!(target instanceof String))continue;String name=(String)descriptor.get("name"),key=target+"\u0000"+name;Object incoming=descriptor.get("value");if(values.containsKey(key)&&!Objects.equals(values.get(key),incoming))conflicts.add(Json.map("kind","coalescedValue","sourceIds",List.of(publicIds.getOrDefault(sources.get(key),sources.get(key)),publicIds.getOrDefault(source,source)),"descriptor",name));else if(!values.containsKey(key)){values.put(key,incoming);sources.put(key,source);}}
        return conflicts;
    }
    private void applyFormScopes(Map<String,Object> graph,Map<String,Object> mappings,Map<String,Object> rules){
        // Only immutable form metadata may request scoped replacement.
        Object raw=rules.get("_formRootSource");if(!(raw instanceof String source))return;
        Node root=repo.entity("Object",c.namespaceId,(String)mappings.get(source));if(root==null)c.fail("PLAN_STALE","Form root is no longer available");
        for(Object field:(List<?>)rules.get("_formRootDescriptors")){Node d=repo.descriptor(root,(String)field);if(d!=null)repo.remove(d,new ArrayList<>());}
        var remove=new ArrayList<org.neo4j.graphdb.Relationship>();
        try(var edges=root.getRelationships(Direction.BOTH,RelationshipType.withName("RELATED_TO"))){for(var edge:edges)for(Object scopeRaw:(List<?>)rules.get("_formRootRelationships")){var scope=ValueCodec.map(scopeRaw);boolean outgoing="outgoing".equals(scope.get("direction"));if(!(outgoing?edge.getStartNode():edge.getEndNode()).equals(root))continue;Node other=outgoing?edge.getEndNode():edge.getStartNode();if(Objects.equals(scope.get("label"),edge.getProperty("label"))&&Objects.equals(scope.get("className"),repo.objectClass(other).getProperty("name"))){remove.add(edge);break;}}}
        for(var edge:remove)repo.remove(edge,new ArrayList<>());
    }
    private void validateRules(Map<String,Object> rules){validateRules(rules,false);}
    private void validateRules(Map<String,Object> rules,boolean form){
        if(form)fields(rules,"alwaysCreate","matchEmpty","rootId");else fields(rules,"alwaysCreate","matchEmpty");
        if(rules.containsKey("alwaysCreate")){if(!(rules.get("alwaysCreate") instanceof List<?>))c.fail("VALIDATION_FAILED","alwaysCreate must be a list");var seen=new HashSet<String>();for(Object raw:(List<?>)rules.get("alwaysCreate"))if(!seen.add(BaseService.name(raw)))c.fail("VALIDATION_FAILED","alwaysCreate requires unique Class names");}
        if(rules.containsKey("matchEmpty")&&!(rules.get("matchEmpty") instanceof Boolean))c.fail("VALIDATION_FAILED","matchEmpty must be Boolean");
        if(rules.containsKey("rootId"))RequestContext.uuid(rules.get("rootId"));
    }
    private long number(Node node,String key,long fallback){return ((Number)node.getProperty(key,fallback)).longValue();}
}
