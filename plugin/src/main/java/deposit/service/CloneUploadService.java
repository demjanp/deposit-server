package deposit.service;

import deposit.common.*;
import deposit.repository.ResourceRepository;
import deposit.repository.CoreRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

/** Actor-owned, server-scoped staging for complete Namespace clone packages. */
final class CloneUploadService extends ActionService {
    private static final String UPLOAD = "DepositCloneUpload";
    private final ResourceRepository resources;
    CloneUploadService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    Map<String,Object> execute(String method,Map<String,Object> request){
        return switch(method){
            case "beginCloneUpload"->begin(request);
            case "cloneUploadChunk"->chunk(request);
            case "finishCloneUpload"->finish(request);
            case "abortCloneUpload"->abort(request);
            default->throw new IllegalArgumentException();
        };
    }

    private Map<String,Object> begin(Map<String,Object> request){
        fields(request,"filename","bytes","sha256");String filename=string(request.get("filename"));
        long bytes=integer(request.get("bytes"));if(bytes<0)c.fail("VALIDATION_FAILED","Clone package size cannot be negative");
        String checksum=ResourceFiles.hash(request.get("sha256"));String id=UUID.randomUUID().toString();
        Path path=path("staging/imports/clone-uploads/"+id+".zip");String relative=relative(path);
        return action("deposit.import.beginCloneUpload",request,true,"viewer",()->{
            Node existing=find(UPLOAD,Map.of("operationId",c.operationId));
            if(existing!=null) {
                if(!c.actorId.equals(existing.getProperty("actorId"))||!filename.equals(existing.getProperty("filename"))||
                        !checksum.equals(existing.getProperty("sha256"))||bytes!=((Number)existing.getProperty("bytes")).longValue())c.fail("OPERATION_MISMATCH","Prepared clone upload identity changed");
                return new Change(new Results.Outcome(null,null,Json.map("uploadId",CoreRepository.id(existing),"chunkBytes",(long)ResourceFiles.CHUNK,"nextOffset",((Number)existing.getProperty("nextOffset")).longValue())),false);
            }
            try{
                var admission=c.server.storage.admission.acquire("clone-upload",id,c.operationId,"namespace-clone-upload",Map.of(resources.files.root,bytes),Json.map("phase","upload","uploadId",id,"actorId",c.actorId,"bytes",bytes,"nextOffset",0L,"sha256",checksum,"stagingPath",relative));
                Node upload=c.tx.createNode(Label.label(UPLOAD));Map<String,Object> values=Json.map("id",id,"actorId",c.actorId,"filename",filename,"bytes",bytes,"sha256",checksum,"nextOffset",0L,"operationId",c.operationId,"state","uploading","expires",resources.now()+ResourceFiles.LEASE,"stagingPath",relative,"admissionId",admission.id);
                values.forEach(upload::setProperty);boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->{if(!committed[0])try{c.server.storage.admission.close(admission);}catch(IOException failure){c.server.storage.diagnostic="Rolled-back clone upload admission cleanup pending";}});return new Change(new Results.Outcome(null,null,Json.map("uploadId",id,"chunkBytes",(long)ResourceFiles.CHUNK,"nextOffset",0L)),true);
            }catch(IOException e){throw new java.io.UncheckedIOException(e);}
        }).data();
    }

    private Map<String,Object> chunk(Map<String,Object> request){
        fields(request,"uploadId","offset","bytesBase64","chunkSha256");String id=RequestContext.uuid(request.get("uploadId"));long offset=integer(request.get("offset"));String encoded=string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Clone upload chunk exceeds limit");byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){c.fail("VALIDATION_FAILED","Invalid Base64");return Map.of();}
        if(bytes.length==0||bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Invalid clone upload chunk length");String checksum=ResourceFiles.hash(request.get("chunkSha256"));if(!checksum.equals(ResourceFiles.checksum(bytes)))c.fail("VALIDATION_FAILED","Chunk checksum mismatch");
        return action("deposit.import.cloneUploadChunk",request,true,"viewer",()->{
            Node upload=owned(id,true);String relative=(String)upload.getProperty("stagingPath");long expected=((Number)upload.getProperty("bytes")).longValue(),next=((Number)upload.getProperty("nextOffset")).longValue();
            Node previous=find("DepositCloneUploadChunk",Map.of("uploadId",id,"offset",offset));if(previous!=null){if(!checksum.equals(previous.getProperty("checksum"))||bytes.length!=((Number)previous.getProperty("length")).longValue())c.fail("OPERATION_MISMATCH","Clone chunk retry changed input");return new Change(new Results.Outcome(null,null,Json.map("nextOffset",previous.getProperty("nextOffset"))),false);}
            if(offset!=next||offset<0||bytes.length>expected-offset)c.fail("VALIDATION_FAILED","Clone chunk must continue the committed offset");
            Path target=path(relative);StorageAdmission.Reservation admission;long encodedBytes=encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;Path logs;
            try{admission=c.server.storage.admission.resume((String)upload.getProperty("admissionId"));logs=c.server.database().databaseLayout().getTransactionLogsDirectory();c.server.storage.admission.grow(admission,Map.of(logs,encodedBytes));c.server.storage.admission.setWriteIntent(admission,Json.map("phase","chunk","uploadId",id,"actorId",c.actorId,"bytes",expected,"nextOffset",next,"sha256",upload.getProperty("sha256"),"stagingPath",relative,"offset",offset,"length",(long)bytes.length,"chunkSha256",checksum));}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            try{resources.files.append(relative,offset,bytes);long updated=Math.addExact(next,(long)bytes.length);String chunkId=id+"/"+offset;Node chunk=c.tx.createNode(Label.label("DepositCloneUploadChunk"));chunk.setProperty("id",chunkId);chunk.setProperty("uploadId",id);chunk.setProperty("offset",offset);chunk.setProperty("length",(long)bytes.length);chunk.setProperty("checksum",checksum);chunk.setProperty("nextOffset",updated);upload.setProperty("nextOffset",updated);
                boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->{try{if(committed[0]){c.server.storage.admission.materialized(admission,resources.files.root,bytes.length);c.server.storage.admission.release(admission,Map.of(logs,encodedBytes));}else{resources.files.truncate(relative,next);c.server.storage.admission.release(admission,Map.of(logs,encodedBytes));}c.server.storage.admission.clearWriteIntent(admission);}catch(IOException failure){c.server.storage.diagnostic="Clone upload admission reconciliation pending";}});
                return new Change(new Results.Outcome(null,null,Json.map("nextOffset",updated)),true);
            }catch(IOException e){throw new java.io.UncheckedIOException(e);}
        }).data();
    }

    private Map<String,Object> finish(Map<String,Object> request){
        fields(request,"uploadId");String id=RequestContext.uuid(request.get("uploadId"));return action("deposit.import.finishCloneUpload",request,true,"viewer",()->{
            Node upload=owned(id,true);long bytes=((Number)upload.getProperty("bytes")).longValue(),next=((Number)upload.getProperty("nextOffset")).longValue();if(bytes!=next)c.fail("RESOURCE_NOT_READY","Clone upload is incomplete");Path target=path((String)upload.getProperty("stagingPath"));
            try{if(!Files.exists(target))Files.createFile(target);resources.files.verify((String)upload.getProperty("stagingPath"),bytes,(String)upload.getProperty("sha256"));upload.setProperty("state","finished");upload.setProperty("expires",resources.now()+ResourceFiles.LEASE);return new Change(new Results.Outcome(null,null,Json.map("uploadId",id,"bytes",bytes,"sha256",upload.getProperty("sha256"),"state","ready")),true);}catch(IOException e){c.fail("RESOURCE_CORRUPT","Clone package integrity verification failed");return new Change(new Results.Outcome(null,null,Map.of()),false);}
        }).data();
    }

    private Map<String,Object> abort(Map<String,Object> request){
        fields(request,"uploadId");String id=RequestContext.uuid(request.get("uploadId"));return action("deposit.import.abortCloneUpload",request,true,"viewer",()->{
            Node upload=owned(id,false);upload.setProperty("state","aborted");upload.setProperty("expires",0L);return new Change(new Results.Outcome(null,null,Json.map("aborted",true)),true);
        }).data();
    }

    private Node owned(String id,boolean active){Node upload=find(UPLOAD,Map.of("id",id));if(upload==null)c.fail("NOT_FOUND","Clone upload not found");c.tx.acquireWriteLock(upload);upload=find(UPLOAD,Map.of("id",id));if(upload==null||!c.actorId.equals(upload.getProperty("actorId")))c.fail("NOT_FOUND","Clone upload not found");if(active&&(!Set.of("uploading","finished").contains(upload.getProperty("state"))||((Number)upload.getProperty("expires")).longValue()<=resources.now()))c.fail("NOT_FOUND","Clone upload expired or aborted");return upload;}
    private Node find(String label,Map<String,Object> properties){try(var found=c.tx.findNodes(Label.label(label),properties)){return found.hasNext()?found.next():null;}}
    private Path path(String value){try{return resources.files.path(value);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}
    private String relative(Path value){return c.server.storage.files.root.relativize(value).toString().replace('\\','/');}
}
