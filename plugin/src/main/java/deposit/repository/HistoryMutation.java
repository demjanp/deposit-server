package deposit.repository;

import deposit.common.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Collects scientific images at repository write boundaries in one locked domain action. */
public final class HistoryMutation {
    private final CoreRepository core;
    private final HistoryRepository history;
    private final PatternChanges patterns;
    private final String namespace;
    private final Runnable check;
    private final ChangeSet changes;
    private final Map<String,Entity> pending=new HashMap<>();
    private ChangeSet.Images prior=new ChangeSet.Images();

    public HistoryMutation(CoreRepository core,HistoryRepository history,PatternChanges patterns,
            String namespace,Runnable check,ChangeSet changes) {
        this.core=core;this.history=history;this.patterns=patterns;this.namespace=namespace;this.check=check;this.changes=changes;
    }

    void created(Entity entity) {
        String key=key(entity);
        if(key!=null)pending.put(key,entity);
    }

    public void changing(Entity entity) {
        String key=key(entity);
        if(key!=null&&!pending.containsKey(key)) {
            history.captureImages(entity,prior);
            pending.put(key,entity);
        }
    }

    void deleting(Entity entity) {
        String key=key(entity);
        if(key==null)return;
        changing(entity);
        pending.put(key,null);
    }

    // Membership and geometry images are captured with their owning Object and Descriptor.
    private static String key(Entity entity) {
        String kind=entity instanceof Node node?CoreRepository.kind(node):CoreRepository.kind((Relationship)entity);
        if(!HistoryCodec.KINDS.contains(kind)||kind.equals("Membership")||kind.equals("DGeometry"))return null;
        return HistoryCodec.key(kind,CoreRepository.id(entity));
    }

    public void checkpoint() {
        if(pending.isEmpty())return;
        var next=new ChangeSet.Images();
        for(Entity entity:pending.values()) {
            check.run();
            if(entity!=null)history.captureImages(entity,next);
        }
        var step=new Step(prior.state(),next.state());
        changes.checkpointAffected(prior,next,(image,before)->"Descriptor".equals(image.get("kind"))?
            (before?prior:next).descriptorSchema().get(HistoryCodec.key(image)):step.schemaReferences(image,before),
            this::reverseReferences);
        pending.clear();prior=new ChangeSet.Images();
    }

    private Set<String> reverseReferences(Map<String,Object> image) {
        String kind=(String)image.get("kind");
        var types=switch(kind) {
            case "Class", "Object" -> new String[]{"RELATED_TO","MEMBER_OF","HAS_DESCRIPTOR"};
            case "Descriptor", "DGeometry" -> new String[]{"HAS_GEOMETRY"};
            default -> new String[0];
        };
        if(types.length==0)return Set.of();
        Node node=kind.equals("DGeometry")?core.entity(kind,namespace,(String)image.get("id")):
            (Node)pending.get(HistoryCodec.key(image));
        var result=new HashSet<String>();
        try(var edges=node.getRelationships(Arrays.stream(types).map(RelationshipType::withName).toArray(RelationshipType[]::new))) {
            for(var edge:edges) {
                check.run();
                switch(edge.getType().name()) {
                    case "HAS_DESCRIPTOR" -> {
                        if(edge.getStartNode().equals(node)) {
                            Node child=edge.getEndNode();
                            result.add(HistoryCodec.key(CoreRepository.kind(child),CoreRepository.id(child)));
                        }
                    }
                    case "HAS_GEOMETRY" -> {
                        Node other=edge.getOtherNode(node);
                        result.add(HistoryCodec.key(CoreRepository.kind(other),CoreRepository.id(other)));
                    }
                    default -> result.add(HistoryCodec.key(CoreRepository.kind(edge),CoreRepository.id(edge)));
                }
            }
        }
        return result;
    }

    /** Look up only the current neighbors and overlay the command's preimages for the old side. */
    private final class Step {
        private record Field(String classId,String name) {}
        private final Map<String,Map<String,Object>> prior, next;
        private final Map<String,Map<String,Object>> oldClassesByName=new HashMap<>();
        private final Map<Field,Map<String,Object>> oldDeclarations=new HashMap<>();
        private final Map<String,List<Map<String,Object>>> oldPatternsByClass=new HashMap<>();

