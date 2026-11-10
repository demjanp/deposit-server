package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ocfl.api.model.ObjectVersionId;
import io.ocfl.api.model.VersionInfo;
import io.ocfl.core.OcflRepositoryBuilder;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Fixed fixtures and independent inventory inspection, including real library I/O. */
@Timeout(120)
class OcflResourceStorageTest {
    @TempDir Path directory;
    static final String ID="12345678-1234-4234-8234-123456789012";
    static final String HASH="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String NAME="abc-"+HASH+".txt";
    Path root,work,source;
    @BeforeEach void setup()throws Exception{root=Files.createDirectory(directory.resolve("ocfl"));work=Files.createDirectory(directory.resolve("work"));source=Files.writeString(directory.resolve("staged"),"abc");}
    ResourceStorage.Content content(){return new ResourceStorage.Content(ID,NAME,3,HASH);}
    Path object(String id)throws Exception{String h=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return root.resolve(h.substring(0,3)).resolve(h.substring(3,6)).resolve(h.substring(6,9)).resolve(h);}
    @Test void exactIdentityInventorySingleVersionAndIdempotentPublication()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            storage.publish(content(),source);storage.publish(content(),null);storage.verify(content());
            var inventory=new ObjectMapper().readTree(Files.readString(object(ID).resolve("inventory.json")));
            assertEquals(ID,inventory.get("id").asText());assertEquals("v1",inventory.get("head").asText());assertEquals(1,inventory.get("versions").size());
            assertEquals("sha256",inventory.get("digestAlgorithm").asText());assertEquals(NAME,inventory.at("/versions/v1/state/"+HASH+"/0").asText());
            assertEquals("abc",Files.readString(source));assertTrue(Files.exists(root.resolve("0=ocfl_1.1")));assertTrue(storage.physicalBytes(ID)>3);
        }
    }
    @Test void separateIdsRangesEmptyRangeAndIndependentStreams()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            String other="22345678-1234-4234-8234-123456789012";storage.publish(content(),source);storage.publish(new ResourceStorage.Content(other,NAME,3,HASH),source);
            assertEquals(2,storage.discover().size());assertNotEquals(object(ID),object(other));
            try(var range=storage.open(content(),1,2);var full=storage.open(content(),0,3)){assertArrayEquals(new byte[]{98,99},range.readAllBytes());assertArrayEquals(new byte[]{97,98,99},full.readAllBytes());}
            try(var empty=storage.open(content(),3,0)){assertEquals(-1,empty.read());}
            assertThrows(IOException.class,()->storage.open(content(),2,2));
        }
    }
    @Test void concurrentIdenticalPublicationNeverCreatesAnotherVersion()throws Exception{
        try(var storage=new OcflResourceStorage(root,work);var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();storage.publish(content(),source);return true;});var b=executor.submit(()->{start.await();storage.publish(content(),source);return true;});start.countDown();assertTrue(a.get());assertTrue(b.get());storage.verify(content());
            assertFalse(Files.exists(object(ID).resolve("v2")));
        }
    }
    @Test void mismatchedRetryCannotOverwriteAndFailedSourceLeavesNoObject()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            assertThrows(IOException.class,()->storage.publish(new ResourceStorage.Content(ID,NAME,4,HASH),source));assertFalse(storage.exists(ID));
            storage.publish(content(),source);Files.writeString(source,"xyz");
            var bad=new ResourceStorage.Content(ID,NAME,3,"0".repeat(64));assertThrows(IOException.class,()->storage.publish(bad,source));storage.verify(content());
        }
    }
    @Test void immutableMetadataDoesNotApplyAResourceSizePolicyCeiling()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            var large=new ResourceStorage.Content(ID,NAME,2L*1024*1024*1024+1,"0".repeat(64));
            var failure=assertThrows(IOException.class,()->storage.publish(large,source));
            assertEquals("Invalid publication source",failure.getMessage());
        }
    }
    @Test void payloadInventoryAndUnexpectedVersionCorruptionAreRejected()throws Exception{
        Path payload;byte[] inventory;
        try(var storage=new OcflResourceStorage(root,work)){
            storage.publish(content(),source);payload=object(ID).resolve("v1/content").resolve(NAME);Files.writeString(payload,"bad");assertThrows(IOException.class,()->storage.verify(content()));Files.writeString(payload,"abc");
            Path manifest=object(ID).resolve("inventory.json");inventory=Files.readAllBytes(manifest);Files.writeString(manifest,"{}");assertThrows(IOException.class,()->storage.verify(content()));Files.write(manifest,inventory);storage.verify(content());
        }
        var upstream=new OcflRepositoryBuilder().storage(s->s.fileSystem(root)).workDir(work).build();try{upstream.putObject(ObjectVersionId.head(ID),source,new VersionInfo());}finally{upstream.close();}
        try(var storage=new OcflResourceStorage(root,work)){assertThrows(IOException.class,()->storage.verify(content()));}
    }
    @Test void completeAndPartiallyInterruptedPurgeAreIdempotent()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            storage.publish(content(),source);Files.delete(object(ID).resolve("v1/inventory.json"));
            storage.purge(ID);storage.purge(ID);assertFalse(Files.exists(object(ID)));assertEquals(0,storage.physicalBytes(ID));assertTrue(Files.exists(root.resolve("ocfl_layout.json")));
        }
    }
    @Test void rootOwnershipAndLayoutMismatchFailExplicitly()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){assertThrows(IOException.class,()->new OcflResourceStorage(root,work));}
        Path config=root.resolve("extensions/0004-hashed-n-tuple-storage-layout/config.json");var tree=new ObjectMapper().readTree(Files.readString(config));((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("tupleSize",2);Files.writeString(config,tree.toString());
        assertThrows(IOException.class,()->new OcflResourceStorage(root,work));
    }
    @Test void workRecoveryPreservesUploadSourceAndPublishedContent()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){storage.publish(content(),source);Files.createDirectories(work.resolve("interrupted/content"));Files.writeString(work.resolve("interrupted/content/partial"),"partial");storage.recoverWork();assertEquals("abc",Files.readString(source));storage.verify(content());try(var entries=Files.list(work)){assertEquals(0,entries.count());}}
    }
    @Test void incompleteInstallationsForeignFilesAndUnsafeIdentitiesFailExplicitly()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            Files.createDirectories(object(ID));assertThrows(IOException.class,storage::discover);storage.purge(ID);
            Files.writeString(root.resolve("unexpected.part"),"partial");assertThrows(IOException.class,storage::discover);Files.delete(root.resolve("unexpected.part"));
            for(String bad:List.of("../outside","C:/escape",ID.toUpperCase()+".","CON"))assertThrows(IOException.class,()->storage.publish(new ResourceStorage.Content(bad,NAME,3,HASH),source));
            for(String bad:List.of("../outside","a/b","a\\b","..","name:stream"))assertThrows(IOException.class,()->storage.publish(new ResourceStorage.Content(ID,bad,3,HASH),source));
            storage.publish(content(),source);Files.delete(object(ID).resolve("v1/content").resolve(NAME));assertThrows(IOException.class,()->storage.open(content(),3,0));assertThrows(IOException.class,storage::discover);
        }
    }
    @Test void depositConfigurationRejectsLegacyOverlapAndStagingEscapes()throws Exception{
        String previous=System.getProperty("deposit.config");Path container=Files.createDirectory(directory.resolve("resources")),exports=Files.createDirectory(directory.resolve("exports")),config=directory.resolve("deposit.properties");
        try{
            Files.writeString(config,"resourceRoot="+container.toString().replace('\\','/')+"\nexportRoot="+exports.toString().replace('\\','/')+"\n");System.setProperty("deposit.config",config.toString());
            var staging=new ResourceFiles();assertThrows(IOException.class,()->staging.path("../outside"));assertThrows(IOException.class,()->staging.path("ocfl/object"));assertThrows(IOException.class,()->staging.path("staging/../../outside"));
            Files.writeString(config,"resourceRoot="+container.toString().replace('\\','/')+"\nexportRoot="+container.resolve("staging").toString().replace('\\','/')+"\n");assertThrows(IllegalStateException.class,ResourceFiles::new);
            Path nested=Files.createDirectory(root.resolve("work"));assertThrows(IOException.class,()->new OcflResourceStorage(root,nested));
        }finally{if(previous==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previous);}
    }
    @Test void zeroByteAndMaximumSizePayloads()throws Exception{
        try(var storage=new OcflResourceStorage(root,work)){
            Files.write(source,new byte[0]);String emptyHash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            var empty=new ResourceStorage.Content(ID,"empty-"+emptyHash,0,emptyHash);storage.publish(empty,source);storage.verify(empty);storage.purge(ID);
            long size=2147483648L;try(var channel=FileChannel.open(source,StandardOpenOption.WRITE)){channel.position(size-1);channel.write(ByteBuffer.wrap(new byte[1]));channel.force(true);}
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] zeros=new byte[1024*1024];for(int i=0;i<2048;i++)digest.update(zeros);
            String hash=HexFormat.of().formatHex(digest.digest());var maximum=new ResourceStorage.Content(ID,"maximum-"+hash,size,hash);storage.publish(maximum,source);storage.verify(maximum);
            try(var tail=storage.open(maximum,size-2,2)){assertArrayEquals(new byte[2],tail.readAllBytes());}
        }
    }
}
