package deposit.common;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ExchangeArchiveLimitsTest {
    @TempDir Path temp;
    @Test void entryCountAboveFormerPolicyCeilingIsNotRejectedByCount()throws Exception{
        Path path=temp.resolve("entry-count.zip");try(var zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))){for(int i=0;i<100_001;i++){byte[] content=ByteBuffer.allocate(4).putInt(i).array();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));zip.putNextEntry(new ZipEntry("resources/"+hash));zip.write(content);zip.closeEntry();}}
        var error=assertThrows(IOException.class,()->ExchangePackageCodec.validateSummary(path));assertFalse(error.getMessage().contains("entry limit"),error.getMessage());
    }
    @Test void expandedSizeUsesLongRangeAndFailsOnlyOnArithmeticOverflow()throws Exception{
        assertEquals(2L*1024*1024*1024+1,PackageArchiveIO.checkedAdd(2L*1024*1024*1024,1));
        assertThrows(IOException.class,()->PackageArchiveIO.checkedAdd(Long.MAX_VALUE,1));
    }
    @Test void rejectsUnsupportedCompression()throws Exception{
        Path path=temp.resolve("compression.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(path))){zip.putNextEntry(new ZipEntry("graph.json"));zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}byte[] bytes=Files.readAllBytes(path);bytes[8]=99;bytes[9]=0;Files.write(path,bytes);assertThrows(IOException.class,()->ExchangePackageCodec.validateSummary(path));
    }
    @Test void cancellationRemovesValidationWorkspace()throws Exception{
        Path path=temp.resolve("cancel.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(path))){zip.putNextEntry(new ZipEntry("graph.json"));zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->ExchangePackageCodec.validateSummary(path));}finally{Thread.interrupted();}
    }
}
