package deposit.common;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.*;
import java.io.*;
import java.util.*;

/** Deposit staging and pure validation. Completed content belongs to ResourceStorage. */
public final class ResourceFiles {
    public static final long MAX_BYTES=2L*1024*1024*1024,LEASE=86_400_000L;
    public static final int CHUNK=1024*1024,MAX_CHUNK=4*CHUNK;
    public final Path root,exports;
    public ResourceFiles() {
        try {
            var config=new Properties();try(var in=Files.newInputStream(Path.of(System.getProperty("deposit.config")))){config.load(in);}
            root=checkedRoot(config.getProperty("resourceRoot"));exports=checkedRoot(config.getProperty("exportRoot"));
            if(root.startsWith(exports)||exports.startsWith(root))throw new IOException("Overlapping storage roots");
            for(String name:List.of("staging","ocfl","ocfl-work")){
                Path child=root.resolve(name);Files.createDirectories(child);checkedRoot(child.toString());
                if(!Files.getFileStore(child).equals(Files.getFileStore(root)))throw new IOException("Different filesystem");
            }
        }catch(IOException e){throw new IllegalStateException("Invalid resource storage configuration",e);}
    }
    private static Path checkedRoot(String value)throws IOException {
        if(value==null)throw new IOException("Missing storage root");Path path=Path.of(value).toAbsolutePath().normalize();
        if(!Path.of(value).isAbsolute()||!Files.isDirectory(path)||!Files.isWritable(path)||!path.equals(path.toRealPath()))throw new IOException("Unsafe storage root");
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked storage root");return path;
    }
    public Path path(String relative)throws IOException {
        Path suffix=Path.of(relative),p=root.resolve(suffix).normalize();
        if(suffix.isAbsolute()||!p.startsWith(root.resolve("staging"))||p.equals(root.resolve("staging")))throw new IOException("Unsafe staging path");
        for(Path part=root; !part.equals(p);) {
            part=part.resolve(part.relativize(p).getName(0));
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(part)||!part.toRealPath().equals(part)))throw new IOException("Linked resource path");
        }return p;
    }
    public static String checksum(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String checksum(Path path)throws IOException {
        try {var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String hash(Object input) {
        if(!(input instanceof String s)||!s.matches("[a-fA-F0-9]{64}"))throw ExceptionHandler.fail("VALIDATION_FAILED","SHA-256 must have 64 hexadecimal characters");return ((String)input).toLowerCase(Locale.ROOT);
    }
    public static String filename(String input,String hash) {
        String s=input.replaceAll("[^a-zA-Z0-9.\\-]","_");s=s.substring(0,Math.min(120,s.length()));if(s.isEmpty())s="resource";
        String generated=generate(s,hash),first=generated.split("\\.",2)[0];
        if(first.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))generated=generate("_"+s.substring(0,Math.min(119,s.length())),hash);
        return generated;
    }
    private static String generate(String s,String hash) {int dot=s.lastIndexOf('.');return dot>0&&dot<s.length()-1?s.substring(0,dot)+"-"+hash+s.substring(dot):s+"-"+hash;}
    public void append(String location,long offset,byte[] bytes)throws IOException {
        Path p=path(location);Files.createDirectories(p.getParent());path(location);
        try(var channel=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            if(channel.size()<offset)throw new IOException("Missing committed upload bytes");channel.truncate(offset);channel.position(offset);
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    public void verify(String location,long bytes,String checksum)throws IOException {Path p=path(location);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=bytes||!checksum(p).equals(checksum))throw new IOException("Resource content unavailable");}
}
