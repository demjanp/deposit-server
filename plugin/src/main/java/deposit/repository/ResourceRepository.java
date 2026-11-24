package deposit.repository;

import deposit.common.*;
import deposit.repository.ResourceTransferStore.Transfer;
import java.util.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.io.*;
import java.time.ZonedDateTime;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Resource graph/file persistence within supplied transactions and storage protection. */
public final class ResourceRepository {
    public final Transaction tx;public final CoreRepository core;public final ServerContext server;public final ResourceFiles files;public final ResourceStorage storage;
    public ResourceRepository(Transaction tx,CoreRepository core) {
        this.tx=tx;this.core=core;var internal=(InternalTransaction)tx;server=ServerContext.forDatabase(internal.getDatabaseId());files=server.storage.files;storage=server.storage.content;
    }
    public long now(){return server.sessionTime();}
    public void lock(String id){
        var kernel=((InternalTransaction)tx).kernelTransaction();
        server.holdStorage(server.state(kernel),id);
        server.storage.transfers.requireSettled(id,server.operationalUnit(kernel));
    }
    public Transfer location(String id){
        var transfer=resourceTransfer(id);
        return transfer==null||"finished".equals(transfer.state())?null:transfer;
    }
    public Node completed(String id){return core.find("DResource","id",id);}
    public Node resource(String ns,String id){
        Node resource=core.entity("DResource",ns,id);
        return resource!=null&&ownedBy(resource,ns)?resource:null;
    }

    private static boolean ownedBy(Node resource,String namespace){
        try(var ownership=resource.getRelationships(Direction.INCOMING,RelationshipType.withName("OWNS_RESOURCE"));
            var edges=ownership.iterator()){
            if(!edges.hasNext())return false;
            Node owner=edges.next().getStartNode();
            return !edges.hasNext()&&owner.hasLabel(Label.label("Namespace"))&&namespace.equals(owner.getProperty("id",null));
        }
    }

    public record ResourceState(Node completed,Transfer transfer) {
        public Transfer provisional() { return completed==null && transfer!=null && !"finished".equals(transfer.state())?transfer:null; }
        public boolean exists() { return completed!=null||provisional()!=null; }
        public String namespace() { return completed!=null?text(completed,"namespaceId"):provisional().namespace(); }
        public long bytes() { return completed!=null?number(completed,"bytes",0):provisional().bytes(); }
        public String deleting() { return completed!=null?(String)completed.getProperty("deleting",null):provisional().deleting(); }
        public Long orphanSince() { return completed!=null?(Long)completed.getProperty("orphanSince",null):provisional().orphanSince(); }
    }

    public ResourceState state(String id){
        return new ResourceState(completed(id),resourceTransfer(id));
    }

    public Map<String,String> collectionCandidates(String namespace){
        var scope=namespace==null?Map.<String,Object>of():Map.<String,Object>of("namespaceId",namespace);
        var candidates=new TreeMap<String,String>();
        for(var transfer:server.storage.transfers.scoped(namespace))
            if(!"finished".equals(transfer.state()))candidates.put(transfer.resource(),transfer.namespace());
        String filter=namespace==null?"":" AND r.namespaceId=$namespaceId";
        try(var rows=tx.execute("MATCH (r:DResource) WHERE r.orphanSince IS NOT NULL"+filter+" RETURN r",scope)){
            while(rows.hasNext()){
                Node resource=(Node)rows.next().get("r");
                if(namespace==null||ownedBy(resource,namespace))
                    candidates.put(CoreRepository.id(resource),text(resource,"namespaceId"));
            }
        }
        return candidates;
    }

    /** Publish the completed graph identity after the caller has verified publication. */
    public Node complete(String ns,String filename,String mediaType,ResourceStorage.Content content,String actor,ZonedDateTime timestamp){
        Node owner=core.namespace(ns);
        if(owner==null)throw ExceptionHandler.fail("NOT_FOUND","Resource Namespace not found");
        Node resource=tx.createNode(Label.label("DResource"));
        resource.setProperty("id",content.resourceId());
        Json.map("namespaceId",ns,"filename",filename,
            "storedFilename",content.storedFilename(),"bytes",content.bytes(),"checksum",content.checksum(),
            "mediaType",mediaType,"state","ready","createdBy",actor,"updatedBy",actor,
            "createdAt",timestamp,"updatedAt",timestamp,"leaseUntil",now()+ResourceFiles.LEASE)
            .forEach((key,value)->{if(value!=null)resource.setProperty(key,value);});
        owner.createRelationshipTo(resource,RelationshipType.withName("OWNS_RESOURCE"));
        return resource;
    }

