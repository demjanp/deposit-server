package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;

/** Receiving-scope schema, candidate, mapping and conflict analysis for one validated snapshot. */
final class ImportSnapshotAnalysis {
    private final RequestContext c;
    private final CoreRepository repo;
    private final ResourceRepository resources;
    private final Path scratchRoot;

    ImportSnapshotAnalysis(RequestContext c,CoreRepository repo,ResourceRepository resources,Path scratchRoot){this.c=c;this.repo=repo;this.resources=resources;this.scratchRoot=scratchRoot;}


<T> T analyze(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules,Function<Map<String,Object>,T> persist){
    var writeBudget=ImportWriteBudget.forRequest(c);var scratchBudget=writeBudget.scratchLease();
    try(var indexes=new ImportScratch(scratchRoot,()->c.state.kernel.assertOpen(),scratchBudget::reserve,scratchBudget::close);var solutions=new DiskJsonList(indexes.directory,"optimal-",writeBudget);var conflicts=new DiskJsonList(indexes.directory,"conflicts-",writeBudget);var patternDetails=new DiskJsonList(indexes.directory,"pattern-details-",writeBudget)){
    Map<String,Object> graph=pkg.graph();
    var groups=new ArrayList<Map<String,Object>>();var schema=repo.namespaceSchema(c.namespaceId);
    Map<String,String> mapped=indexes.map();for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);String source=(String)clazz.get("name"),target=source;mapped.put(source,target);Map<String,String> declarations=schema.computeIfAbsent(target,k->new TreeMap<>());for(Object rd:(List<?>)clazz.get("descriptors")){var d=ValueCodec.map(rd);String old=declarations.putIfAbsent((String)d.get("name"),(String)d.get("type"));if(old!=null&&!old.equals(d.get("type")))conflicts.add(Json.map("kind","schema","className",target,"descriptor",d.get("name"),"sourceType",d.get("type"),"targetType",old));}}
    var patterns=new ArrayList<Map<String,Object>>();
    for(Object raw:(List<?>)graph.get("patterns")){var p=ValueCodec.map(raw);Map<String,Object> def=PatternCodec.normalize(ValueCodec.map(p.get("definition")));patterns.add(def);patternDetails.add(Json.map("name",p.get("name"),"objects",def.get("objects")));Node existing=repo.find("Pattern",Map.of("namespaceId",c.namespaceId,"name",p.get("name")));if(existing!=null&&(!ImportSemantics.samePatternDefinition((String)existing.getProperty("definition"),def,schema)||!Objects.equals(existing.getProperty("comment",""),p.getOrDefault("comment",""))))conflicts.add(Json.map("kind","patternName","name",p.get("name")));}
    for(Node stored:repo.nodes("Pattern",Map.of("namespaceId",c.namespaceId))){var definition=Json.read((String)stored.getProperty("definition"));patterns.add(definition);patternDetails.add(Json.map("name",stored.getProperty("name"),"objects",definition.get("objects")));}
    try{PatternCodec.validate(patterns,schema);}catch(RuntimeException invalid){conflicts.add(Json.map("kind","patternCompatibility","message","Incoming and receiving Patterns or schema are incompatible","_conflictRef","patterns"));}
    for(Object raw:(List<?>)graph.get("userTools")){var tool=ValueCodec.map(raw);Node existing=repo.find("UserTool",Map.of("namespaceId",c.namespaceId,"name",tool.get("name")));if(existing!=null&&!Objects.equals(existing.getProperty("data"),tool.get("data")))conflicts.add(Json.map("kind","userToolName","name",tool.get("name"),"incomingId",tool.get("id"),"receivingId",CoreRepository.id(existing)));}
    for(Object raw:(List<?>)graph.get("descriptors")){var descriptor=ValueCodec.map(raw);indexes.update("INSERT INTO descriptors(owner,name,body) VALUES(?,?,?)",descriptor.get("objectId"),descriptor.get("name"),ImportSemantics.encode(Json.map("value",descriptor.get("value"))));}
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
    if(rules.get("_formRootSource") instanceof String rootSource){for(Object raw:(List<?>)graph.get("objects")){String id=(String)ValueCodec.map(raw).get("id");indexes.update("INSERT INTO assignments(source,target) VALUES(?,?)",id,id.equals(rootSource)?rules.get("rootId"):null);}indexes.captureSolution(0);solutions.add(0L);solver.count=((Number)indexes.one("SELECT count(*) AS n FROM assignments").get("n")).longValue();solver.best=solver.count-1;}else solver.solve();
    if(solutions.isEmpty())conflicts.add(Json.map("kind","mappingIncomplete","message","No complete import mapping satisfies the source structure"));
    List<Map<String,Object>> advertised=new AbstractList<>(){
        public int size(){return solutions.size();}
        public Map<String,Object> get(int index){Objects.checkIndex(index,size());indexes.check();return indexes.solution(index);}
        public Iterator<Map<String,Object>> iterator(){return new Iterator<>(){int next;public boolean hasNext(){return next<size();}public Map<String,Object> next(){if(!hasNext())throw new NoSuchElementException();return get(next++);}};}
    };
    Map<String,Object> objectMappings=advertised.size()==1?advertised.getFirst():Map.of();
    if(advertised.size()>1)conflicts.add(Json.map("kind","mappingAmbiguity","conflictId","mapping","_conflictRef","mappings"));
    if(!rules.containsKey("_formRootSource")&&advertised.size()==1)for(Object raw:(List<?>)graph.get("relationships")){indexes.check();var edge=ValueCodec.map(raw);String s=(String)objectMappings.get(edge.get("sourceId")),t=(String)objectMappings.get(edge.get("targetId"));if(s!=null&&t!=null&&repo.relationship(repo.entity("Object",c.namespaceId,s),repo.entity("Object",c.namespaceId,t),(String)edge.get("label"))==null)conflicts.add(Json.map("kind","relationship","sourceId",edge.get("sourceId"),"targetId",edge.get("targetId"),"label",edge.get("label")));}
    if(advertised.size()==1)for(Object raw:(List<?>)graph.get("descriptors")){indexes.check();var descriptor=ValueCodec.map(raw);String sourceId=(String)descriptor.get("objectId"),targetId=(String)objectMappings.get(sourceId);if(targetId==null||sourceId.equals(rules.get("_formRootSource")))continue;Node target=repo.entity("Object",c.namespaceId,targetId),existing=repo.descriptor(target,(String)descriptor.get("name"));if(existing==null)continue;Node declaration=repo.declaration(repo.objectClass(target),(String)descriptor.get("name"));if(declaration==null)continue;String type=(String)declaration.getProperty("type");Object incoming=ExchangePackageCodec.decodeValue(type,descriptor.get("value")),current=repo.descriptorValue(existing);boolean equal="DResource".equals(type)?ImportSemantics.resourceEquivalent(resources,incoming,current,graph,c.namespaceId):Objects.equals(incoming,current);if(!equal)conflicts.add(Json.sourceRecord(ImportSemantics.encode(Json.map("kind","value","conflictId","field:"+sourceId+":"+descriptor.get("name"),"sourceId",ValueCodec.map(rules.getOrDefault("_tabularIds",Map.of())).getOrDefault(sourceId,sourceId),"descriptor",descriptor.get("name"),"incoming",descriptor.get("value"),"receiving",current))));}
    if(advertised.size()==1)ImportCoalescedConflicts.appendCoalescedDescriptorConflicts(graph,objectMappings,rules,indexes,conflicts);
    long objectCount=longSize((Collection<?>)graph.get("objects")),classCount=longSize((Collection<?>)graph.get("classes")),relationshipCount=longSize((Collection<?>)graph.get("relationships"));
    groups.add(Json.map("groupId","all","objects",objectCount,"dependencies",List.of()));
    Map<String,Object> summary=Json.map("classMap",mapped,"objectMappings",objectMappings,"candidateMappings",advertised,"groups",groups,"conflicts",conflictView(conflicts,advertised,patternDetails,schema.keySet()),"summary",Json.map("classes",classCount,"objects",objectCount,"relationships",relationshipCount,"createObjects",solver.best,"reuseObjects",solver.count-solver.best));
    return persist.apply(summary);
    }catch(IOException failure){throw new UncheckedIOException(failure);}
}

