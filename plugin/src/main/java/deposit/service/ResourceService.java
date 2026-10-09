package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import org.neo4j.graphdb.*;
import static deposit.repository.ResourceRepository.*;

public final class ResourceService extends BaseService {
    private final ResourceRepository resources;
    public ResourceService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}
    private Node transfer(Object id,boolean active) {
        Node t=resources.transfer(RequestContext.uuid(id));
        if(t==null||!c.actorId.equals(t.getProperty("actorId")))c.fail("NOT_FOUND","Upload not found");
        if(!c.namespaceId.equals(t.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Upload belongs to another Namespace");
        resources.lock(text(t,"resourceId"));
        if(active&&(!Set.of("uploading","finished").contains(text(t,"state"))||number(t,"expires",0)<=resources.now()))c.fail("NOT_FOUND","Upload expired or aborted");return t;
    }
    private void operational() {
        if(c.operationId==null||c.expectedRevision!=null)c.fail("VALIDATION_FAILED","Operational writes require operationId and no expectedRevision");
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","One mutation per transaction");c.state.mutation=true;repo.lockActor(c.actorId);
    }
    public Map<String,Object> transfer(String method,Map<String,Object> request) {
        scoped("editor");operational();repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,"editor");c.revision=repo.sequence(c.namespaceId);
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.resource."+method,"request",request)));
        Node binding=repo.binding(c.actorId,c.operationId);if(binding!=null&&!c.namespaceId.equals(binding.getProperty("scope")))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
        Node receipt=repo.receipt(c.namespaceId,c.operationId);
        if(receipt!=null) {
            if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");
            if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");
            var saved=repo.replay(receipt,c.namespaceId).data();
            if(method.equals("beginUpload")){Node t=transfer(saved.get("uploadId"),true);resources.renew(t);return ResourceTransferRepository.beginResult(t);}
            if(method.equals("uploadChunk")){Node t=transfer(request.get("uploadId"),true);resources.renew(t);}
            if(method.equals("finishUpload")){Node t=transfer(request.get("uploadId"),false);if(!"finished".equals(t.getProperty("state")))c.fail("NOT_FOUND","Upload unavailable");if(number(t,"expires",0)>resources.now())resources.renew(t);}
            return saved;
        }
        Map<String,Object> result;
        try {result=switch(method){case "beginUpload"->new ResourceTransferRepository(c,resources).begin(request);case "uploadChunk"->new ResourceTransferRepository(c,resources).chunk(request,transfer(request.get("uploadId"),true));case "finishUpload"->new ResourceTransferRepository(c,resources).finish(request,transfer(request.get("uploadId"),true));case "abortUpload"->new ResourceTransferRepository(c,resources).abort(request,transfer(request.get("uploadId"),false));default->throw new IllegalArgumentException();};}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Transfer storage unavailable");}
        repo.saveReceipt(c,c.namespaceId,hash,new Outcome(null,null,result));return result;
    }
    public Map<String,Object> read(Map<String,Object> request,boolean download) {
        readContext();scoped("viewer");if(download)fields(request,"resourceId","offset","length");else fields(request,"resourceId");
        String id=RequestContext.uuid(request.get("resourceId"));repo.lockNamespace(c.namespaceId);resources.lock(id);Node r=resources.resource(c.namespaceId,id);
        if(r==null||!resources.accessible(r,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        if(!"ready".equals(r.getProperty("state")))c.fail("RESOURCE_NOT_READY","Resource is not ready");
        Node loc=resources.location(id);Map<String,Object> result;
        if(!download){resources.verify(loc);result=resources.info(r);}
        else {
            long offset=integer(request.get("offset")),length=integer(request.get("length")),size=number(r,"bytes",0);
            if(offset<0||offset>size||length<1||length>ResourceFiles.MAX_CHUNK)c.fail("VALIDATION_FAILED","Invalid download range");
            int actual=(int)Math.min(length,size-offset);
            try(var stream=resources.storage.open(resources.content(loc),offset,actual)) {byte[] data=stream.readNBytes(actual);if(data.length!=actual||stream.read()!=-1)throw new IOException("Incomplete resource range");result=Json.map("bytesBase64",Base64.getEncoder().encodeToString(data),"offset",offset,"nextOffset",offset+actual,"eof",offset+actual==size,"chunkSha256",ResourceFiles.checksum(data));}
            catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Resource content unavailable");}
        }
        resources.accessible(r,c.actorId,effectiveRole(c.namespaceId),true);return result;
    }
    public Map<String,Object> prune(Map<String,Object> request) {
        fields(request,"dryRun","minimumAgeHours","scope");Node user=authenticate();admin(user);
        Object dry=request.getOrDefault("dryRun",true);if(!(dry instanceof Boolean))c.fail("VALIDATION_FAILED","dryRun must be Boolean");boolean dryRun=(Boolean)dry;
        String scope=string(request.getOrDefault("scope","namespace"));if(!Set.of("namespace","all").contains(scope))c.fail("VALIDATION_FAILED","Invalid scope");
        if(scope.equals("namespace"))scoped("viewer");else c.revision=c.server.revision(c.state);
        if(dryRun)readContext();else operational();
        String bindingScope=scope.equals("all")?"server":c.namespaceId;
        long hours=integer(request.getOrDefault("minimumAgeHours",48L));if(hours<0||hours>Long.MAX_VALUE/3_600_000)c.fail("VALIDATION_FAILED","Invalid minimum age");long age=hours*3_600_000;
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.admin.pruneResources","request",request)));
        if(!dryRun) {
            Node binding=repo.binding(c.actorId,c.operationId);if(binding!=null&&!bindingScope.equals(binding.getProperty("scope")))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
            Node receipt=repo.receipt(bindingScope,c.operationId);if(receipt!=null){if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");return repo.replay(receipt,bindingScope).data();}
        }
        List<Node> locations=repo.nodes("DepositResourceLocation",scope.equals("all")?Map.of():Map.of("namespaceId",c.namespaceId));
        for(String ns:locations.stream().map(n->text(n,"namespaceId")).distinct().sorted().toList())repo.lockNamespace(ns);
        var candidates=new ArrayList<String>();long bytes=0;for(Node loc:locations){resources.lock(CoreRepository.id(loc));if(resources.eligible(loc,age)){candidates.add(CoreRepository.id(loc));bytes+=number(loc,"bytes",0);}}
        if(dryRun)return Json.map("candidateIds",candidates,"counts",(long)candidates.size(),"eligibleBytes",bytes);
        String job=UUID.randomUUID().toString();resources.record("DepositPruneJob",job,Json.map("scope",bindingScope,"actorId",c.actorId,"minimumAge",age,"createdAt",resources.now()));
        for(String id:candidates)resources.record("DepositPruneItem",job+"/"+id,Json.map("jobId",job,"resourceId",id,"state","pending","reclaimedBytes",0L));
        var result=Json.map("pruneId",job,"candidateIds",candidates,"counts",(long)candidates.size(),"scheduledBytes",bytes);repo.saveReceipt(c,bindingScope,hash,new Outcome(null,null,result));return result;
    }
    public Map<String,Object> status(Map<String,Object> request) {
        fields(request,"pruneId");readContext();admin(authenticate());Node job=repo.find("DepositPruneJob","id",RequestContext.uuid(request.get("pruneId")));if(job==null)c.fail("NOT_FOUND","Collection job not found");
        if(!"server".equals(job.getProperty("scope"))) {scoped("viewer");if(!c.namespaceId.equals(job.getProperty("scope")))c.fail("SCOPE_MISMATCH","Job belongs to another Namespace");}else c.revision=c.server.revision(c.state);
        return jobStatus(repo,CoreRepository.id(job));
    }
    public static Map<String,Object> jobStatus(CoreRepository repo,String id) {
        long pending=0,completed=0,failed=0,bytes=0;var diagnostics=new ArrayList<String>();
        for(Node item:repo.nodes("DepositPruneItem",Map.of("jobId",id))) {switch(text(item,"state")){case "completed","skipped"->completed++;case "failed"->{failed++;diagnostics.add((String)item.getProperty("diagnostic","Collection failed"));}default->pending++;}bytes+=number(item,"reclaimedBytes",0);}
        return Json.map("pruneId",id,"state",pending>0?"pending":failed>0?"failed":"completed","pending",pending,"completed",completed,"failed",failed,"reclaimedBytes",bytes,"diagnostics",diagnostics);
    }
}
