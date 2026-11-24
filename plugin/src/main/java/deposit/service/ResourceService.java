package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import deposit.repository.ResourceTransferStore.Transfer;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import org.neo4j.graphdb.*;
import static deposit.repository.ResourceRepository.*;

public final class ResourceService extends BaseService {
    private final ResourceRepository resources;
    public ResourceService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}
    private Transfer transfer(Object id,boolean active) {
        Transfer t=resources.transfer(RequestContext.uuid(id));
        if(t==null||!c.actorId.equals(t.actor()))c.fail("NOT_FOUND","Upload not found");
        if(!c.namespaceId.equals(t.namespace()))c.fail("SCOPE_MISMATCH","Upload belongs to another Namespace");
        resources.lock(t.resource());
        UploadBinding.requireUpload(c,t.id(),t.writerBound());
        if(active&&(!Set.of("uploading","finished").contains(t.state())||!t.writerBound()&&t.expires()<=resources.now()))c.fail("NOT_FOUND","Upload expired or aborted");return t;
    }
    private void operational() {
        OperationalGuards.operationalContext(c);
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","One mutation per transaction");c.state.mutation=true;repo.lockActor(c.actorId);
    }
    public Map<String,Object> transfer(String method,Map<String,Object> request) {
        scoped("editor");operational();repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,"editor");c.revision=repo.sequence(c.namespaceId);
        String hash=c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.resource."+method,"request",request)));
        Map<String,Object> result;
        try {result=switch(method){case "beginUpload"->new ResourceTransferRepository(c,resources).begin(request);case "uploadChunk"->new ResourceTransferRepository(c,resources).chunk(request,transfer(request.get("uploadId"),true));case "finishUpload"->new ResourceTransferRepository(c,resources).finish(request,transfer(request.get("uploadId"),true));case "abortUpload"->new ResourceTransferRepository(c,resources).abort(request,transfer(request.get("uploadId"),false));default->throw new IllegalArgumentException();};}
        catch(StorageAdmission.StoragePressureException e){throw ExceptionHandler.fail("LIMIT_EXCEEDED",e.getMessage());}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Transfer storage unavailable");}
        repo.saveReceipt(c,c.namespaceId,hash,new Outcome(null,null,result));return result;
    }
    public Map<String,Object> read(Map<String,Object> request,boolean download) {
        readContext();scoped("viewer");if(download)fields(request,"resourceId","offset","length");else fields(request,"resourceId");
        String id=RequestContext.uuid(request.get("resourceId"));repo.lockNamespace(c.namespaceId);resources.lock(id);Node r=resources.resource(c.namespaceId,id);
        if(r==null||!resources.accessible(r,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        if(!"ready".equals(r.getProperty("state")))c.fail("RESOURCE_NOT_READY","Resource is not ready");
        Map<String,Object> result;
        if(!download){resources.verifyCompleted(r);result=resources.info(r);}
        else {
            long offset=integer(request.get("offset")),length=integer(request.get("length")),size=number(r,"bytes",0);
            if(offset<0||offset>size||length<1||length>ResourceFiles.MAX_CHUNK)c.fail("VALIDATION_FAILED","Invalid download range");
            int actual=(int)Math.min(length,size-offset);
            try {byte[] data=resources.storage.readChunk(resources.content(r),offset,actual);result=Json.map("bytesBase64",Base64.getEncoder().encodeToString(data),"offset",offset,"nextOffset",offset+actual,"eof",offset+actual==size,"chunkSha256",ResourceFiles.checksum(data));}
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
        if(!dryRun&&bindingScope.equals("server"))c.server.lockServer(c.state);
        long hours=integer(request.getOrDefault("minimumAgeHours",48L));if(hours<0||hours>Long.MAX_VALUE/3_600_000)c.fail("VALIDATION_FAILED","Invalid minimum age");long age=hours*3_600_000;
        String hash=c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.admin.pruneResources","request",request)));
        var locations=resources.collectionCandidates(scope.equals("all")?null:c.namespaceId);
        for(String ns:new TreeSet<>(locations.values()))repo.lockNamespace(ns);
        var candidates=new ArrayList<String>();long bytes=0;
        for(String id:locations.keySet()) {
            resources.lock(id);
            var resource=resources.state(id);
            if(resources.eligible(resource,age)){
                candidates.add(id);
                try{bytes=Math.addExact(bytes,resource.bytes());}
                catch(ArithmeticException overflow){c.fail("LIMIT_EXCEEDED","Eligible resource size is not representable");}
            }
        }
        if(dryRun)return Json.map("candidateIds",candidates,"counts",(long)candidates.size(),"eligibleBytes",bytes);
        String job=UUID.randomUUID().toString();
        var owner=bindingScope.equals("server")?user:repo.namespace(c.namespaceId);
        String unit=c.server.prepareWorkflowCommit(c,bindingScope,owner);
        c.server.storage.pruning.create(new PruneRepository.Job(job,bindingScope,c.actorId,age,resources.now(),c.server.runId),
            candidates,unit);
        var result=Json.map("pruneId",job,"candidateIds",candidates,"counts",(long)candidates.size(),"scheduledBytes",bytes);
        repo.saveReceipt(c,bindingScope,hash,new Outcome(null,null,result));
        return result;
    }
    public Map<String,Object> status(Map<String,Object> request) {
        fields(request,"pruneId");readContext();admin(authenticate());
        var pruning=c.server.storage.pruning;
        var job=pruning.job(RequestContext.uuid(request.get("pruneId")));
        if(job==null)c.fail("NOT_FOUND","Collection job not found");
        if(!"server".equals(job.scope())) {
            scoped("viewer");
            if(!c.namespaceId.equals(job.scope()))c.fail("SCOPE_MISMATCH","Job belongs to another Namespace");
        }else c.revision=c.server.revision(c.state);
        long pending=0,completed=0,failed=0,bytes=0;
        var diagnostics=new ArrayList<String>();
        for(var item:pruning.items(job.id())) {
            switch(item.state()) {
                case "completed","skipped" -> completed++;
                case "failed" -> {
                    failed++;
                    diagnostics.add(Objects.requireNonNullElse(item.diagnostic(),"Collection failed"));
                }
                default -> pending++;
            }
            try{bytes=Math.addExact(bytes,item.reclaimed());}
            catch(ArithmeticException overflow){throw ExceptionHandler.fail("LIMIT_EXCEEDED","Reclaimed resource size is not representable");}
        }
        if(pruning.job(job.id())==null)c.fail("NOT_FOUND","Collection job not found");
        return Json.map("pruneId",job.id(),"state",pending>0?"pending":failed>0?"failed":"completed",
            "pending",pending,"completed",completed,"failed",failed,"reclaimedBytes",bytes,"diagnostics",diagnostics);
    }
}
