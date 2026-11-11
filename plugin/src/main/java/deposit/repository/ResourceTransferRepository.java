package deposit.repository;
import deposit.common.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.neo4j.graphdb.Node;
import static deposit.repository.ResourceRepository.*;
/** Authorized transfer persistence; all graph writes use the supplied request transaction. */
public final class ResourceTransferRepository {
    private final RequestContext c;private final ResourceRepository resources;
    public ResourceTransferRepository(RequestContext c,ResourceRepository resources){this.c=c;this.resources=resources;}
    static long validateUploadBytes(long bytes){if(bytes<0)throw ExceptionHandler.fail("LIMIT_EXCEEDED","Upload size cannot be negative");return bytes;}
    private static void fields(Map<String,Object> request,String... keys){if(!Set.of(keys).containsAll(request.keySet()))throw ExceptionHandler.fail("VALIDATION_FAILED","Unknown transfer field");}
    public static Map<String,Object> beginResult(Node t){return Json.map("uploadId",CoreRepository.id(t),"chunkBytes",(long)ResourceFiles.CHUNK,"nextOffset",t.getProperty("nextOffset"));}
    public Map<String,Object> begin(Map<String,Object> request)throws IOException {
        fields(request,"filename","bytes","sha256","mediaType");String filename=StrictValues.string(request.get("filename")),checksum=ResourceFiles.hash(request.get("sha256")),mediaType=StrictValues.string(request.get("mediaType"));
        long bytes=validateUploadBytes(StrictValues.integer(request.get("bytes")));
        String upload=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),stored=ResourceFiles.filename(filename,checksum),staging="staging/"+c.namespaceId+"/"+upload+".part";
        StorageAdmission.Reservation reservation=admission("upload",upload,"resource-upload",Map.of(resources.files.root.resolve(staging),bytes,transactionVolume(),64L*1024),Json.map("resourceId",id,"stagingPath",staging,"expectedBytes",bytes,"nextOffset",0L));
        resources.lock(id);
        Node t=resources.record("DepositTransfer",upload,Json.map("namespaceId",c.namespaceId,"actorId",c.actorId,"resourceId",id,"filename",filename,"storedFilename",stored,"mediaType",mediaType,"bytes",bytes,"checksum",checksum,"nextOffset",0L,"state","uploading","expires",resources.now()+ResourceFiles.LEASE,"admissionId",reservation.id));
        resources.record("DepositResourceLocation",id,Json.map("namespaceId",c.namespaceId,"uploadId",upload,"storedFilename",stored,"stagingPath",staging,"bytes",bytes,"checksum",checksum,"leaseUntil",resources.now()+ResourceFiles.LEASE,"everAttached",false,"admissionId",reservation.id));return beginResult(t);
    }
    public Map<String,Object> chunk(Map<String,Object> request,Node t)throws IOException {
        fields(request,"uploadId","offset","bytesBase64","chunkSha256");
        if(!"uploading".equals(t.getProperty("state")))c.fail("RESOURCE_NOT_READY","Upload already finished");
        long offset=StrictValues.integer(request.get("offset"));String encoded=StrictValues.string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Chunk exceeds limit");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Base64");}
        if(bytes.length==0||bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Invalid chunk length");
        String checksum=ResourceFiles.hash(request.get("chunkSha256"));if(!checksum.equals(ResourceFiles.checksum(bytes)))c.fail("VALIDATION_FAILED","Chunk checksum mismatch");
        String uploadId=CoreRepository.id(t);Node old=resources.core.find("DepositTransferChunk",Map.of("uploadId",uploadId,"offset",offset));
        if(old!=null){Map<String,Object> replay=retryResult(old,checksum,bytes.length);resources.renew(t);return replay;}
        if(offset<0||offset!=number(t,"nextOffset",0)||bytes.length>number(t,"bytes",0)-offset)c.fail("VALIDATION_FAILED","Chunk must continue the committed offset within declared size");
        Node loc=resources.location(text(t,"resourceId"));String staging=text(loc,"stagingPath");StorageAdmission.Reservation reservation=admission(t,loc,number(t,"bytes",0)-number(t,"nextOffset",0));long argumentBytes=encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        t.setProperty("admissionId",reservation.id);loc.setProperty("admissionId",reservation.id);
        var intent=Json.map("resourceId",text(t,"resourceId"),"stagingPath",staging,"phase","chunk","offset",offset,"length",(long)bytes.length,"checksum",checksum,"expectedBytes",number(t,"bytes",0));resources.server.storage.admission.setWriteIntent(reservation,intent);
        resources.server.storage.admission.grow(reservation,Map.of(transactionVolume(),argumentBytes));
        boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->finishChunkAdmission(reservation,staging,bytes.length,transactionVolume(),argumentBytes,committed[0]));
        resources.files.append(staging,offset,bytes);
        c.server.storagePoint("chunk-flushed");
        long next;try{next=Math.addExact(offset,(long)bytes.length);}catch(ArithmeticException e){c.fail("LIMIT_EXCEEDED","Upload offset is not representable");return Map.of();}
        resources.record("DepositTransferChunk",UUID.randomUUID().toString(),Json.map("uploadId",uploadId,"offset",offset,"length",(long)bytes.length,"checksum",checksum,"nextOffset",next));
        t.setProperty("nextOffset",next);resources.renew(t);return Json.map("nextOffset",next);
    }

    static Map<String,Object> retryResult(Node committedChunk,String checksum,long length){
        long offset=number(committedChunk,"offset",-1),recordedLength=number(committedChunk,"length",-1),next=number(committedChunk,"nextOffset",-1);
        if(offset<0||recordedLength<=0||next<0||next-offset!=recordedLength)throw ExceptionHandler.fail("RESOURCE_CORRUPT","Committed upload chunk metadata is invalid");
        if(!checksum.equals(text(committedChunk,"checksum"))||recordedLength!=length)throw ExceptionHandler.fail("OPERATION_MISMATCH","Chunk input changed");
        return Json.map("nextOffset",next);
    }
    public Map<String,Object> finish(Map<String,Object> request,Node t)throws IOException {
        fields(request,"uploadId");String id=text(t,"resourceId");
        if(number(t,"nextOffset",0)!=number(t,"bytes",0))c.fail("RESOURCE_NOT_READY","Upload incomplete");
        Node loc=resources.location(id);StorageAdmission.Reservation reservation=admission(t,loc,0);
        t.setProperty("admissionId",reservation.id);loc.setProperty("admissionId",reservation.id);
        if(!resources.storage.exists(id)) {
            if(number(t,"bytes",0)==0)resources.files.append(text(loc,"stagingPath"),0,new byte[0]);
            resources.files.verify(text(loc,"stagingPath"),number(t,"bytes",0),text(t,"checksum"));
        }
        c.server.storagePoint("publication-before");boolean alreadyPublished=resources.storage.exists(id);if(!alreadyPublished){resources.server.storage.admission.setWriteIntent(reservation,Json.map("resourceId",id,"stagingPath",loc.getProperty("stagingPath",""),"phase","publication","expectedBytes",number(t,"bytes",0)));resources.server.storage.admission.grow(reservation,Map.of(resources.files.root,number(t,"bytes",0)));}
        resources.publish(resources.content(loc),loc.hasProperty("stagingPath")?resources.files.path(text(loc,"stagingPath")):null,reservation);c.server.storagePoint("publication-after");
        if(resources.resource(c.namespaceId,id)==null)resources.record("DResource",id,Json.map("namespaceId",c.namespaceId,"filename",t.getProperty("filename"),"storedFilename",t.getProperty("storedFilename"),"mediaType",t.getProperty("mediaType"),"bytes",t.getProperty("bytes"),"checksum",t.getProperty("checksum"),"state","ready","createdBy",c.actorId,"updatedBy",c.actorId,"createdAt",c.timestamp,"updatedAt",c.timestamp));
        t.setProperty("state","finished");loc.setProperty("published",true);resources.renew(t);
        c.server.storagePoint("ready-before-commit");boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->finishPublicationAdmission(reservation,committed[0]));
        return Json.map("resourceId",id,"checksum",t.getProperty("checksum"),"bytes",t.getProperty("bytes"),"state","ready");
    }
    public Map<String,Object> abort(Map<String,Object> request,Node t) {
        fields(request,"uploadId");if("finished".equals(t.getProperty("state")))c.fail("RESOURCE_NOT_READY","Finished uploads cannot be aborted");
        t.setProperty("state","aborted");t.setProperty("expires",0L);Node loc=resources.location(text(t,"resourceId"));loc.setProperty("leaseUntil",0L);resources.orphan(loc);String id=(String)t.getProperty("admissionId",loc.getProperty("admissionId",null));if(id!=null){try{var reservation=c.server.storage.admission.resume(id);boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->{if(committed[0])try{c.server.storage.admission.close(reservation);}catch(IOException failure){c.server.storage.diagnostic="Storage reservation cleanup pending";}});}catch(IOException failure){throw new UncheckedIOException(failure);}}return Json.map("aborted",true);
    }

    private StorageAdmission.Reservation admission(String kind,String owner,String purpose,Map<Path,Long> commitments,Map<String,Object> intent)throws IOException {
        try{return c.server.storage.admission.acquire(kind,owner,c.operationId,purpose,commitments,intent);}catch(StorageAdmission.StoragePressureException pressure){c.fail("LIMIT_EXCEEDED",pressure.getMessage());return null;}
    }
    private StorageAdmission.Reservation admission(Node transfer,Node location,long remaining)throws IOException {
        String reservationId=(String)transfer.getProperty("admissionId",location.getProperty("admissionId",null));
        if(reservationId!=null)return c.server.storage.admission.resume(reservationId);
        String upload=CoreRepository.id(transfer),staging=text(location,"stagingPath");return admission("upload",upload,"resource-upload",Map.of(resources.files.root.resolve(staging),remaining),Json.map("resourceId",text(transfer,"resourceId"),"stagingPath",staging,"expectedBytes",number(transfer,"bytes",0),"nextOffset",number(transfer,"nextOffset",0)));
    }
    private Path transactionVolume(){return c.server.database().databaseLayout().getTransactionLogsDirectory();}
    private void finishChunkAdmission(StorageAdmission.Reservation reservation,String staging,long length,Path transactionVolume,long argumentBytes,boolean committed){
        try{
            if(committed){resources.server.storage.admission.materialized(reservation,resources.files.root,length);resources.server.storage.admission.release(reservation,Map.of(transactionVolume,argumentBytes));resources.server.storage.admission.clearWriteIntent(reservation);}
            else resources.server.storage.admission.release(reservation,Map.of(transactionVolume,argumentBytes));
        }catch(IOException failure){resources.server.storage.diagnostic="Upload storage admission reconciliation pending";}
    }
    private void finishPublicationAdmission(StorageAdmission.Reservation reservation,boolean committed){
        if(!committed)return;
        try{resources.server.storage.admission.close(reservation);}
        catch(IOException failure){resources.server.storage.diagnostic="Published upload storage admission reconciliation pending";}
    }
}
