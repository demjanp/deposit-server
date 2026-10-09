package deposit.repository;

import deposit.common.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Resource graph/file persistence within supplied transactions and storage protection. */
public final class ResourceRepository {
    public final Transaction tx;public final CoreRepository core;public final ServerContext server;public final ResourceFiles files;
    public ResourceRepository(Transaction tx,CoreRepository core) {
        this.tx=tx;this.core=core;var internal=(InternalTransaction)tx;server=ServerContext.forDatabase(internal.getDatabaseId());files=server.storage.files;
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
        if(number(loc,"leaseUntil",0)>now())return true;
        for(Node lease:core.nodes("DepositResourceLease",Map.of("resourceId",CoreRepository.id(loc))))if(number(lease,"expires",Long.MAX_VALUE)>now())return true;
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
        for(Node lease:core.nodes("DepositResourceLease",Map.of("resourceId",id)))if("history".equals(lease.getProperty("type",""))&&role.equals("owner")&&number(lease,"expires",Long.MAX_VALUE)>now())return true;
        if(actor.equals(resource.getProperty("createdBy"))&&number(loc,"leaseUntil",0)>now()) {
            if(renew){Node t=transfer(text(loc,"uploadId"));if(t!=null)renew(t);}return true;
        }
        for(Node grant:core.nodes("DepositExportGrant",Map.of("resourceId",id,"actorId",actor))) {
            if(number(grant,"expires",0)>now()&&(!Boolean.TRUE.equals(grant.getProperty("history",false))||role.equals("owner"))) {
                if(renew){grant.setProperty("expires",now()+ResourceFiles.LEASE);loc.removeProperty("orphanSince");}return true;
            }
        }return false;
    }
    public Node linked(Node descriptor) {var e=descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING);return e==null?null:e.getEndNode();}
    public void changed(Node r){Node loc=location(CoreRepository.id(r));loc.setProperty("pending",true);orphan(loc);}
    public boolean write(Node descriptor,Object payload,String actor,String role) {
        Node old=linked(descriptor),next=null;
        if(payload!=null) {
            String id=(String)ValueCodec.map(payload).get("resourceId");lock(id);next=resource((String)descriptor.getProperty("namespaceId"),id);
            if(next==null)throw ExceptionHandler.fail("NOT_FOUND","Resource not found");
            if(!"ready".equals(next.getProperty("state")))throw ExceptionHandler.fail("RESOURCE_NOT_READY","Resource is not ready");
            if(!accessible(next,actor,role,false))throw ExceptionHandler.fail("FORBIDDEN","Resource access required");verify(location(id));
        }
        if(Objects.equals(old,next))return false;
        if(old!=null){lock(CoreRepository.id(old));descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING).delete();changed(old);}
        if(next!=null){descriptor.createRelationshipTo(next,RelationshipType.withName("HAS_RESOURCE"));Node loc=location(CoreRepository.id(next));loc.setProperty("everAttached",true);changed(next);}return true;
    }
    public String resolve(Node loc)throws IOException {
        String current=text(loc,"path");if(Files.exists(files.path(current)))return current;
        if(loc.hasProperty("moveTo")&&Files.exists(files.path(text(loc,"moveTo"))))return text(loc,"moveTo");return current;
    }
    public String verify(Node loc){try{String path=resolve(loc);files.verify(path,number(loc,"bytes",0),text(loc,"checksum"));return path;}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Resource content unavailable");}}
    public Map<String,Object> info(Node r){return Json.map("resourceId",CoreRepository.id(r),"filename",r.getProperty("filename"),"storedFilename",r.getProperty("storedFilename"),"downloadUrl",null,"bytes",r.getProperty("bytes"),"checksum",r.getProperty("checksum"),"mediaType",r.getProperty("mediaType"),"state",r.getProperty("state"));}
    public void expireAndPlace(Node loc) {
        String id=CoreRepository.id(loc);lock(id);if(loc.hasProperty("deleting")||Boolean.TRUE.equals(loc.getProperty("collected",false)))return;
        Node t=loc.hasProperty("uploadId")?transfer(text(loc,"uploadId")):null;
        if(t!=null&&"uploading".equals(t.getProperty("state"))&&number(t,"expires",0)<=now()) {t.setProperty("state","expired");}
        orphan(loc);Node r=resource(text(loc,"namespaceId"),id);if(r==null)return;
        boolean desiredLive=live(id)||(!Boolean.TRUE.equals(loc.getProperty("everAttached",false))&&number(loc,"leaseUntil",0)>now());
        String dest=(desiredLive?"live/":"deleted/")+text(loc,"suffix");
        if(!dest.equals(loc.getProperty("path"))||loc.hasProperty("moveTo")) {loc.setProperty("moveTo",dest);loc.setProperty("pending",true);}else loc.setProperty("pending",false);
    }
    public void movePending(Node loc) throws IOException {
        if(!loc.hasProperty("moveTo"))return;lock(CoreRepository.id(loc));
        // Desired placement was persisted in a preceding transaction; recheck current references here.
        boolean desiredLive=live(CoreRepository.id(loc))||(!Boolean.TRUE.equals(loc.getProperty("everAttached",false))&&number(loc,"leaseUntil",0)>now());
        String dest=(desiredLive?"live/":"deleted/")+text(loc,"suffix");
        String source=resolve(loc);files.verify(source,number(loc,"bytes",0),text(loc,"checksum"));
        if(!source.equals(dest)) {if(Files.exists(files.path(dest)))files.verify(dest,number(loc,"bytes",0),text(loc,"checksum"));else {server.storagePoint("relocation-before");files.move(source,dest);server.storagePoint("relocation-after");}}
        loc.setProperty("path",dest);loc.removeProperty("moveTo");loc.setProperty("pending",false);loc.removeProperty("diagnostic");
    }
    public boolean eligible(Node loc,long age) {return !Boolean.TRUE.equals(loc.getProperty("collected",false))&&!loc.hasProperty("deleting")&&!protectedContent(loc)&&loc.hasProperty("orphanSince")&&now()-number(loc,"orphanSince",now())>=age;}
}
