package deposit.common;

import java.text.Normalizer;
import java.util.*;

/** Pure Pattern definition and resulting-schema validation shared by live state and provenance. */
public final class PatternCodec {
    private PatternCodec() {}

    /** A lookup-only schema over indexes owned by the caller. */
    static Map<String,Map<String,String>> lookupSchema(Map<String,String> classNames,Map<String,String> declarations) {
        return new AbstractMap<>() {
            public Map<String,String> get(Object name) {
                String id=classNames.get(name);
                if(id==null)return null;
                return new AbstractMap<>() {
                    public String get(Object field){return declarations.get(id+"\u0000"+field);}
                    public boolean containsKey(Object field){return get(field)!=null;}
                    public Set<Entry<String,String>> entrySet(){throw new UnsupportedOperationException("Lookup-only declaration view");}
                };
            }
            public boolean containsKey(Object name){throw new UnsupportedOperationException("Lookup-only schema view");}
            public Set<Entry<String,Map<String,String>>> entrySet(){throw new UnsupportedOperationException("Lookup-only schema view");}
        };
    }

    public static Map<String,Object> normalize(Map<String,Object> source) {
        fields(source,"objects");
        if(!(source.get("objects") instanceof Map<?,?>))fail("Pattern objects must be a map");
        var objects=new TreeMap<String,Object>();
        for(var entry:ValueCodec.map(source.get("objects")).entrySet()) {
            String role=name(entry.getKey());
            if(objects.containsKey(role))fail("Duplicate normalized Pattern Object name");
            var item=new LinkedHashMap<>(ValueCodec.map(entry.getValue()));
            fields(item,"className","root","alwaysCreate","matchEmpty","comment","descriptors","relationships");
            item.put("className",name(item.get("className")));
            item.put("root",bool(item.getOrDefault("root",false)));
            item.put("alwaysCreate",bool(item.getOrDefault("alwaysCreate",false)));
            item.put("matchEmpty",bool(item.getOrDefault("matchEmpty",false)));
            item.put("comment",text(item.getOrDefault("comment","")));
            var descriptors=new ArrayList<Object>();var descriptorNames=new HashSet<String>();
            Object rawDescriptors=item.getOrDefault("descriptors",List.of());
            if(!(rawDescriptors instanceof List<?>))fail("Pattern descriptors must be a list");
            for(Object raw:(List<?>)rawDescriptors) {
                var descriptor=new LinkedHashMap<>(ValueCodec.map(raw));fields(descriptor,"name","type","comment");
                String field=name(descriptor.get("name"));if(!descriptorNames.add(field))fail("Duplicate Pattern Descriptor");
                descriptor.put("name",field);
                if(descriptor.containsKey("type"))descriptor.put("type",ValueCodec.type(descriptor.get("type")));
                descriptor.put("comment",text(descriptor.getOrDefault("comment","")));descriptors.add(descriptor);
            }
            item.put("descriptors",descriptors);
            var relationships=new ArrayList<Object>();var edgeKeys=new HashSet<String>();
            Object rawRelationships=item.getOrDefault("relationships",List.of());
            if(!(rawRelationships instanceof List<?>))fail("Pattern relationships must be a list");
            for(Object raw:(List<?>)rawRelationships) {
                var edge=new LinkedHashMap<>(ValueCodec.map(raw));fields(edge,"label","targetName","comment");
                String label=name(edge.get("label")),target=name(edge.get("targetName"));
                if(!edgeKeys.add(label+"\u0000"+target))fail("Duplicate Pattern relationship");
                edge.put("label",label);edge.put("targetName",target);edge.put("comment",text(edge.getOrDefault("comment","")));relationships.add(edge);
            }
            item.put("relationships",relationships);
            objects.put(role,item);
        }
        if(objects.isEmpty())fail("Pattern must contain objects");
        validateRoots(objects);
        return Json.map("objects",objects);
    }

    public static void validate(Collection<Map<String,Object>> definitions,Map<String,? extends Map<String,String>> schema) {
        validate(definitions,schema,null);
    }
    static void validate(Collection<Map<String,Object>> definitions,Map<String,? extends Map<String,String>> schema,PackageWorkspace workspace) {
        if(workspace!=null){validateStored(definitions,schema,workspace);return;}
        validateMatching(definitions,schema,null);
    }