        Step(Map<String,Map<String,Object>> prior,Map<String,Map<String,Object>> next) {
            this.prior=prior;this.next=next;
            for(var image:prior.values())switch((String)image.get("kind")) {
                case "Class" -> oldClassesByName.put((String)image.get("name"),image);
                case "ClassDescriptor" -> oldDeclarations.put(new Field((String)image.get("classId"),(String)image.get("name")),image);
                case "Pattern" -> {
                    var names=new HashSet<String>();
                    for(var role:PatternChanges.roles(ValueCodec.map(image.get("definition"))))names.add((String)role.get("className"));
                    for(String name:names)oldPatternsByClass.computeIfAbsent(name,key->new ArrayList<>()).add(image);
                }
                default -> {}
            }
        }

        Set<String> schemaReferences(Map<String,Object> image,boolean before) {
            var neighborhood=new HashMap<String,Map<String,Object>>();
            switch((String)image.get("kind")) {
                case "Pattern" -> {
                    for(var role:PatternChanges.roles(ValueCodec.map(image.get("definition")))) {
                        String name=(String)role.get("className");
                        var clazz=before?oldClassesByName.get(name):null;
                        if(clazz==null)clazz=imageOf(core.find("Class",Map.of("namespaceId",namespace,"name",name)),before);
                        if(clazz==null||!name.equals(clazz.get("name")))continue;
                        add(neighborhood,clazz);
                        for(String field:PatternCodec.referencedDescriptors(role))
                            add(neighborhood,declaration((String)clazz.get("id"),field,before));
                    }
                }
                case "Class" -> addPatterns(neighborhood,(String)image.get("name"),before);
                case "ClassDescriptor" -> {
                    String owner=(String)image.get("classId");
                    var clazz=before?HistoryCodec.find(prior,owner,"Class"):null;
                    if(clazz==null)clazz=imageOf(core.entity("Class",namespace,owner),before);
                    if(clazz!=null) {
                        add(neighborhood,clazz);
                        addPatterns(neighborhood,(String)clazz.get("name"),before);
                    }
                }
                default -> {return Set.of();}
            }
            return HistoryCodec.schemaReferences(neighborhood,image);
        }

        private void addPatterns(Map<String,Map<String,Object>> neighborhood,String name,boolean before) {
            for(var pattern:patterns.referencing(name)) {
                String key=HistoryCodec.key("Pattern",CoreRepository.id(pattern.node()));
                if(!before||!next.containsKey(key))
                    neighborhood.put(key,Json.map("kind","Pattern","id",CoreRepository.id(pattern.node()),"definition",pattern.data()));
            }
            if(before)for(var pattern:oldPatternsByClass.getOrDefault(name,List.of()))add(neighborhood,pattern);
        }

        private Map<String,Object> declaration(String classId,String name,boolean before) {
            var image=before?oldDeclarations.get(new Field(classId,name)):null;
            if(image!=null)return image;
            Node node=core.find("ClassDescriptor",Map.of("namespaceId",namespace,"ownerId",classId,"name",name));
            return imageOf(node,before);
        }

        private Map<String,Object> imageOf(Node entity,boolean before) {
            if(entity==null)return null;
            String kind=CoreRepository.kind(entity);
            String id=CoreRepository.id(entity),key=HistoryCodec.key(kind,id);
            if(before&&(prior.containsKey(key)||next.containsKey(key)))return prior.get(key);
            if(next.containsKey(key))return next.get(key);
            return switch(kind) {
                case "Class" -> Json.map("kind",kind,"id",id,"name",entity.getProperty("name"));
                case "ClassDescriptor" -> Json.map("kind",kind,"id",id,"classId",entity.getProperty("ownerId"),"name",entity.getProperty("name"));
                default -> throw new IllegalStateException("Unexpected schema image: "+kind);
            };
        }

        private void add(Map<String,Map<String,Object>> images,Map<String,Object> image) {
            if(image!=null)images.put(HistoryCodec.key(image),image);
        }
    }
}
