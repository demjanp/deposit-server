package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Owns resulting-Namespace Pattern/schema compatibility checks and canonicalization. */
final class PatternCompatibilityService extends BaseService {
    PatternCompatibilityService(RequestContext c){super(c);}

    void validateNamespace(){validateNamespace(c.namespaceId);}
    void validateNamespace(String namespaceId){
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

    /** Canonicalize resolved proposals inside the caller's existing action/transaction. */
    boolean canonicalizeResolvedDeclarations(){
        boolean any=false;
        for(Node pattern:repo.nodes("Pattern",Map.of("namespaceId",c.namespaceId))){Map<String,Object> definition=Json.read((String)pattern.getProperty("definition"));Map<String,Object> roles=ValueCodec.map(definition.get("objects"));boolean changed=false;
            for(Object raw:roles.values()){var role=ValueCodec.map(raw);Node clazz=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",role.get("className")));if(clazz==null)continue;
                for(Object rawDescriptor:(List<?>)role.get("descriptors")){var descriptor=ValueCodec.map(rawDescriptor);if(!descriptor.containsKey("type"))continue;Node declaration=repo.declaration(clazz,(String)descriptor.get("name"));if(declaration==null)continue;
                    if(!ValueCodec.type(descriptor.get("type")).equals(declaration.getProperty("type")))c.fail("VALIDATION_FAILED","Pattern declaration proposal conflicts with Class schema");descriptor.remove("type");changed=true;
                }
            }
            if(changed)any|=repo.update(pattern,Map.of("definition",Json.write(definition)),c.actorId);
        }return any;
    }
}