    /** Live definitions are normalized at the write boundary. Compare matching rules for affected classes. */
    public static void validateAffected(Collection<Map<String,Object>> definitions,Map<String,? extends Map<String,String>> schema,Set<String> classes) {
        validateMatching(definitions,schema,classes);
    }

    private static void validateMatching(Collection<Map<String,Object>> definitions,Map<String,? extends Map<String,String>> schema,Set<String> classes) {
        Map<String,String> tuples=new HashMap<>(),proposals=new HashMap<>();
        for(var definition:definitions)for(Object raw:ValueCodec.map((classes==null?normalize(definition):definition).get("objects")).values()) {
            var role=ValueCodec.map(raw);String clazz=(String)role.get("className");
            if(classes!=null&&!classes.contains(clazz))continue;
            Map<String,String> declarations=schema.get(clazz);
            if(declarations==null)fail("Pattern references an unknown Class: "+clazz);
            var available=new HashSet<String>();
            for(Object rawDescriptor:(List<?>)role.get("descriptors")) {
                var descriptor=ValueCodec.map(rawDescriptor);String field=(String)descriptor.get("name"),declared=declarations.get(field);
                String proposal=descriptor.containsKey("type")?ValueCodec.type(descriptor.get("type")):null;
                if(declared!=null&&proposal!=null&&!declared.equals(proposal))fail("Pattern declaration proposal conflicts with Class schema");
                if(declared==null) {
                    if(proposal==null)fail("Pattern field without a Class declaration requires a type proposal");
                    String old=proposals.putIfAbsent(clazz+"\u0000"+field,proposal);
                    if(old!=null&&!old.equals(proposal))fail("Pattern declaration proposals conflict for "+clazz+"."+field);
                }
                available.add(field);
            }
            var matchingNames=new TreeSet<String>();for(Object rawDescriptor:(List<?>)role.get("descriptors"))matchingNames.add((String)ValueCodec.map(rawDescriptor).get("name"));
            String tuple=Json.write(List.of(role.get("alwaysCreate"),matchingNames,role.get("matchEmpty")));
            String old=tuples.putIfAbsent(clazz,tuple);if(old!=null&&!old.equals(tuple))fail("Pattern matching rules conflict for Class: "+clazz);
        }
    }

