package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

public final class ReadService extends BaseService {
    public ReadService(RequestContext c) {super(c);}
    private Map<String,Object> discoveryRequest;
    private String discoveryFingerprint;
    private boolean discoveryMode;
    private List<Node> namespaces(Map<String,Object> request) {
        List<Node> nodes=repo.nodes("Namespace",Map.of()).stream().filter(n->effectiveRole(CoreRepository.id(n))!=null).toList();
        if(request.containsKey("id")) {String id=RequestContext.uuid(request.get("id"));nodes=nodes.stream().filter(n->id.equals(CoreRepository.id(n))).toList();}
        if(request.containsKey("name")) {String name=name(request.get("name"));nodes=nodes.stream().filter(n->name.equals(n.getProperty("name"))).toList();}
        return nodes;
    }
    private Map<String,Object> namespaceData(Node n) {var data=repo.data(n);data.put("role",effectiveRole(CoreRepository.id(n)));return data;}
    private String fingerprint(List<Map<String,Object>> data) {return Json.hash(Json.write(data));}
    private Node required(String kind,Object id) {
        Node n=repo.entity(kind,c.namespaceId,RequestContext.uuid(id));if(n==null)c.fail("NOT_FOUND",kind+" not found");return n;
    }
    private record Item(Node node,Map<String,Object> data) {}
    private List<Item> select(String kind,Map<String,Object> request) {
        readContext();
        boolean server=Set.of("User","Namespace").contains(kind);
        Node user;
        if(server) {user=authenticate();c.revision=c.server.revision(c.state);} else {scoped(kind.equals("NamespaceRoles")?"owner":"viewer");user=null;}
        List<Node> nodes;
        switch(kind) {
            case "User" -> {
                fields(request,"userId");boolean admin="administrator".equals(user.getProperty("serverRole"));
                if(request.containsKey("userId")) {
                    String id=RequestContext.uuid(request.get("userId"));Node target=repo.find("User","id",id);
                    if(target==null||(!admin&&!id.equals(c.actorId)))c.fail("NOT_FOUND","User not found");nodes=List.of(target);
                } else nodes=admin?repo.nodes("User",Map.of()):List.of(user);
            }
            case "Namespace" -> {
                fields(request,"id","name");exclusive(request,"id","name");
                nodes=namespaces(request);
                if(!request.isEmpty()&&nodes.isEmpty())c.fail("NOT_FOUND","Namespace not found");
            }
            case "Class" -> {
                fields(request,"id","name");exclusive(request,"id","name");
                if(request.containsKey("id"))nodes=List.of(required(kind,request.get("id")));
                else if(request.containsKey("name")) {Node n=repo.find(kind,Map.of("namespaceId",c.namespaceId,"name",name(request.get("name"))));if(n==null)c.fail("NOT_FOUND","Class not found");nodes=List.of(n);}
                else nodes=repo.nodes(kind,Map.of("namespaceId",c.namespaceId));
            }
            case "Object" -> {
                fields(request,"id","classId","descriptors");if(request.containsKey("id")&&request.size()!=1)c.fail("VALIDATION_FAILED","ID selector is exclusive");
                if(request.containsKey("id"))nodes=List.of(required(kind,request.get("id")));
                else nodes=request.containsKey("classId")?repo.members(required("Class",request.get("classId"))):repo.nodes(kind,Map.of("namespaceId",c.namespaceId));
                if(request.containsKey("descriptors")) {Map<String,Object> filters=ValueCodec.map(request.get("descriptors"));nodes=nodes.stream().filter(n->matches(n,filters)).toList();}
            }
            case "ClassDescriptor","Descriptor" -> {
                String ownerKey=kind.equals("Descriptor")?"objectId":"classId";
                fields(request,"id",ownerKey,"name");
                if(request.containsKey("id")) {if(request.size()!=1)c.fail("VALIDATION_FAILED","ID selector is exclusive");nodes=List.of(required(kind,request.get("id")));}
                else {
                    if(request.containsKey(ownerKey))nodes=repo.children(required(kind.equals("Descriptor")?"Object":"Class",request.get(ownerKey)),kind);
                    else {if(request.containsKey("name"))c.fail("VALIDATION_FAILED","Name filter requires owner");nodes=repo.nodes(kind,Map.of("namespaceId",c.namespaceId));}
                    if(request.containsKey("name")) {String name=name(request.get("name"));nodes=nodes.stream().filter(n->name.equals(n.getProperty("name"))).toList();}
                }
            }
            case "ClassMembers" -> {fields(request,"classId");nodes=repo.members(required("Class",request.get("classId")));}
            case "ObjectClass" -> {fields(request,"objectId");nodes=List.of(repo.objectClass(required("Object",request.get("objectId"))));}
            case "ObjectByDescriptorId" -> {fields(request,"descriptorId");nodes=List.of(repo.owner(required("Descriptor",request.get("descriptorId"))));}
            case "NamespaceRoles" -> {fields(request);return repo.roles(c.namespaceId).stream().map(data->new Item(null,data)).toList();}
            default -> throw new IllegalArgumentException();
        }
        var results=new ArrayList<Item>();
        for(Node n:nodes) {checkRevision();var data=kind.equals("Namespace")?namespaceData(n):repo.data(n);results.add(new Item(n,data));}
        if(kind.equals("Namespace")) {discoveryRequest=Map.copyOf(request);discoveryMode=administrativeMode();discoveryFingerprint=fingerprint(results.stream().map(Item::data).toList());}
        checkRevision();return results;
    }
    private boolean matches(Node object,Map<String,Object> filters) {
        boolean match=true;
        for(var entry:filters.entrySet()) {
            String name=name(entry.getKey());Node declaration=repo.declaration(repo.objectClass(object),name);
            if(declaration==null) {match=false;continue;}
            String type=(String)declaration.getProperty("type");Object expected=ValueCodec.normalize(type,entry.getValue());Node d=repo.descriptor(object,name);
            if(d==null||!Json.write(expected).equals(Json.write(ValueCodec.decode(type,d.getProperty("value",null)))))match=false;
        }return match;
    }
    private static void exclusive(Map<String,Object> request,String a,String b) {if(request.containsKey(a)&&request.containsKey(b))throw ExceptionHandler.fail("VALIDATION_FAILED","Selectors are exclusive");}
    @Override protected void checkRevision() {
        c.state.kernel.assertOpen();
        authenticate();
        if(System.currentTimeMillis()-c.state.kernel.startTime()>30_000)c.fail("CANCELLED","Read timeout exceeded");
        long current=c.namespaceId==null?c.server.revision(c.state):repo.sequence(c.namespaceId);
        if(current!=c.revision)throw ExceptionHandler.error("REVISION_CONFLICT","Revision changed during read",c,false,Map.of("currentRevision",current));
        if(discoveryRequest!=null&&(discoveryMode!=administrativeMode()||!discoveryFingerprint.equals(fingerprint(namespaces(discoveryRequest).stream().map(this::namespaceData).toList()))))c.fail("REVISION_CONFLICT","Accessible Namespace results changed");
    }
    private Stream<Item> stream(List<Item> items) {
        var iterator=items.iterator();
        var spliterator=new Spliterators.AbstractSpliterator<Item>(items.size(),Spliterator.ORDERED) {
            @Override public boolean tryAdvance(Consumer<? super Item> action) {
                checkRevision();if(!iterator.hasNext())return false;action.accept(iterator.next());checkRevision();return true;
            }
        };
        return StreamSupport.stream(spliterator,false).onClose(this::checkRevision);
    }
    public Stream<EntityRow> entities(String kind,Map<String,Object> request) {return stream(select(kind,request)).map(i->new EntityRow(i.node(),i.data(),c.meta()));}
    public Stream<DataRow> records(String kind,Map<String,Object> request) {return stream(select(kind,request)).map(i->new DataRow(i.data(),c.meta()));}
    public DataRow page(String kind,Map<String,Object> request) {
        if(request==null)c.fail("VALIDATION_FAILED","request must be a map");
        var base=new LinkedHashMap<>(request);base.remove("pageSize");base.remove("cursor");
        long size=integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");
        readContext();if(Set.of("User","Namespace").contains(kind)) {authenticate();c.revision=c.server.revision(c.state);}else scoped(kind.equals("NamespaceRoles")?"owner":"viewer");
        String scope=c.namespaceId==null?"server":c.namespaceId;
        String hash=Json.hash(Json.write(Json.map("kind",kind,"request",base)));
        long offset=0;
        Map<String,Object> cursor=null;
        if(request.containsKey("cursor")) {
            cursor=CursorCodec.decode(c,string(request.get("cursor")));
            if(!scope.equals(cursor.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another scope");
            if(!c.actorId.equals(cursor.get("actor"))||!hash.equals(cursor.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match request");
            if(c.namespaceId==null&&!c.server.runId.equals(cursor.get("run")))c.fail("REVISION_CONFLICT","Server run changed");
            if(((Number)cursor.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Cursor revision changed");
            offset=((Number)cursor.get("offset")).longValue();
        }
        long pinned=c.revision;List<Item> items=select(kind,base);if(pinned!=c.revision)c.fail("REVISION_CONFLICT","Revision changed during page");
        if(cursor!=null && kind.equals("Namespace") && (!Objects.equals(cursor.get("discovery"),discoveryFingerprint)||!Objects.equals(cursor.get("administrative"),discoveryMode)))c.fail("REVISION_CONFLICT","Accessible Namespace results or mode changed");
        if(offset<0||offset>items.size())c.fail("VALIDATION_FAILED","Invalid cursor offset");
        int end=(int)Math.min(items.size(),offset+size);var data=Json.map("items",items.subList((int)offset,end).stream().map(Item::data).toList());var meta=c.meta();
        if(end<items.size())meta.put("nextCursor",CursorCodec.encode(c,Json.map("scope",scope,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",end,"expires",System.currentTimeMillis()+900_000L,"run",c.server.runId,"discovery",discoveryFingerprint,"administrative",administrativeMode())));
        checkRevision();return new DataRow(data,meta);
    }
}
