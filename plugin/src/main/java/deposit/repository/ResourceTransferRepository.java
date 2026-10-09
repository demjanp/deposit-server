package deposit.repository;
import deposit.common.*;
import java.util.*;
import java.nio.file.Files;
import java.io.IOException;
import org.neo4j.graphdb.Node;
import static deposit.repository.ResourceRepository.*;
/** Authorized transfer persistence; all graph writes use the supplied request transaction. */
public final class ResourceTransferRepository {
    private final RequestContext c;private final ResourceRepository resources;
    public ResourceTransferRepository(RequestContext c,ResourceRepository resources){this.c=c;this.resources=resources;}
    private static void fields(Map<String,Object> request,String... keys){if(!Set.of(keys).containsAll(request.keySet()))throw ExceptionHandler.fail("VALIDATION_FAILED","Unknown transfer field");}
    private static String string(Object value){if(!(value instanceof String))throw ExceptionHandler.fail("VALIDATION_FAILED","Expected String");return (String)value;}
    private static long integer(Object value){if(!(value instanceof Long||value instanceof Integer))throw ExceptionHandler.fail("VALIDATION_FAILED","Expected Integer");return ((Number)value).longValue();}
    public static Map<String,Object> beginResult(Node t){return Json.map("uploadId",CoreRepository.id(t),"chunkBytes",(long)ResourceFiles.CHUNK,"nextOffset",t.getProperty("nextOffset"));}
    public Map<String,Object> begin(Map<String,Object> request) {
        fields(request,"filename","bytes","sha256","mediaType");String filename=string(request.get("filename")),checksum=ResourceFiles.hash(request.get("sha256")),mediaType=string(request.get("mediaType"));
        long bytes=integer(request.get("bytes"));if(bytes<0||bytes>ResourceFiles.MAX_BYTES)c.fail("LIMIT_EXCEEDED","Upload size exceeds limit");
        String upload=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),stored=ResourceFiles.filename(filename,checksum),suffix=c.namespaceId+"/"+id+"/"+stored;
        resources.lock(id);
        Node t=resources.record("DepositTransfer",upload,Json.map("namespaceId",c.namespaceId,"actorId",c.actorId,"resourceId",id,"filename",filename,"storedFilename",stored,"mediaType",mediaType,"bytes",bytes,"checksum",checksum,"nextOffset",0L,"chunks","{}","state","uploading","expires",resources.now()+ResourceFiles.LEASE));
        resources.record("DepositResourceLocation",id,Json.map("namespaceId",c.namespaceId,"uploadId",upload,"suffix",suffix,"path","staging/"+c.namespaceId+"/"+upload+".part","bytes",bytes,"checksum",checksum,"leaseUntil",resources.now()+ResourceFiles.LEASE,"everAttached",false));return beginResult(t);
    }
    public Map<String,Object> chunk(Map<String,Object> request,Node t)throws IOException {
        fields(request,"uploadId","offset","bytesBase64","chunkSha256");
        if(!"uploading".equals(t.getProperty("state")))c.fail("RESOURCE_NOT_READY","Upload already finished");
        long offset=integer(request.get("offset"));String encoded=string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Chunk exceeds limit");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Base64");}
        if(bytes.length==0||bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Invalid chunk length");
        String checksum=ResourceFiles.hash(request.get("chunkSha256"));if(!checksum.equals(ResourceFiles.checksum(bytes)))c.fail("VALIDATION_FAILED","Chunk checksum mismatch");
        Map<String,Object> chunks=Json.read(text(t,"chunks"));var old=chunks.get(Long.toString(offset));
        if(old!=null){var m=ValueCodec.map(old);if(!checksum.equals(m.get("checksum"))||((Number)m.get("length")).longValue()!=bytes.length)c.fail("OPERATION_MISMATCH","Chunk input changed");resources.renew(t);return Json.map("nextOffset",m.get("nextOffset"));}
        if(offset<0||offset!=number(t,"nextOffset",0)||bytes.length>number(t,"bytes",0)-offset)c.fail("VALIDATION_FAILED","Chunk must continue the committed offset within declared size");
        Node loc=resources.location(text(t,"resourceId"));resources.files.append(text(loc,"path"),offset,bytes);
        c.server.storagePoint("chunk-flushed");
        long next=offset+bytes.length;chunks.put(Long.toString(offset),Json.map("checksum",checksum,"length",(long)bytes.length,"nextOffset",next));t.setProperty("chunks",Json.write(chunks));t.setProperty("nextOffset",next);resources.renew(t);return Json.map("nextOffset",next);
    }
    public Map<String,Object> finish(Map<String,Object> request,Node t)throws IOException {
        fields(request,"uploadId");String id=text(t,"resourceId");
        if(number(t,"nextOffset",0)!=number(t,"bytes",0))c.fail("RESOURCE_NOT_READY","Upload incomplete");
        Node loc=resources.location(id);String destination="live/"+text(loc,"suffix");
        if(!Files.exists(resources.files.path(destination))) {
            if(number(t,"bytes",0)==0)resources.files.append(text(loc,"path"),0,new byte[0]);
            resources.files.verify(text(loc,"path"),number(t,"bytes",0),text(t,"checksum"));c.server.storagePoint("publication-before");resources.files.move(text(loc,"path"),destination);c.server.storagePoint("publication-after");
        }
        resources.files.verify(destination,number(t,"bytes",0),text(t,"checksum"));
        if(resources.resource(c.namespaceId,id)==null)resources.record("DResource",id,Json.map("namespaceId",c.namespaceId,"filename",t.getProperty("filename"),"storedFilename",t.getProperty("storedFilename"),"mediaType",t.getProperty("mediaType"),"bytes",t.getProperty("bytes"),"checksum",t.getProperty("checksum"),"state","ready","createdBy",c.actorId,"updatedBy",c.actorId,"createdAt",c.timestamp,"updatedAt",c.timestamp));
        t.setProperty("state","finished");loc.setProperty("path",destination);resources.renew(t);
        c.server.storagePoint("ready-before-commit");
        return Json.map("resourceId",id,"checksum",t.getProperty("checksum"),"bytes",t.getProperty("bytes"),"state","ready");
    }
    public Map<String,Object> abort(Map<String,Object> request,Node t) {
        fields(request,"uploadId");if("finished".equals(t.getProperty("state")))c.fail("RESOURCE_NOT_READY","Finished uploads cannot be aborted");
        t.setProperty("state","aborted");t.setProperty("expires",0L);Node loc=resources.location(text(t,"resourceId"));loc.setProperty("leaseUntil",0L);resources.orphan(loc);return Json.map("aborted",true);
    }
}