private static long longSize(Collection<?> values){if(values instanceof LongSized sized)return sized.longSize();return values.size();}

static Collection<Object> conflictView(DiskJsonList rows,List<Map<String,Object>> mappings,DiskJsonList patternDetails,Set<String> classes){
    return new AbstractCollection<>(){
        public int size(){return rows.size();}
        public Iterator<Object> iterator(){Iterator<Object> source=rows.iterator();return new Iterator<>(){
            public boolean hasNext(){return source.hasNext();}
            public Object next(){Map<String,Object> raw=ValueCodec.map(source.next());Object reference=raw.remove("_conflictRef");if("mappings".equals(reference))raw.put("candidateMappings",mappings);else if("patterns".equals(reference)){raw.put("patterns",patternDetails);raw.put("classes",classes);}return raw;}
        };}
    };
}

private String sourceClassName(Map<String,Object> graph,String classId){for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);if(classId.equals(clazz.get("id")))return (String)clazz.get("name");}c.fail("VALIDATION_FAILED","Object Class is missing from package");return "";}
private boolean alwaysCreate(String source,String className,Map<String,Object> rules,ImportScratch indexes){if(ImportSemantics.forcedClass(className,rules))return true;try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",source)){for(var role:roles)if(Boolean.TRUE.equals(Json.sourceRecord((String)role.get("body")).get("alwaysCreate")))return true;}return false;}
private boolean matchesObject(String sourceId,String sourceClass,Node candidate,Map<String,Object> graph,Map<String,Object> rules,ImportScratch indexes){
    boolean patternRole=false;List<?> blanks=(List<?>)ValueCodec.map(rules.getOrDefault("_tabularBlanks",Map.of())).getOrDefault(sourceId,List.of());
    try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",sourceId)){for(var raw:roles){var spec=Json.sourceRecord((String)raw.get("body"));patternRole=true;boolean matchEmpty=Boolean.TRUE.equals(spec.get("matchEmpty"));for(Object descriptor:(List<?>)spec.get("descriptors")){indexes.check();String field=(String)ValueCodec.map(descriptor).get("name");if(TabularImportSemantics.shouldIgnoreMatchingField(blanks,field))continue;var value=indexes.one("SELECT body FROM descriptors WHERE owner=? AND name=?",sourceId,field);if(!matchesField(candidate,field,value!=null,value==null?null:Json.sourceRecord((String)value.get("body")).get("value"),matchEmpty,graph))return false;}}}
    if(!patternRole){boolean matchEmpty=Boolean.TRUE.equals(rules.getOrDefault("matchEmpty",false));try(var values=indexes.query("SELECT name,body FROM descriptors WHERE owner=? ORDER BY name",sourceId)){for(var value:values){Object incoming=Json.sourceRecord((String)value.get("body")).get("value");if((matchEmpty||incoming!=null)&&!matchesField(candidate,(String)value.get("name"),true,incoming,matchEmpty,graph))return false;}}}
    return true;
}
/** Applicability depends only on complete source Class/edge bindings, never Descriptor values. */
private void indexApplicablePatterns(Map<String,Object> graph,ImportScratch indexes){
    for(Object raw:(List<?>)graph.get("patterns")){var roles=ValueCodec.map(ValueCodec.map(ValueCodec.map(raw).get("definition")).get("objects"));var visited=new HashSet<String>();
        for(String start:roles.keySet()){if(visited.contains(start))continue;var component=ImportSemantics.connectedRoles(start,roles);visited.addAll(component);var order=new ArrayList<>(component);Collections.sort(order);var chosen=new LinkedHashMap<String,String>();String[] after=new String[order.size()];int depth=0;
            while(depth>=0){indexes.check();String role=order.get(depth),className=(String)ValueCodec.map(roles.get(role)).get("className");Map<String,Object> next=after[depth]==null?indexes.one("SELECT id FROM sources WHERE className=? ORDER BY id LIMIT 1",className):indexes.one("SELECT id FROM sources WHERE className=? AND id>? ORDER BY id LIMIT 1",className,after[depth]);
                if(next==null){after[depth]=null;chosen.remove(role);depth--;continue;}String id=(String)next.get("id");after[depth]=id;chosen.put(role,id);if(!sourceBindingCompatible(roles,chosen,indexes)){chosen.remove(role);continue;}
                if(depth==order.size()-1){for(var binding:chosen.entrySet())indexes.update("INSERT OR IGNORE INTO matching_rules(source,body) VALUES(?,?)",binding.getValue(),ImportSemantics.encode(roles.get(binding.getKey())));chosen.remove(role);}else{depth++;after[depth]=null;}
            }
        }
    }
}
private boolean sourceBindingCompatible(Map<String,Object> roles,Map<String,String> chosen,ImportScratch indexes){for(var entry:chosen.entrySet())for(Object raw:(List<?>)ValueCodec.map(roles.get(entry.getKey())).getOrDefault("relationships",List.of())){var edge=ValueCodec.map(raw);String target=chosen.get(edge.get("targetName"));if(target!=null&&indexes.one("SELECT 1 AS found FROM edges WHERE source=? AND target=? AND label=? LIMIT 1",entry.getValue(),target,edge.get("label"))==null)return false;}return true;}
private boolean matchesField(Node candidate,String field,boolean present,Object incoming,boolean matchEmpty,Map<String,Object> graph){Node target=repo.descriptor(candidate,field);if(!present)return !matchEmpty||target==null;if(incoming==null&&!matchEmpty)return true;if(target==null)return false;Node declaration=repo.declaration(repo.objectClass(candidate),field);if(declaration==null)return false;String type=(String)declaration.getProperty("type");Object decoded;try{decoded=ExchangePackageCodec.decodeValue(type,incoming);}catch(RuntimeException invalid){return false;}Object actual=repo.descriptorValue(target);return "DResource".equals(type)?ImportSemantics.resourceEquivalent(resources,decoded,actual,graph,candidate.getProperty("namespaceId").toString()):Objects.equals(decoded,actual);}



private boolean hasMatchingInput(String source,String className,Map<String,Object> graph,Map<String,Object> rules,ImportScratch indexes){
    List<?> blanks=(List<?>)ValueCodec.map(rules.getOrDefault("_tabularBlanks",Map.of())).getOrDefault(source,List.of());boolean applicable=false;try(var roles=indexes.query("SELECT body FROM matching_rules WHERE source=?",source)){for(var raw:roles){var role=Json.sourceRecord((String)raw.get("body"));applicable=true;for(Object d:(List<?>)role.get("descriptors")){String name=(String)ValueCodec.map(d).get("name");if(TabularImportSemantics.shouldIgnoreMatchingField(blanks,name))continue;var value=indexes.one("SELECT body FROM descriptors WHERE owner=? AND name=?",source,name);if(Boolean.TRUE.equals(role.get("matchEmpty"))||value!=null&&Json.sourceRecord((String)value.get("body")).get("value")!=null)return true;}}}
    if(applicable)return false;
    try(var values=indexes.query("SELECT body FROM descriptors WHERE owner=?",source)){for(var value:values){if(Boolean.TRUE.equals(rules.get("matchEmpty"))||Json.sourceRecord((String)value.get("body")).get("value")!=null)return true;}}return false;
}


}
