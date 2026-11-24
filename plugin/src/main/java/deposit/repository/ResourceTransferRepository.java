package deposit.repository;
import deposit.common.*;
import java.util.*;
import java.nio.file.Path;
import java.io.IOException;
import deposit.repository.ResourceTransferStore.Transfer;
/** Authorized transfer persistence coupled to the enclosing action. */
public final class ResourceTransferRepository {
    private final RequestContext c;private final ResourceRepository resources;
    public ResourceTransferRepository(RequestContext c,ResourceRepository resources){this.c=c;this.resources=resources;}
    static long validateUploadBytes(long bytes){if(bytes<0)throw ExceptionHandler.fail("LIMIT_EXCEEDED","Upload size cannot be negative");return bytes;}
    private static void fields(Map<String,Object> request,String... keys){if(!Set.of(keys).containsAll(request.keySet()))throw ExceptionHandler.fail("VALIDATION_FAILED","Unknown transfer field");}
    public static Map<String,Object> beginResult(Transfer t){return Json.map("uploadId",t.id(),"chunkBytes",(long)ResourceFiles.CHUNK,"nextOffset",t.nextOffset());}
    public Map<String,Object> begin(Map<String,Object> request)throws IOException {
        fields(request,"filename","bytes","sha256","mediaType");String filename=StrictValues.string(request.get("filename")),checksum=ResourceFiles.hash(request.get("sha256")),mediaType=StrictValues.string(request.get("mediaType"));
        long bytes=validateUploadBytes(StrictValues.integer(request.get("bytes")));
        String upload=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),stored=ResourceFiles.filename(filename,checksum),staging="staging/"+c.namespaceId+"/"+upload+".part";
        StorageAdmission.Reservation reservation=admission("upload",upload,"resource-upload",Map.of(resources.files.root.resolve(staging),bytes,transactionVolume(),64L*1024),Json.map("resourceId",id,"stagingPath",staging));
        resources.lock(id);
        var transfer=new Transfer(upload,id,c.namespaceId,c.actorId,filename,stored,mediaType,bytes,checksum,
            0,"uploading",resources.now()+ResourceFiles.LEASE,reservation.id,staging,c.state.operationClaimed,
            null,null,false,false,null);
        UploadBinding.bindStaging(c,upload);
        save(transfer);
        return beginResult(transfer);
    }
    public Map<String,Object> chunk(Map<String,Object> request,Transfer t)throws IOException {
        fields(request,"uploadId","offset","bytesBase64");
        if(!"uploading".equals(t.state()))c.fail("RESOURCE_NOT_READY","Upload already finished");
        long offset=StrictValues.integer(request.get("offset"));String encoded=StrictValues.string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Chunk exceeds limit");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Base64");}
        if(bytes.length==0||bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Invalid chunk length");
        if(offset<0||offset!=t.nextOffset()||bytes.length>t.bytes()-offset)c.fail("VALIDATION_FAILED","Chunk must continue the committed offset within declared size");
        String staging=t.stagingPath();
        StorageAdmission.Reservation reservation=admission(t,t.bytes()-t.nextOffset());
        long argumentBytes=encoded.length();
        resources.server.storage.admission.grow(reservation,Map.of(transactionVolume(),argumentBytes));
        boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->finishChunkAdmission(reservation,bytes.length,transactionVolume(),argumentBytes,committed[0]));
        resources.files.append(staging,offset,bytes);
        c.server.storagePoint("chunk-flushed");
        long next;try{next=Math.addExact(offset,(long)bytes.length);}catch(ArithmeticException e){c.fail("LIMIT_EXCEEDED","Upload offset is not representable");return Map.of();}
        save(t.progress(next,"uploading",resources.now()+ResourceFiles.LEASE,reservation.id,null));return Json.map("nextOffset",next);
    }

    public Map<String,Object> finish(Map<String,Object> request,Transfer t)throws IOException {
        fields(request,"uploadId");String id=t.resource();
        if(t.nextOffset()!=t.bytes())c.fail("RESOURCE_NOT_READY","Upload incomplete");
        if("finished".equals(t.state())){
            var resource=resources.resource(c.namespaceId,id);
            resources.verifyCompleted(resource);
            long expires=resources.now()+ResourceFiles.LEASE;
            resource.setProperty("leaseUntil",expires);
            resource.removeProperty("orphanSince");
            save(t.progress(t.nextOffset(),"finished",expires,t.admissionId(),null));
            return readyResult(t);
        }
        StorageAdmission.Reservation reservation=admission(t,0);
        if(!resources.storage.exists(id)) {
            if(t.bytes()==0)resources.files.append(t.stagingPath(),0,new byte[0]);
            resources.files.verify(t.stagingPath(),t.bytes(),t.checksum(),c.state.kernel::assertOpen);
        }
        c.server.storagePoint("publication-before");
        if(!resources.storage.exists(id))resources.server.storage.admission.grow(reservation,Map.of(resources.files.root,t.bytes()));
        resources.publish(t.content(),resources.files.path(t.stagingPath()),reservation);
        c.server.storagePoint("publication-after");
        resources.complete(c.namespaceId,t.filename(),t.mediaType(),t.content(),c.actorId,c.timestamp);
        save(t.progress(t.nextOffset(),"finished",resources.now()+ResourceFiles.LEASE,reservation.id,null));
        c.server.storagePoint("ready-before-commit");boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->finishPublicationAdmission(reservation,committed[0]));
        return readyResult(t);
    }
    private Map<String,Object> readyResult(Transfer t){
        return Json.map("resourceId",t.resource(),"checksum",t.checksum(),"bytes",t.bytes(),"state","ready");
    }
    public Map<String,Object> abort(Map<String,Object> request,Transfer t) {
        fields(request,"uploadId");
        if("finished".equals(t.state()))c.fail("RESOURCE_NOT_READY","Finished uploads cannot be aborted");
        save(t.progress(t.nextOffset(),"aborted",0,t.admissionId(),
            t.orphanSince()==null?resources.now():t.orphanSince()));
        return Json.map("aborted",true);
    }

    private StorageAdmission.Reservation admission(String kind,String owner,String purpose,Map<Path,Long> commitments,Map<String,Object> intent)throws IOException {
        try{return c.server.storage.admission.acquire(kind,owner,c.operationId,purpose,commitments,intent);}catch(StorageAdmission.StoragePressureException pressure){c.fail("LIMIT_EXCEEDED",pressure.getMessage());return null;}
    }
    private StorageAdmission.Reservation admission(Transfer transfer,long remaining)throws IOException {
        if(transfer.admissionId()!=null)return c.server.storage.admission.existing(transfer.admissionId());
        String staging=transfer.stagingPath();
        return admission("upload",transfer.id(),"resource-upload",Map.of(resources.files.root.resolve(staging),remaining),
            Json.map("resourceId",transfer.resource(),"stagingPath",staging));
    }
    private void save(Transfer transfer) {
        String unit=c.server.prepareWorkflowCommit(c,c.namespaceId,resources.core.namespace(c.namespaceId));
        c.server.storage.transfers.save(transfer,unit);
    }
    private Path transactionVolume(){return c.server.database().databaseLayout().getTransactionLogsDirectory();}
    private void finishChunkAdmission(StorageAdmission.Reservation reservation,long length,Path transactionVolume,long argumentBytes,boolean committed){
        try{
            if(committed){resources.server.storage.admission.materialized(reservation,resources.files.root,length);resources.server.storage.admission.release(reservation,Map.of(transactionVolume,argumentBytes));}
            else resources.server.storage.admission.release(reservation,Map.of(transactionVolume,argumentBytes));
        }catch(IOException failure){resources.server.storage.diagnostic="Upload storage admission reconciliation pending";}
    }
    private void finishPublicationAdmission(StorageAdmission.Reservation reservation,boolean committed){
        if(!committed)return;
        try{resources.server.storage.admission.close(reservation);}
        catch(IOException failure){resources.server.storage.diagnostic="Published upload storage admission reconciliation pending";}
    }
}