    public void detach(Node resource){
        lock(CoreRepository.id(resource));
        var ownership=resource.getSingleRelationship(RelationshipType.withName("OWNS_RESOURCE"),Direction.INCOMING);
        if(ownership!=null)ownership.delete();
        resource.setProperty("leaseUntil",0L);
        orphan(resource);
    }

    public static long number(Node n,String key,long fallback){return ((Number)n.getProperty(key,fallback)).longValue();}
    public static String text(Node n,String key){return (String)n.getProperty(key);}
    public Transfer transfer(String id){return server.storage.transfers.transfer(id);}
    public Transfer resourceTransfer(String id){
        var kernel=((InternalTransaction)tx).kernelTransaction();
        return server.storage.transfers.resource(id,server.operationalUnit(kernel));
    }
    public boolean live(String id){
        Node resource=completed(id);
        return resource!=null&&resource.hasRelationship(Direction.INCOMING,RelationshipType.withName("HAS_RESOURCE"));
    }
    public boolean protectedContent(Node loc) {
        return number(loc,"leaseUntil",0)>now() || linkedProtection(CoreRepository.id(loc));
    }
    public boolean protectedContent(ResourceState resource) {
        return resource.completed()!=null?protectedContent(resource.completed()):
            resource.provisional().expires()>now() || linkedProtection(resource.provisional().resource());
    }
    private boolean linkedProtection(String id) {
        return live(id)||retained(id)||server.hasReaderPin(id)||server.storage.tables.protects(id)
            ||server.storage.exportGrants.protects(id,now());
    }
    public void orphan(Node loc) {if(protectedContent(loc))loc.removeProperty("orphanSince");else if(!loc.hasProperty("orphanSince"))loc.setProperty("orphanSince",now());}
    public boolean accessible(Node resource,String actor,String role,boolean renew) {
        String namespace=text(resource,"namespaceId");
        if(resource.hasProperty("deleting")||!ownedBy(resource,namespace))return false;
        String id=CoreRepository.id(resource);
        boolean namespaceReader=role!=null&&Set.of("viewer","editor","owner").contains(role);
        if(namespaceReader&&live(id))return true;
        if(namespaceReader&&retained(id))return true;
        // The creator lease is an upload recovery path only. Generated exports are
        // authorized through their actor-bound export grant.
        if(actor.equals(resource.getProperty("createdBy"))&&number(resource,"leaseUntil",0)>now()) {
            var t=resourceTransfer(id);
            if(t!=null&&"finished".equals(t.state())&&actor.equals(t.actor())
                &&namespace.equals(t.namespace())&&t.expires()>now()) {
                return true;
            }
        }
        return server.storage.exportGrants.permits(id,namespace,actor,
            Objects.equals(role,"owner"),now());
    }
    public boolean retained(String id) {
        Node resource=completed(id);if(resource==null)return false;
        try(var edges=resource.getRelationships(Direction.INCOMING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var edge:edges){Node snapshot=edge.getStartNode();Node epoch=core.entity("DepositHistoryEpoch",text(resource,"namespaceId"),(String)snapshot.getProperty("historyEpochId"));if(epoch!=null&&!Boolean.TRUE.equals(epoch.getProperty("expired",false)))return true;}}
        return false;
    }
    public Node linked(Node descriptor) {var e=descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING);return e==null?null:e.getEndNode();}
    public void changed(Node resource){orphan(resource);}
    /** Internal authority for resources created by one materialization, never a public access grant. */
    public ImportedResources beginImport(String namespace,String actor,StorageAdmission.Reservation reservation,Set<String> createdIds){
        requireImportWriter(namespace,actor);
        return new ImportedResources(this,namespace,actor,reservation,createdIds);
    }
    private void requireImportWriter(String namespace,String actor) {
        ((InternalTransaction)tx).kernelTransaction().assertOpen();
        String role=core.resourceWriteRole(actor,namespace);
        if(core.namespace(namespace)==null||!("owner".equals(role)||"editor".equals(role)))throw ExceptionHandler.fail("FORBIDDEN","Import requires destination write authority");
    }
    public static final class ImportedResources implements AutoCloseable {
        private final ResourceRepository owner;
        private final String namespace,actor;
        private final StorageAdmission.Reservation reservation;
        private final Set<String> createdIds;
        private boolean closed,publicationStarted,publicationFailed;
        private ImportedResources(ResourceRepository owner,String namespace,String actor,StorageAdmission.Reservation reservation,Set<String> createdIds){this.owner=owner;this.namespace=namespace;this.actor=actor;this.reservation=reservation;this.createdIds=createdIds;}
        private void scope(ResourceRepository repository,String destination,String writer) {
            if(closed||repository.tx!=owner.tx||!namespace.equals(destination)||!actor.equals(writer))throw ExceptionHandler.fail("FORBIDDEN","Imported resource authority is outside its scope");
            repository.requireImportWriter(destination,writer);
        }
        /** Creates and registers a fresh identity; existing resources cannot be registered. */
        public String create(Path staged,String filename,String mediaType,long bytes,String checksum,ZonedDateTime timestamp)throws IOException {
            scope(owner,namespace,actor);
            if(Files.size(staged)!=bytes||!checksum.equals(ResourceFiles.checksum(staged)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Package resource content changed");
            String id=UUID.randomUUID().toString(),stored=ResourceFiles.filename(filename,checksum);
            owner.lock(id);
            if(owner.location(id)!=null||owner.completed(id)!=null)throw new IllegalStateException("Fresh resource identity already exists");
            var content=new ResourceStorage.Content(id,stored,bytes,checksum);if(reservation==null)throw new IOException("Import publication has no storage admission");publicationStarted=true;try{owner.publish(content,staged,reservation);}catch(IOException|RuntimeException failure){publicationFailed=true;throw failure;}
            owner.complete(namespace,filename,mediaType,content,actor,timestamp);
            createdIds.add(id);
            return id;
        }
        private Node resolve(ResourceRepository repository,Node descriptor,String id,String writer) {
            scope(repository,(String)descriptor.getProperty("namespaceId"),writer);
            if(!createdIds.contains(id))throw ExceptionHandler.fail("FORBIDDEN","Resource was not created by this import");
            repository.lock(id);Node resource=repository.resource(namespace,id);
            if(resource==null||!actor.equals(resource.getProperty("createdBy",null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Imported resource identity changed");
            repository.verifyCompleted(resource);return resource;
        }
        @Override public void close(){closed=true;}
        public boolean publicationFailed(){return publicationFailed;}
        public boolean publicationStarted(){return publicationStarted;}
    }
    boolean writeImported(Node descriptor,Object payload,String actor,ImportedResources imported) {
        if(imported==null)throw ExceptionHandler.fail("FORBIDDEN","Imported resource authority required");
        Node next=imported.resolve(this,descriptor,(String)ValueCodec.map(payload).get("resourceId"),actor);
        return attach(descriptor,linked(descriptor),next);
    }
    public boolean write(Node descriptor,Object payload,String actor,String role) {
        Node old=linked(descriptor),next=null;
        if(payload!=null) {
            String id=(String)ValueCodec.map(payload).get("resourceId");lock(id);next=resource((String)descriptor.getProperty("namespaceId"),id);
            if(next==null)throw ExceptionHandler.fail("NOT_FOUND","Resource not found");
            if(!"ready".equals(next.getProperty("state")))throw ExceptionHandler.fail("RESOURCE_NOT_READY","Resource is not ready");
            if(!accessible(next,actor,role,false))throw ExceptionHandler.fail("FORBIDDEN","Resource access required");verifyCompleted(next);
        }
        return attach(descriptor,old,next);
    }
    private boolean attach(Node descriptor,Node old,Node next) {
        if(Objects.equals(old,next))return false;
        if(old!=null){lock(CoreRepository.id(old));descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING).delete();changed(old);}
        if(next!=null){descriptor.createRelationshipTo(next,RelationshipType.withName("HAS_RESOURCE"));changed(next);}return true;
    }
    public ResourceStorage.Content content(Node loc){return new ResourceStorage.Content(CoreRepository.id(loc),text(loc,"storedFilename"),number(loc,"bytes",0),text(loc,"checksum"));}
    public ResourceStorage.Content verify(Node loc){try{var value=content(loc);storage.verify(value);return value;}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Resource content unavailable");}}
    public ResourceStorage.Content verifyCompleted(Node resource){
        if(resource==null||!"ready".equals(resource.getProperty("state",null))||resource.hasProperty("deleting")
            ||!(resource.getProperty("bytes",null) instanceof Number bytes)||bytes.longValue()<0
            ||!(resource.getProperty("checksum",null) instanceof String checksum)||!checksum.matches("[0-9a-f]{64}"))
            throw ExceptionHandler.fail("RESOURCE_CORRUPT","Completed resource unavailable");
        for(String property:List.of("filename","storedFilename","mediaType"))
            if(!(resource.getProperty(property,null) instanceof String))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Missing completed resource metadata");
        return verify(resource);
    }
    public void growPublication(StorageAdmission.Reservation reservation,long bytes)throws IOException{
        // This reservation can also carry credit for later staged resources. Publication
        // needs an additive peak allowance; treating the total commitment as publication
        // credit would consume that staging allowance when the current item is published.
        server.storage.admission.grow(reservation,Map.of(files.root,bytes));
    }
    /** Publish only after capacity was prepared in the operation's existing reservation. */
    public void publish(ResourceStorage.Content content,Path staged,StorageAdmission.Reservation reservation)throws IOException{
        if(storage.exists(content.resourceId())){storage.verify(content);return;}
        final long peak;
        try{peak=Math.multiplyExact(content.bytes(),2L);}catch(ArithmeticException overflow){throw new IOException("OCFL publication capacity is not representable",overflow);}
        // The pinned OCFL 2.2.3 updater first copies the input into its work directory,
        // then installs the immutable object in the OCFL root. Reserve both payload
        // writes on the resolved filesystem before calling it.
        growPublication(reservation,peak);
        var state=server.state(((InternalTransaction)tx).kernelTransaction());
        if(state.operationClaimed){
            state.kernel.assertOpen();
            var operation=state.operationKey();
            var intent=server.storage.admission.acquire("ocfl-publication",content.resourceId(),operation.id(),
                "immutable-object-write",Map.of(files.root,0L),Json.map("phase","publish","actorId",operation.actor(),
                    "receiptScope",operation.scope(),"resourceId",content.resourceId(),"storedFilename",content.storedFilename(),
                    "bytes",content.bytes(),"checksum",content.checksum()));
            boolean[] committed={false};
            state.afterCommit.add(()->committed[0]=true);
            state.release.add(()->{if(committed[0])try{server.storage.admission.closeIfPresent(intent.id);}
                catch(IOException|RuntimeException failure){server.storage.diagnostic="Committed OCFL intent cleanup pending";}});
        }
        storage.publish(content,staged);
        storage.assertWorkClean();
        server.storage.admission.materialized(reservation,files.root,content.bytes());
        server.storage.admission.release(reservation,Map.of(files.root,content.bytes()));
    }
    public Map<String,Object> info(Node r){String url=server.downloadPublicBaseUrl==null?null:ResourceLinkEncoder.url(server.downloadPublicBaseUrl,(String)r.getProperty("namespaceId"),CoreRepository.id(r));return Json.map("resourceId",CoreRepository.id(r),"filename",r.getProperty("filename"),"storedFilename",r.getProperty("storedFilename"),"downloadUrl",url,"bytes",r.getProperty("bytes"),"checksum",r.getProperty("checksum"),"mediaType",r.getProperty("mediaType"),"state",r.getProperty("state"));}
    public void expire(String id) {
        lock(id);
        var resource=state(id);
        if(!resource.exists()||resource.deleting()!=null)return;
        if(resource.completed()!=null) {
            orphan(resource.completed());
            return;
        }
        var transfer=resource.provisional();
        if(transfer.collected()||transfer.quarantined())return;
        String next=transfer.state();
        if("uploading".equals(next)) {
            if(transfer.writerBound()||transfer.expires()>now())return;
            next="expired";
        }
        boolean protectedContent=protectedContent(resource);
        Long orphan=protectedContent?null:transfer.orphanSince();
        if(orphan==null&&!protectedContent)orphan=now();
        if(!next.equals(transfer.state())||!Objects.equals(orphan,transfer.orphanSince()))
            server.storage.transfers.save(transfer.progress(transfer.nextOffset(),next,transfer.expires(),transfer.admissionId(),orphan),"");
    }

    public boolean eligible(ResourceState resource,long age) {
        var provisional=resource.provisional();
        return resource.exists() && resource.deleting()==null
            && (provisional==null||!provisional.collected()&&!provisional.quarantined()&&!"uploading".equals(provisional.state()))
            && !protectedContent(resource) && resource.orphanSince()!=null && now()-resource.orphanSince()>=age;
    }
}
