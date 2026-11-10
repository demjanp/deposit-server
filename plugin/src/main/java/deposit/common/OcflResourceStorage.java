package deposit.common;

import io.ocfl.api.*;
import io.ocfl.api.model.*;
import io.ocfl.core.OcflRepositoryBuilder;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** The only application class that interprets OCFL inventories and physical layout. */
public final class OcflResourceStorage implements ResourceStorage {
    private static final String EXTENSION="0004-hashed-n-tuple-storage-layout";
    private final Path root,work;
    private final OcflRepository repository;
    private final FileChannel ownerChannel;
    private final FileLock owner;

    public OcflResourceStorage(Path root,Path work) throws IOException {
        this.root=root.toAbsolutePath().normalize();this.work=work.toAbsolutePath().normalize();
        checkTree(this.root);checkTree(this.work);
        if(this.root.startsWith(this.work)||this.work.startsWith(this.root)||!Files.getFileStore(this.root).equals(Files.getFileStore(this.work)))throw new IOException("Invalid OCFL work root");
        ownerChannel=FileChannel.open(this.root.resolveSibling("ocfl-owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired=null;OcflRepository opened=null;
        try {
            acquired=ownerChannel.tryLock();if(acquired==null)throw new IOException("OCFL storage already owned");
            try(var entries=Files.list(this.root)){if(entries.findAny().isPresent())checkLayout();}
            opened=new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig().setDigestAlgorithm(DigestAlgorithmRegistry.sha256).setTupleSize(3).setNumberOfTuples(3).setShortObjectRoot(false))
                .ocflConfig(new OcflConfig().setOcflVersion(OcflVersion.OCFL_1_1).setDefaultDigestAlgorithm(DigestAlgorithmRegistry.sha256))
                .storage(s->s.fileSystem(this.root)).workDir(this.work).inventoryCache(null).verifyStaging(true).build();
            checkLayout();owner=acquired;repository=opened;
        }catch(Exception e){if(opened!=null)opened.close();if(acquired!=null)acquired.release();ownerChannel.close();throw new IOException("Cannot initialize OCFL storage",e);}
    }
    private void checkLayout() throws IOException {
        if(!Files.isRegularFile(root.resolve("0=ocfl_1.1"),LinkOption.NOFOLLOW_LINKS)||!"ocfl_1.1\n".equals(Files.readString(root.resolve("0=ocfl_1.1"))))throw new IOException("OCFL 1.1 root required");
        Map<String,Object> layout=Json.read(Files.readString(root.resolve("ocfl_layout.json")));
        Map<String,Object> config=Json.read(Files.readString(root.resolve("extensions").resolve(EXTENSION).resolve("config.json")));
        if(!EXTENSION.equals(layout.get("extension"))||!EXTENSION.equals(config.get("extensionName"))||!"sha256".equals(config.get("digestAlgorithm"))
            ||!Long.valueOf(3).equals(asLong(config.get("tupleSize")))||!Long.valueOf(3).equals(asLong(config.get("numberOfTuples")))||!Boolean.FALSE.equals(config.get("shortObjectRoot")))throw new IOException("Unsupported OCFL layout configuration");
    }
    private static Long asLong(Object value){return value instanceof Number n?n.longValue():null;}
    private static void id(String value)throws IOException {
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Invalid storage identity");
    }
    private static void metadata(Content content)throws IOException {
        id(content.resourceId());String name=content.storedFilename();
        if(name==null||name.isEmpty()||!name.matches("[a-zA-Z0-9._-]+")||name.equals(".")||name.equals("..")||content.bytes()<0
            ||content.checksum()==null||!content.checksum().matches("[0-9a-f]{64}"))throw new IOException("Invalid immutable storage metadata");
    }
    private Path objectRoot(String resourceId)throws IOException {
        id(resourceId);String digest=ResourceFiles.checksum(resourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path path=root.resolve(digest.substring(0,3)).resolve(digest.substring(3,6)).resolve(digest.substring(6,9)).resolve(digest);
        for(Path p=path;p!=null&&p.startsWith(root);p=p.getParent())checkEntry(p);
        return path;
    }
    private static void checkEntry(Path path)throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(path)||!path.toAbsolutePath().normalize().equals(path.toRealPath())))throw new IOException("Linked storage path");
    }
    private static void checkTree(Path path)throws IOException {
        checkEntry(path);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))try(var paths=Files.walk(path)){for(Path entry:paths.toList())checkEntry(entry);}
    }
    private OcflObjectVersion profile(String resourceId)throws IOException {
        try {
            Path path=objectRoot(resourceId);checkTree(path);
            if(repository.validateObject(resourceId,false).hasErrors())throw new IOException("Invalid OCFL object");
            ObjectDetails details=repository.describeObject(resourceId);
            if(!resourceId.equals(details.getId())||details.getObjectOcflVersion()!=OcflVersion.OCFL_1_1||!DigestAlgorithmRegistry.sha256.equals(details.getDigestAlgorithm())
                ||details.getVersionMap().size()!=1||!"v1".equals(details.getHeadVersionNum().toString()))throw new IOException("Invalid immutable OCFL profile");
            var object=repository.getObject(ObjectVersionId.version(resourceId,"v1"));
            if(object.isMutable()||object.getFiles().size()!=1)throw new IOException("One immutable OCFL payload required");
            return object;
        }catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("OCFL content unavailable",e);}
    }
    @Override public boolean exists(String resourceId)throws IOException{return Files.exists(objectRoot(resourceId),LinkOption.NOFOLLOW_LINKS);}
    @Override public synchronized void publish(Content content,Path stagedFile)throws IOException {
        metadata(content);
        if(exists(content.resourceId())){verify(content);return;}
        if(stagedFile==null)throw new IOException("Missing publication source");checkEntry(stagedFile);
        if(!Files.isRegularFile(stagedFile,LinkOption.NOFOLLOW_LINKS)||Files.size(stagedFile)!=content.bytes()||!ResourceFiles.checksum(stagedFile).equals(content.checksum()))throw new IOException("Invalid publication source");
        try {
            // Deposit holds the resource lock through graph completion; there is one root owner.
            // updateObject is used only to CREATE v1 with a controlled logical name, never to update.
            repository.updateObject(ObjectVersionId.head(content.resourceId()),new VersionInfo().setMessage("Deposit immutable resource"),u->u.addPath(stagedFile,content.storedFilename()));
            forceTree(objectRoot(content.resourceId()));
            verify(content);
        }catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("OCFL publication failed",e);}
    }
    @Override public void verify(Content content)throws IOException {
        metadata(content);var object=profile(content.resourceId());
        if(!object.containsFile(content.storedFilename()))throw new IOException("OCFL payload name differs");
        try(var in=object.getFile(content.storedFilename()).getStream()){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];long size=0;int n;
            while((n=in.read(buffer))!=-1){try{size=Math.addExact(size,(long)n);}catch(ArithmeticException e){throw new IOException("OCFL payload length is not representable",e);}if(size>content.bytes())throw new IOException("OCFL payload length differs");digest.update(buffer,0,n);}
            if(size!=content.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(content.checksum()))throw new IOException("OCFL payload integrity differs");
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OCFL verification failed",e);}
    }
    @Override public InputStream open(Content content,long offset,long length)throws IOException {
        if(offset<0||length<0||offset>content.bytes()||length>content.bytes()-offset)throw new IOException("Invalid storage range");
        verify(content);if(length==0)return new ByteArrayInputStream(new byte[0]);
        try{return repository.getObject(ObjectVersionId.version(content.resourceId(),"v1")).getFile(content.storedFilename()).getRange(offset,offset+length-1);}
        catch(RuntimeException e){throw new IOException("OCFL read failed",e);}
    }
    @Override public List<Content> discover()throws IOException {
        var result=new ArrayList<Content>();
        try(var ids=repository.listObjectIds()){
            for(String resourceId:ids.toList()){
                var object=profile(resourceId);var file=object.getFiles().iterator().next();
                long size;try(var in=file.getStream()){size=in.transferTo(OutputStream.nullOutputStream());}
                String checksum=file.getFixity().get(DigestAlgorithmRegistry.sha256);
                Content content=new Content(resourceId,file.getPath(),size,checksum);metadata(content);result.add(content);
            }
        }catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("OCFL discovery failed",e);}
        // Library enumeration finds inventories. Separately reject incomplete installations,
        // misplaced objects and foreign files; never turn each inventory file into an orphan.
        Set<Path> found=new HashSet<>();for(var content:result)found.add(objectRoot(content.resourceId()));
        inspectDirectories(root,0,found);
        return result;
    }
    private void inspectDirectories(Path directory,int depth,Set<Path> found)throws IOException {
        try(var entries=Files.list(directory)){
            for(Path path:entries.toList()){
                String name=path.getFileName().toString();checkEntry(path);
                if(depth==0&&Set.of("0=ocfl_1.1","ocfl_1.1.md","ocfl_extensions_1.0.md",EXTENSION+".md","ocfl_layout.json","extensions").contains(name))continue;
                if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!name.matches(depth<3?"[0-9a-f]{3}":"[0-9a-f]{64}"))throw new IOException("Unexpected OCFL root entry: "+root.relativize(path));
                if(depth==3){if(!found.contains(path))throw new IOException("Incomplete or misplaced OCFL installation");}
                else inspectDirectories(path,depth+1,found);
            }
        }
    }
    @Override public long physicalBytes(String resourceId)throws IOException {
        Path path=objectRoot(resourceId);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return 0;checkTree(path);
        long result=0;try(var paths=Files.walk(path)){for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())try{result=Math.addExact(result,Files.size(file));}catch(ArithmeticException overflow){throw new IOException("OCFL physical size is not representable",overflow);}}return result;
    }
    @Override public void purge(String resourceId)throws IOException {
        Path path=objectRoot(resourceId);checkTree(path);
        try{repository.purgeObject(resourceId);}catch(RuntimeException e){throw new IOException("OCFL purge incomplete",e);}
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("OCFL object removal incomplete");
    }
    @Override public void recoverWork()throws IOException {
        // The root is exclusively owned and no work has started. Sources were copied, not consumed.
        checkTree(work);try(var paths=Files.walk(work)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())if(!p.equals(work))Files.delete(p);}
    }
    private static void forceTree(Path path)throws IOException {
        try(var paths=Files.walk(path)){for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}}
    }
    @Override public void close(){try{repository.close();}finally{try{owner.release();ownerChannel.close();}catch(IOException e){throw new IllegalStateException("Cannot close OCFL storage",e);}}}
}
