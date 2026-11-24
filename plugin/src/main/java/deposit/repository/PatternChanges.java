package deposit.repository;

import deposit.common.*;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Pattern and schema facts for one locked domain action. */
public final class PatternChanges {
    public record Definition(Node node, Map<String,Object> data) {}
    public record RemovedDeclaration(String classId, String oldClassName, String field) {}
    private final CoreRepository core;
    private final String namespace;
    private final Runnable check;
    private final Set<String> classNames = new HashSet<>();
    private final Map<String,String> oldClassNames = new HashMap<>();
    private final Map<String,Set<String>> fieldsByClassId = new HashMap<>();
    private final Set<String> newDeclarations = new HashSet<>();
    private final List<RemovedDeclaration> removed = new ArrayList<>();
    private final Map<String,Map<String,Object>> oldDefinitions = new HashMap<>();
    private Map<String,Definition> definitions;
    private final Map<String,Set<String>> patternsByClass = new HashMap<>();

    public PatternChanges(CoreRepository core, String namespace, Runnable check) {
        this.core=core;this.namespace=namespace;this.check=check;
    }

    void classChanged(Node node, String oldName, String newName) {
        if(oldName!=null) {
            oldClassNames.putIfAbsent(CoreRepository.id(node),oldName);
            classNames.add(oldName);
        }
        if(newName!=null)classNames.add(newName);
    }

    void declarationChanged(Node node) {
        fieldsByClassId.computeIfAbsent((String)node.getProperty("ownerId"),id->new HashSet<>())
            .add((String)node.getProperty("name"));
    }

    void declarationCreated(Node node) {
        declarationChanged(node);
        newDeclarations.add(CoreRepository.id(node));
    }

    void declarationRemoved(Node node) {
        declarationChanged(node);
        if(newDeclarations.remove(CoreRepository.id(node)))return;
        Node clazz=core.owner(node);String classId=CoreRepository.id(clazz);
        removed.add(new RemovedDeclaration(classId,oldClassNames.getOrDefault(classId,(String)clazz.getProperty("name")),
            (String)node.getProperty("name")));
    }

    void definitionChanging(Node node) {
        String id=CoreRepository.id(node);
        if(!oldDefinitions.containsKey(id))
            oldDefinitions.put(id,definitions==null?read(node):definitions.get(id).data());
    }

    void patternCreated(Node node) {
        oldDefinitions.put(CoreRepository.id(node),null);
        definitionChanged(node);
    }

    void definitionChanged(Node node) {
        if(definitions!=null) {
            removeDefinition(CoreRepository.id(node));
            index(new Definition(node,read(node)));
        }
    }

    void patternRemoved(Node node) {
        String id=CoreRepository.id(node);
        oldDefinitions.remove(id);
        if(definitions!=null)removeDefinition(id);
    }

    public Collection<Definition> definitions() {
        if(definitions==null) {
            core.observe("pattern-definitions-scan");
            definitions=new LinkedHashMap<>();
            try(var nodes=core.scanNodes("Pattern",Map.of("namespaceId",namespace))) {
                while(nodes.hasNext()) {
                    check.run();
                    Node node=nodes.next();
                    index(new Definition(node,read(node)));
                }
            }
        }
        return definitions.values();
    }

    public List<Definition> referencing(String className) {
        definitions();
        return patternsByClass.getOrDefault(className,Set.of()).stream().map(definitions::get).toList();
    }

    public boolean affected() {
        return !classNames.isEmpty()||!fieldsByClassId.isEmpty()||!oldDefinitions.isEmpty();
    }

    public Set<String> affectedClasses() {
        var names=new HashSet<>(classNames);
        for(var entry:fieldsByClassId.entrySet()) {
            Node clazz=core.entity("Class",namespace,entry.getKey());
            if(clazz==null)continue;
            String name=(String)clazz.getProperty("name");
            if(!names.contains(name)&&referencing(name).stream().anyMatch(pattern->references(pattern.data(),name,entry.getValue())))
                names.add(name);
        }
        for(String id:oldDefinitions.keySet()) {
            Definition pattern=definitions.get(id);
            if(pattern!=null)names.addAll(classNames(pattern.data()));
        }
        return names;
    }

    public List<RemovedDeclaration> removedDeclarations() {return removed;}

    public Map<String,Object> originalDefinition(Definition pattern) {
        return oldDefinitions.getOrDefault(CoreRepository.id(pattern.node()),pattern.data());
    }

    public static boolean references(Map<String,Object> definition, String clazz, Set<String> fields) {
        if(definition==null)return false;
        return roles(definition).stream().anyMatch(role->clazz.equals(role.get("className"))&&
            (fields.isEmpty()||!Collections.disjoint(fields,PatternCodec.referencedDescriptors(role))));
    }

    public static List<Map<String,Object>> roles(Map<String,Object> definition) {
        return ValueCodec.map(definition.get("objects")).values().stream().map(ValueCodec::map).toList();
    }

    private static Set<String> classNames(Map<String,Object> definition) {
        var names=new HashSet<String>();
        for(var role:roles(definition))names.add((String)role.get("className"));
        return names;
    }

    private static Map<String,Object> read(Node pattern) {
        return Json.read((String)pattern.getProperty("definition"));
    }

    private void index(Definition pattern) {
        String id=CoreRepository.id(pattern.node());
        definitions.put(id,pattern);
        for(String name:classNames(pattern.data()))patternsByClass.computeIfAbsent(name,key->new LinkedHashSet<>()).add(id);
    }

    private void removeDefinition(String id) {
        Definition old=definitions.remove(id);
        if(old!=null)for(String name:classNames(old.data()))patternsByClass.get(name).remove(id);
    }
}
