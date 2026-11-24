package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.*;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;

public final class ReadService extends BaseService {
    public ReadService(RequestContext c) {super(c);}
    private static final Set<String> SCOPED_NODE_PAGES=Set.of("Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool");
    private Map<String,Object> discoveryRequest;
    private String discoveryFingerprint;
    private boolean discoveryMode;
    private List<Node> namespaces(Map<String,Object> request) {
        if(request.containsKey("id")) {
            String id=RequestContext.uuid(request.get("id"));
            Node node=repo.namespace(id);
            return node!=null&&effectiveRole(id)!=null?List.of(node):List.of();
        }
        if(request.containsKey("name")) {
            Node node=repo.find("Namespace","name",name(request.get("name")));
            return node!=null&&effectiveRole(CoreRepository.id(node))!=null?List.of(node):List.of();
        }
        return repo.nodes("Namespace",Map.of()).stream().filter(n->{
            checkLive();
            return effectiveRole(CoreRepository.id(n))!=null;
        }).toList();
    }
    private Map<String,Object> namespaceData(Node n) {var data=repo.data(n);data.put("role",effectiveRole(CoreRepository.id(n)));return data;}
    private String fingerprint(List<Node> nodes) {
        // Discovery spans Namespaces, so the server revision alone cannot detect every change.
        return Json.hash(Json.write(nodes.stream().map(n->{
            checkLive();
            String id=CoreRepository.id(n);
            return List.of(id,CoreRepository.sequence(n),Objects.requireNonNullElse(effectiveRole(id),""));
        }).toList()));
    }
    private record Item(Node node,Relationship rel) {}
    private void startRead(String kind) {
        readContext();
        if(kind.equals("User")||kind.equals("Namespace"))pinServerRead();
        else pinReadScope(kind.equals("NamespaceRoles")?"owner":"viewer");
        checkLive();
    }
    private List<Item> select(String kind,Map<String,Object> request) {
        List<Node> nodes;
        switch(kind) {
            case "User" -> {
                fields(request,"userId");Node user=c.state.protectedActor;
                boolean admin="administrator".equals(user.getProperty("serverRole"));
                if(request.containsKey("userId")) {
                    String id=RequestContext.uuid(request.get("userId"));
                    if(!admin&&!id.equals(c.actorId))c.fail("NOT_FOUND","User not found");
                    nodes=List.of(id.equals(c.actorId)?user:required("User",id));
                } else nodes=admin?repo.nodes("User",Map.of()):List.of(user);
            }
            case "Namespace" -> {
                fields(request,"id","name");exclusive(request,"id","name");
                nodes=namespaces(request);
                if(!request.isEmpty()&&nodes.isEmpty())c.fail("NOT_FOUND","Namespace not found");
                discoveryRequest=Map.copyOf(request);
                discoveryMode=administrativeMode();
                discoveryFingerprint=fingerprint(nodes);
            }
            case "Class","Pattern","UserTool" -> {
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
                    if(request.containsKey(ownerKey)) {
                        Node owner=required(kind.equals("Descriptor")?"Object":"Class",request.get(ownerKey));
                        if(request.containsKey("name")) {
                            String selected=name(request.get("name"));
                            Node node=kind.equals("Descriptor")?repo.descriptor(owner,selected):repo.declaration(owner,selected);
                            nodes=node==null?List.of():List.of(node);
                        } else nodes=repo.children(owner,kind);
                    } else {if(request.containsKey("name"))c.fail("VALIDATION_FAILED","Name filter requires owner");nodes=repo.nodes(kind,Map.of("namespaceId",c.namespaceId));}
                }
            }
            case "ClassMembers" -> {fields(request,"classId");nodes=repo.members(required("Class",request.get("classId")));}
            case "ObjectClass" -> {fields(request,"objectId");nodes=List.of(repo.objectClass(required("Object",request.get("objectId"))));}
            case "ObjectByDescriptorId" -> {fields(request,"descriptorId");nodes=List.of(repo.owner(required("Descriptor",request.get("descriptorId"))));}
            case "Relationship","ClassRelationship" -> {
                fields(request,"id","sourceId","targetId","label");
                if(request.containsKey("id")&&request.size()!=1)c.fail("VALIDATION_FAILED","ID selector is exclusive");
                String id=request.containsKey("id")?RequestContext.uuid(request.get("id")):null;
                String source=request.containsKey("sourceId")?RequestContext.uuid(request.get("sourceId")):null;
                String target=request.containsKey("targetId")?RequestContext.uuid(request.get("targetId")):null;
                String label=request.containsKey("label")?name(request.get("label")):null;
                var items=new ArrayList<Item>();
                if(id!=null) {
                    var rel=repo.readRelationship(c.namespaceId,kind,id);
                    if(rel==null)c.fail("NOT_FOUND","Relationship not found");
                    return List.of(new Item(null,rel));
                }
                var edges=source!=null?repo.relationshipsAt(c.namespaceId,kind,source,Direction.OUTGOING,this::checkLive):
                    target!=null?repo.relationshipsAt(c.namespaceId,kind,target,Direction.INCOMING,this::checkLive):
                    repo.relationships(c.namespaceId,kind,this::checkLive);
                for(var rel:edges) {
                    checkLive();
                    if(source!=null&&!source.equals(CoreRepository.id(rel.getStartNode())))continue;
                    if(target!=null&&!target.equals(CoreRepository.id(rel.getEndNode())))continue;
                    if(label!=null&&!label.equals(rel.getProperty("label")))continue;
                    items.add(new Item(null,rel));
                }
                return items;
            }
            case "NamespaceRoles" -> {fields(request);return repo.roleEdges(c.namespaceId,this::checkLive).stream().map(edge->new Item(null,edge)).toList();}
            default -> throw new IllegalArgumentException();
        }
        var results=new ArrayList<Item>();
        if(kind.equals("Class")||kind.equals("ClassDescriptor"))nodes=nodes.stream().sorted(Comparator.<Node>comparingLong(n->((Number)n.getProperty("order",0L)).longValue()).thenComparing(CoreRepository::id)).toList();
        for(Node n:nodes) {checkLive();results.add(new Item(n,null));}
        return results;
    }
    private Map<String,Object> project(String kind,Item item) {
        if(kind.equals("NamespaceRoles"))return CoreRepository.roleData(item.rel());
        if(item.rel()!=null)return repo.data(item.rel());
        return kind.equals("Namespace")?namespaceData(item.node()):repo.data(item.node());
    }
    private boolean matches(Node object,Map<String,Object> filters) {
        checkLive();
        boolean match=true;
        if(filters.isEmpty())return true;
        Node clazz=repo.objectClass(object);
        for(var entry:filters.entrySet()) {
            checkLive();
            String name=name(entry.getKey());Node declaration=repo.declaration(clazz,name);
            if(declaration==null) {match=false;continue;}
            String type=(String)declaration.getProperty("type");Object expected=ValueCodec.normalize(type,entry.getValue());Node d=repo.descriptor(object,name);
            if(d==null||!Objects.equals(expected,repo.descriptorValue(d,type)))match=false;
        }return match;
    }
    private static void exclusive(Map<String,Object> request,String a,String b) {if(request.containsKey(a)&&request.containsKey(b))throw ExceptionHandler.fail("VALIDATION_FAILED","Selectors are exclusive");}
    private void checkLive() {
        c.state.kernel.assertOpen();
        if(System.currentTimeMillis()-c.state.kernel.startTime()>30_000)c.fail("CANCELLED","Read timeout exceeded");
    }
    private void checkRevision(String kind) {
        checkLive();
        long current=currentReadRevision(kind.equals("NamespaceRoles")?"owner":"viewer");
        if(current!=c.revision)throw ExceptionHandler.error("REVISION_CONFLICT","Revision changed during read",c,false,Map.of("currentRevision",current));
    }
    private void checkDiscoveryRevision(String kind) {
        if(discoveryRequest!=null&&(discoveryMode!=administrativeMode()||!discoveryFingerprint.equals(fingerprint(namespaces(discoveryRequest)))))c.fail("REVISION_CONFLICT","Accessible Namespace results changed");
        checkRevision(kind);
    }
    private Stream<Item> stream(String kind,Map<String,Object> request) {
        startRead(kind);
        var items=select(kind,request);
        checkDiscoveryRevision(kind);
        var iterator=items.iterator();
        var spliterator=new Spliterators.AbstractSpliterator<Item>(items.size(),Spliterator.ORDERED) {
            @Override public boolean tryAdvance(Consumer<? super Item> action) {
                if(!iterator.hasNext()) {checkDiscoveryRevision(kind);return false;}
                checkRevision(kind);action.accept(iterator.next());checkRevision(kind);return true;
            }
        };
        return StreamSupport.stream(spliterator,false).onClose(()->checkDiscoveryRevision(kind));
    }
    public Stream<EntityRow> entities(String kind,Map<String,Object> request) {
        return stream(kind,request).map(i->new EntityRow(i.node(),c.nativeResultsOnly?null:project(kind,i),c.meta()));
    }
    public Stream<RelRow> relationships(String kind,Map<String,Object> request) {
        return stream(kind,request).map(i->new RelRow(i.rel(),c.nativeResultsOnly?null:project(kind,i),c.meta()));
    }
    public Stream<DataRow> records(String kind,Map<String,Object> request) {
        return stream(kind,request).map(i->new DataRow(project(kind,i),c.meta()));
    }
    public DataRow page(String kind,Map<String,Object> request) {
        if(request==null)c.fail("VALIDATION_FAILED","request must be a map");
        var base=new LinkedHashMap<>(request);base.remove("pageSize");base.remove("cursor");
        long size=integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");
        startRead(kind);
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
        if(offset<0)c.fail("VALIDATION_FAILED","Invalid cursor offset");
        List<Item> window;
        boolean members=Set.of("Object","ClassMembers").contains(kind)&&base.keySet().equals(Set.of("classId"));
        if(members||base.isEmpty()&&SCOPED_NODE_PAGES.contains(kind)) {
            // Starting one identity early distinguishes an exact-end cursor from an invalid offset.
            int prefix=offset==0?0:1;
            String memberClass=members?CoreRepository.id(required("Class",base.get("classId"))):null;
            var nodes=repo.nodePage(kind,c.namespaceId,memberClass,offset-prefix,size+1+prefix);
            if(nodes.size()<prefix)c.fail("VALIDATION_FAILED","Invalid cursor offset");
            window=nodes.subList(prefix,nodes.size()).stream().map(n->new Item(n,null)).toList();
        } else {
            var items=select(kind,base);
            if(cursor!=null&&kind.equals("Namespace")&&(!Objects.equals(cursor.get("discovery"),discoveryFingerprint)||!Objects.equals(cursor.get("administrative"),discoveryMode)))c.fail("REVISION_CONFLICT","Accessible Namespace results or mode changed");
            if(offset>items.size())c.fail("VALIDATION_FAILED","Invalid cursor offset");
            window=items.subList((int)offset,(int)Math.min(items.size(),offset+size+1));
        }
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<Math.min(window.size(),size);i++) {
            checkLive();
            rows.add(project(kind,window.get(i)));
        }
        var meta=c.meta();
        if(window.size()>size)meta.put("nextCursor",CursorCodec.encode(c,Json.map("scope",scope,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",offset+rows.size(),"expires",System.currentTimeMillis()+900_000L,"run",c.server.runId,"discovery",discoveryFingerprint,"administrative",administrativeMode())));
        c.server.storagePoint("read-page-materialized");
        checkDiscoveryRevision(kind);
        return new DataRow(Json.map("items",rows),meta);
    }
}
