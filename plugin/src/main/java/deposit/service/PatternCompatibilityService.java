package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import deposit.repository.PatternChanges;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Owns resulting-Namespace Pattern/schema compatibility checks and canonicalization. */
final class PatternCompatibilityService extends BaseService {
    PatternCompatibilityService(RequestContext c){super(c);}

    void validateNamespace(String namespaceId){
        c.server.storagePoint("pattern-definitions-scan");
        var schema=repo.namespaceSchema(namespaceId);
        var definitions=new ArrayList<Map<String,Object>>();
        for(Node pattern:repo.nodes("Pattern",Map.of("namespaceId",namespaceId)))definitions.add(Json.read((String)pattern.getProperty("definition")));
        PatternCodec.validate(definitions,schema);
    }

    void validateDeclarationDeletion(Map<String,Map<String,Object>> before,Map<String,Map<String,Object>> after){
        for(var old:before.values())if("ClassDescriptor".equals(old.get("kind"))&&!after.containsKey(HistoryCodec.key(old))){Map<String,Object> clazz=HistoryCodec.find(before,old.get("classId"),"Class"),nextClass=clazz==null?null:HistoryCodec.find(after,clazz.get("id"),"Class");if(clazz==null||nextClass==null)continue;String previousName=(String)clazz.get("name"),nextName=(String)nextClass.get("name"),field=(String)old.get("name");
            for(var prior:before.values())if("Pattern".equals(prior.get("kind"))){Map<String,Object> current=HistoryCodec.find(after,prior.get("id"),"Pattern");if(current==null)continue;if(referencesField(prior,previousName,field)&&referencesField(current,nextName,field))c.fail("VALIDATION_FAILED","Class declaration is referenced by a Pattern: "+field);}
        }
    }
    private boolean referencesField(Map<String,Object> pattern,String className,String field){Map<String,Object> objects=ValueCodec.map(ValueCodec.map(pattern.get("definition")).get("objects"));for(Object raw:objects.values()){var role=ValueCodec.map(raw);if(!className.equals(role.get("className")))continue;for(Object descriptor:(List<?>)role.get("descriptors"))if(field.equals(ValueCodec.map(descriptor).get("name")))return true;}return false;}

    boolean validateAffected(PatternChanges changes) {
        changes.definitions();
        var classes=changes.affectedClasses();
        var patterns=new LinkedHashSet<PatternChanges.Definition>();
        var schema=new HashMap<String,Map<String,String>>();
        for(String name:classes) {
            var referenced=changes.referencing(name);
            if(referenced.isEmpty())continue;
            patterns.addAll(referenced);
            Node clazz=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name));
            if(clazz==null)continue; // The codec reports the unknown Class.
            var declarations=new HashMap<String,String>();
            schema.put(name,declarations);
            var fields=new HashSet<String>();
            for(var pattern:referenced)for(var role:PatternChanges.roles(pattern.data()))
                if(name.equals(role.get("className")))fields.addAll(PatternCodec.referencedDescriptors(role));
            for(String field:fields) {
                Node declaration=repo.declaration(clazz,field);
                if(declaration!=null)declarations.put(field,(String)declaration.getProperty("type"));
            }
        }
        PatternCodec.validateAffected(patterns.stream().map(PatternChanges.Definition::data).toList(),schema,classes);
        for(var removed:changes.removedDeclarations()) {
            Node clazz=repo.entity("Class",c.namespaceId,removed.classId());
            if(clazz==null)continue;
            String name=(String)clazz.getProperty("name");
            Set<String> field=Set.of(removed.field());
            for(var pattern:changes.referencing(name))
                if(PatternChanges.references(pattern.data(),name,field)&&
                    PatternChanges.references(changes.originalDefinition(pattern),removed.oldClassName(),field))
                    c.fail("VALIDATION_FAILED","Class declaration is referenced by a Pattern: "+removed.field());
        }
        boolean changed=false;
        for(var pattern:patterns)changed=canonicalize(pattern.node(),pattern.data(),schema)||changed;
        return changed;
    }

    boolean validateAndCanonicalizeNamespace() {
        c.server.storagePoint("pattern-definitions-scan");
        var schema=repo.namespaceSchema(c.namespaceId);
        var definitions=new ArrayList<Map<String,Object>>();
        boolean changed=false;
        for(Node pattern:repo.nodes("Pattern",Map.of("namespaceId",c.namespaceId))) {
            c.state.kernel.assertOpen();
            var definition=Json.read((String)pattern.getProperty("definition"));
            boolean rewritten=canonicalize(pattern,definition,schema);
            changed|=rewritten;
            definitions.add(rewritten?Json.read((String)pattern.getProperty("definition")):definition);
        }
        PatternCodec.validate(definitions,schema);
        return changed;
    }

    /** Copy only definitions that need a rewrite. The index also keeps their prior versions. */
    private boolean canonicalize(Node pattern,Map<String,Object> definition,Map<String,Map<String,String>> schema) {
        boolean rewrite=false;
        for(var role:PatternChanges.roles(definition)) {
            var declarations=schema.get(role.get("className"));
            if(declarations==null)continue;
            for(Object raw:(List<?>)role.get("descriptors")) {
                var descriptor=ValueCodec.map(raw);
                String type=declarations.get(descriptor.get("name"));
                if(type==null||!descriptor.containsKey("type"))continue;
                if(!type.equals(ValueCodec.type(descriptor.get("type"))))
                    c.fail("VALIDATION_FAILED","Pattern declaration proposal conflicts with Class schema");
                rewrite=true;
            }
        }
        if(!rewrite)return false;
        var canonical=Json.read(Json.write(definition));
        for(var role:PatternChanges.roles(canonical)) {
            var declarations=schema.get(role.get("className"));
            if(declarations==null)continue;
            for(Object raw:(List<?>)role.get("descriptors")) {
                var descriptor=ValueCodec.map(raw);
                if(declarations.containsKey(descriptor.get("name")))descriptor.remove("type");
            }
        }
        return repo.update(pattern,Map.of("definition",Json.write(canonical)),c.actorId);
    }
}