    /** Validate external definitions with spill indexes instead of materializing their normalized form. */
    private static void validateStored(Collection<Map<String,Object>> definitions,Map<String,? extends Map<String,String>> schema,PackageWorkspace workspace){
        Map<String,String> tuples=workspace.map(),proposals=workspace.map();Set<String> matchingKeys=workspace.set();
        for(var definition:definitions){int mark=workspace.mark();try{
            fields(definition,"objects");var roles=ValueCodec.map(definition.get("objects"));PackageWorkspace.requireNameKeys(roles);if(roles.isEmpty())fail("Pattern must contain objects");
            Map<String,String> parents=workspace.map();Map<String,Long> roots=workspace.map();
            for(var entry:roles.entrySet()){String role=name(entry.getKey());if(parents.put(role,role)!=null)fail("Duplicate normalized Pattern Object name");}
            for(var entry:roles.entrySet()){
                String roleName=name(entry.getKey());var role=ValueCodec.map(entry.getValue());fields(role,"className","root","alwaysCreate","matchEmpty","comment","descriptors","relationships");
                String clazz=name(role.get("className"));Map<String,String> declarations=schema.get(clazz);if(declarations==null)fail("Pattern references an unknown Class");
                boolean root=bool(role.getOrDefault("root",false)),always=bool(role.getOrDefault("alwaysCreate",false)),empty=bool(role.getOrDefault("matchEmpty",false));text(role.getOrDefault("comment",""));
                int roleMark=workspace.mark();Set<String> available=workspace.set(),edges=workspace.set(),keys=workspace.set();
                try{
                    Object rawDescriptors=role.getOrDefault("descriptors",List.of());if(!(rawDescriptors instanceof List<?>))fail("Pattern descriptors must be a list");
                    for(Object raw:(List<?>)rawDescriptors){var d=ValueCodec.map(raw);fields(d,"name","type","comment");String field=name(d.get("name"));if(!available.add(field))fail("Duplicate Pattern Descriptor");text(d.getOrDefault("comment",""));String declared=declarations.get(field),proposal=d.containsKey("type")?ValueCodec.type(d.get("type")):null;
                        if(declared!=null&&proposal!=null&&!declared.equals(proposal))fail("Pattern declaration proposal conflicts with Class schema");
                        if(declared==null){if(proposal==null)fail("Pattern field requires a type proposal");String old=proposals.putIfAbsent(clazz+"\u0000"+field,proposal);if(old!=null&&!old.equals(proposal))fail("Pattern declaration proposals conflict");}
                    }
                    for(Object raw:(List<?>)role.getOrDefault("descriptors",List.of()))keys.add((String)ValueCodec.map(raw).get("name"));
                    String tuple=Json.write(List.of(always,empty,new TreeSet<>(keys))),old=tuples.putIfAbsent(clazz,tuple);if(old!=null&&!old.equals(tuple))fail("Pattern matching rules conflict");
                    for(String key:keys){String qualified=clazz+"\u0000"+key;if(old==null)matchingKeys.add(qualified);else if(!matchingKeys.contains(qualified))fail("Pattern matching rules conflict");}
                    Object rawRelationships=role.getOrDefault("relationships",List.of());if(!(rawRelationships instanceof List<?>))fail("Pattern relationships must be a list");
                    for(Object raw:(List<?>)rawRelationships){var edge=ValueCodec.map(raw);fields(edge,"label","targetName","comment");String label=name(edge.get("label")),target=name(edge.get("targetName"));text(edge.getOrDefault("comment",""));if(!edges.add(label+"\u0000"+target))fail("Duplicate Pattern relationship");if(!parents.containsKey(target))fail("Pattern relationship target is missing");String left=root(parents,roleName),right=root(parents,target);if(!left.equals(right))parents.put(left,right);}
                    if(root)roots.put(roleName,1L);
                }finally{try{workspace.release(roleMark);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
            }
            Map<String,Long> counts=workspace.map();
            for(String role:parents.keySet()){String group=root(parents,role);counts.put(group,counts.getOrDefault(group,0L)+roots.getOrDefault(role,0L));}
            for(Long count:counts.values())if(count!=1)fail("Each connected Pattern group requires exactly one root");
        }finally{try{workspace.release(mark);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}}
    }
    private static String root(Map<String,String> parents,String role){String root=role;while(!root.equals(parents.get(root)))root=parents.get(root);while(!role.equals(root)){String next=parents.put(role,root);role=next;}return root;}

    public static Set<String> referencedDescriptors(Map<String,Object> role) {
        var names=new TreeSet<String>();
        for(Object raw:(List<?>)role.getOrDefault("descriptors",List.of()))names.add((String)ValueCodec.map(raw).get("name"));
        return names;
    }

    private static void validateRoots(Map<String,Object> roles) {
        var adjacent=new TreeMap<String,Set<String>>();roles.keySet().forEach(key->adjacent.put(key,new TreeSet<>()));
        for(var role:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(role.getValue()).get("relationships")) {
            String target=(String)ValueCodec.map(raw).get("targetName");if(!roles.containsKey(target))fail("Pattern relationship target is missing");
            adjacent.get(role.getKey()).add(target);adjacent.get(target).add(role.getKey());
        }
        var seen=new HashSet<String>();
        for(String start:roles.keySet()) {
            if(!seen.add(start))continue;int roots=0;var queue=new ArrayDeque<String>();queue.add(start);
            while(!queue.isEmpty()) {
                String role=queue.remove();if(Boolean.TRUE.equals(ValueCodec.map(roles.get(role)).get("root")))roots++;
                for(String next:adjacent.get(role))if(seen.add(next))queue.add(next);
            }
            if(roots!=1)fail("Each connected Pattern group requires exactly one root");
        }
    }
    private static void fields(Map<String,Object> map,String... names) {if(map==null)fail("Pattern must be a map");var allowed=Set.of(names);for(String key:map.keySet())if(!allowed.contains(key))fail("Unknown Pattern field: "+key);}
    private static String text(Object raw) {if(!(raw instanceof String))fail("Expected String");return (String)raw;}
    private static String name(Object raw) {String name=Normalizer.normalize(text(raw).strip(),Normalizer.Form.NFC);if(name.isEmpty()||name.codePointCount(0,name.length())>255||name.codePoints().anyMatch(Character::isISOControl))fail("Invalid Pattern name");return name;}
    private static boolean bool(Object raw) {if(!(raw instanceof Boolean))fail("Expected Boolean");return (Boolean)raw;}
    private static void fail(String message) {throw ExceptionHandler.fail("VALIDATION_FAILED",message);}
}
