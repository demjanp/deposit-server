package deposit.repository;

import deposit.common.*;
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
    public void lock(String id){var internal=(InternalTransaction)tx;server.holdStorage(server.state(internal.kernelTransaction()),id);}
    public Node record(String label,String id,Map<String,Object> fields){Node n=tx.createNode(Label.label(label));n.setProperty("id",id);fields.forEach((k,v)->{if(v!=null)n.setProperty(k,v);});return n;}
    public Node location(String id){return core.find("DepositResourceLocation","id",id);}
    public Node resource(String ns,String id){return core.entity("DResource",ns,id);}
    public static long number(Node n,String key,long fallback){return ((Number)n.getProperty(key,fallback)).longValue();}
    public static String text(Node n,String key){return (String)n.getProperty(key);}
    public Node transfer(String id){return core.find("DepositTransfer","id",id);}
    public boolean live(String id){Node loc=location(id);if(loc==null)return false;Node r=resource(text(loc,"namespaceId"),id);return r!=null&&r.hasRelationship(Direction.INCOMING,RelationshipType.withName("HAS_RESOURCE"));}
    public boolean protectedContent(Node loc) {
        if(live(CoreRepository.id(loc)))return true;
        if(retained(CoreRepository.id(loc)))return true;
        if(server.hasReaderPin(CoreRepository.id(loc)))return true;
        if(number(loc,"leaseUntil",0)>now())return true;
        for(Node lease:core.nodes("DepositResourceLease",Map.of("resourceId",CoreRepository.id(loc))))if(!"history".equals(lease.getProperty("type",""))&&number(lease,"expires",Long.MAX_VALUE)>now())return true;
        for(Node grant:core.nodes("DepositExportGrant",Map.of("resourceId",CoreRepository.id(loc))))if(number(grant,"expires",0)>now())return true;
        return false;
    }
    public void orphan(Node loc) {if(protectedContent(loc))loc.removeProperty("orphanSince");else if(!loc.hasProperty("orphanSince"))loc.setProperty("orphanSince",now());}
    public void renew(Node transfer) {
        long until=now()+ResourceFiles.LEASE;transfer.setProperty("expires",until);Node loc=location(text(transfer,"resourceId"));loc.setProperty("leaseUntil",until);loc.removeProperty("orphanSince");
    }
    public boolean accessible(Node resource,String actor,String role,boolean renew) {
        String id=CoreRepository.id(resource);Node loc=location(id);if(loc==null||loc.hasProperty("deleting"))return false;
        if(live(id))return true;
        if(Set.of("viewer","editor","owner").contains(role)&&retained(id))return true;
        // The creator lease is an upload recovery path only. Generated exports are
        // authorized exclusively by their actor-bound DepositExportGrant.
        if(loc.hasProperty("uploadId")&&actor.equals(resource.getProperty("createdBy"))&&number(loc,"leaseUntil",0)>now()) {
            Node t=transfer(text(loc,"uploadId"));
            if(t!=null&&actor.equals(t.getProperty("actorId",null))&&number(t,"expires",0)>now()) {
                if(renew)renew(t);return true;
            }
        }
        for(Node grant:core.nodes("DepositExportGrant",Map.of("resourceId",id,"namespaceId",resource.getProperty("namespaceId"),"actorId",actor))) {
            if(number(grant,"expires",0)>now()&&(!Boolean.TRUE.equals(grant.getProperty("history",false))||role.equals("owner"))) {
                if(renew){grant.setProperty("expires",now()+ResourceFiles.LEASE);loc.removeProperty("orphanSince");}return true;
            }
        }return false;
    }
    public boolean retained(String id) {
        Node loc=location(id);if(loc==null)return false;Node resource=resource(text(loc,"namespaceId"),id);if(resource==null)return false;
        try(var edges=resource.getRelationships(Direction.INCOMING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var edge:edges){Node snapshot=edge.getStartNode();Node epoch=core.entity("DepositHistoryEpoch",text(loc,"namespaceId"),(String)snapshot.getProperty("historyEpochId"));if(epoch!=null&&!Boolean.TRUE.equals(epoch.getProperty("expired",false)))return true;}}
        return false;
    }
    public Node linked(Node descriptor) {var e=descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING);return e==null?null:e.getEndNode();}
    public void changed(Node r){Node loc=location(CoreRepository.id(r));orphan(loc);}
    /** Internal authority for resources created by one materialization, never a public access grant. */
    public ImportedResources beginImport(String namespace,String actor) {
        requireImportWriter(namespace,actor);return new ImportedResources(this,namespace,actor,null);
    }
    public ImportedResources beginImport(String namespace,String actor,StorageAdmission.Reservation reservation){requireImportWriter(namespace,actor);return new ImportedResources(this,namespace,actor,reservation);}
    private void requireImportWriter(String namespace,String actor) {
        ((InternalTransaction)tx).kernelTransaction().assertOpen();
        String role=core.resourceWriteRole(actor,namespace);
        if(core.namespace(namespace)==null||!("owner".equals(role)||"editor".equals(role)))throw ExceptionHandler.fail("FORBIDDEN","Import requires destination write authority");
    }
    public static final class ImportedResources implements AutoCloseable {
        private final ResourceRepository owner;
        private final String namespace,actor;
        private final StorageAdmission.Reservation reservation;
        private boolean closed,publicationStarted,publicationFailed;
        private ImportedResources(ResourceRepository owner,String namespace,String actor,StorageAdmission.Reservation reservation){this.owner=owner;this.namespace=namespace;this.actor=actor;this.reservation=reservation;}
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
            if(owner.location(id)!=null||owner.resource(namespace,id)!=null)throw new IllegalStateException("Fresh resource identity already exists");
            var content=new ResourceStorage.Content(id,stored,bytes,checksum);if(reservation==null)throw new IOException("Import publication has no storage admission");publicationStarted=true;try{owner.publish(content,staged,reservation);}catch(IOException|RuntimeException failure){publicationFailed=true;throw failure;}
            owner.record("DepositResourceLocation",id,Json.map("namespaceId",namespace,"storedFilename",stored,"bytes",bytes,"checksum",checksum,"leaseUntil",owner.now()+ResourceFiles.LEASE,"everAttached",false,"published",true,"importOperationId",reservation.operationId));
            owner.record("DResource",id,Json.map("namespaceId",namespace,"filename",filename,"storedFilename",stored,"mediaType",mediaType,"bytes",bytes,"checksum",checksum,"state","ready","createdBy",actor,"updatedBy",actor,"createdAt",timestamp,"updatedAt",timestamp));
            return id;
        }
        private Node resolve(ResourceRepository repository,Node descriptor,String id,String writer) {
            scope(repository,(String)descriptor.getProperty("namespaceId"),writer);
            if(reservation==null||!reservation.operationId.equals(repository.location(id)==null?null:repository.location(id).getProperty("importOperationId",null)))throw ExceptionHandler.fail("FORBIDDEN","Resource was not created by this import");
            repository.lock(id);Node resource=repository.resource(namespace,id),location=repository.location(id);
            if(resource==null||location==null||!namespace.equals(location.getProperty("namespaceId",null))||location.hasProperty("deleting")||!"ready".equals(resource.getProperty("state",null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Imported resource is unavailable");
            for(String field:List.of("storedFilename","checksum","bytes"))if(!Objects.equals(resource.getProperty(field,null),location.getProperty(field,null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Imported resource metadata changed");
            if(!actor.equals(resource.getProperty("createdBy",null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Imported resource identity changed");
            repository.verify(location);return resource;
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
            if(!accessible(next,actor,role,false))throw ExceptionHandler.fail("FORBIDDEN","Resource access required");verify(location(id));
        }
        return attach(descriptor,old,next);
    }
    private boolean attach(Node descriptor,Node old,Node next) {
        if(Objects.equals(old,next))return false;
        if(old!=null){lock(CoreRepository.id(old));descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING).delete();changed(old);}
        if(next!=null){descriptor.createRelationshipTo(next,RelationshipType.withName("HAS_RESOURCE"));Node loc=location(CoreRepository.id(next));loc.setProperty("everAttached",true);changed(next);}return true;
    }
    public ResourceStorage.Content content(Node loc){return new ResourceStorage.Content(CoreRepository.id(loc),text(loc,"storedFilename"),number(loc,"bytes",0),text(loc,"checksum"));}
    public ResourceStorage.Content verify(Node loc){try{var value=content(loc);storage.verify(value);return value;}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Resource content unavailable");}}
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
        storage.publish(content,staged);storage.verify(content);
        storage.assertWorkClean();
        server.storage.admission.materialized(reservation,files.root,content.bytes());
        server.storage.admission.release(reservation,Map.of(files.root,content.bytes()));
    }
    public Map<String,Object> info(Node r){String url=server.downloadPublicBaseUrl==null?null:ResourceLinkEncoder.url(server.downloadPublicBaseUrl,(String)r.getProperty("namespaceId"),CoreRepository.id(r));return Json.map("resourceId",CoreRepository.id(r),"filename",r.getProperty("filename"),"storedFilename",r.getProperty("storedFilename"),"downloadUrl",url,"bytes",r.getProperty("bytes"),"checksum",r.getProperty("checksum"),"mediaType",r.getProperty("mediaType"),"state",r.getProperty("state"));}
    public void expire(Node loc) {
        String id=CoreRepository.id(loc);lock(id);if(loc.hasProperty("deleting")||Boolean.TRUE.equals(loc.getProperty("collected",false)))return;
        Node t=loc.hasProperty("uploadId")?transfer(text(loc,"uploadId")):null;
        if(t!=null&&"uploading".equals(t.getProperty("state"))&&number(t,"expires",0)<=now()) {t.setProperty("state","expired");}
        orphan(loc);
    }
    public boolean eligible(Node loc,long age) {return !Boolean.TRUE.equals(loc.getProperty("collected",false))&&!loc.hasProperty("deleting")&&!protectedContent(loc)&&loc.hasProperty("orphanSince")&&now()-number(loc,"orphanSince",now())>=age;}
}
