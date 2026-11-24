package deposit.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;

/** Bounded ZIP staging, manifest checksums and portable archive paths. */
final class PackageArchiveIO {
    private PackageArchiveIO() {}
    static void write(Path destination,Map<String,Object> manifest,Map<String,Object> graph,Map<String,Object> history,
            Map<String,Path> resources,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        Path workspace=Files.createTempDirectory(destination.getParent(),"deposit-package-");
        try {
            Path graphFile=workspace.resolve("graph.json");
            writeJson(graphFile,graph,budget,checkpoint);
            Path historyFile=history==null?null:workspace.resolve("history.json");
            if(historyFile!=null)writeJson(historyFile,history,budget,checkpoint);
            var members=resources.entrySet();
            Iterable<ExchangePackageCodec.StagedMember> blobs=()->members.stream().map(entry->
                new ExchangePackageCodec.StagedMember("resources/"+entry.getKey(),entry.getValue(),-1,entry.getKey())).iterator();
            write(destination,manifest,graphFile,historyFile,blobs,workspace,budget,checkpoint);
        } finally { deleteScratch(workspace); }
    }

    static void write(Path destination,Map<String,Object> manifest,Path graph,Path history,
            Iterable<ExchangePackageCodec.StagedMember> resources,Path workspace,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        try(var index=new PackageWorkspace(workspace,checkpoint,budget);
            var zip=new PackageZip.Writer(destination,workspace.resolve("central"),budget,checkpoint)) {
            var entries=index.<Object>map();
            add(zip,entries,new ExchangePackageCodec.StagedMember("graph.json",graph,-1,null),checkpoint);
            if(history!=null)add(zip,entries,new ExchangePackageCodec.StagedMember("history.json",history,-1,null),checkpoint);
            for(var member:resources)add(zip,entries,member,checkpoint);
            var canonical=new LinkedHashMap<>(manifest);
            canonical.put("files",entries);
            canonical.put("includeHistory",history!=null);
            Path manifestFile=workspace.resolve("manifest.json");
            writeJson(manifestFile,canonical,budget,checkpoint);
            zip.add("manifest.json",manifestFile);
            zip.finish();
        }
    }

    private static void add(PackageZip.Writer zip,Map<String,Object> entries,ExchangePackageCodec.StagedMember member,
            Runnable checkpoint)throws IOException {
        String name=member.name();
        boolean resource=name.matches("resources/[a-f0-9]{64}");
        if(!resource&&!Set.of("graph.json","history.json").contains(name))throw new IOException("Unsupported package member");
        long length=Files.size(member.path());
        String hash=ResourceFiles.checksum(member.path(),checkpoint);
        if(member.bytes()!=-1 && member.bytes()!=length || member.sha256()!=null && !member.sha256().equals(hash)
                || resource && !name.substring(10).equals(hash))throw new IOException("Staged member content differs");
        if(entries.put(name,Json.map("bytes",length,"sha256",hash))!=null)throw new IOException("Duplicate package member");
        zip.add(name,member.path());
    }

    static void writeJson(Path target,Object value,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        try(var out=PackageValidationContext.accounted(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW),budget,checkpoint)) {
            Json.write(out,value);
        } catch(IllegalArgumentException failure) {
            if(failure.getCause() instanceof IOException io)throw io;
            throw failure;
        }
    }

    static void deleteScratch(Path root)throws IOException {
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }

    record Contents(Set<String> paths,Map<String,String> hashes,Map<String,Long> sizes,Map<String,Path> jsonFiles) {}
    static Map<String,Path> readVerifiedJson(Path archive,PackageValidationContext context,
            ExchangePackageCodec.ArchiveIdentity identity,boolean includeHistory)throws IOException {
        var jsonFiles=new HashMap<String,Path>();
        var members=includeHistory?Set.of("manifest.json","graph.json","history.json"):Set.of("manifest.json","graph.json");
        context.checkpoint();
        if(!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS))throw new IOException("Verified archive is unavailable");
        try(var file=new RandomAccessFile(archive.toFile(),"r")) {
            Runnable checkpoint=()->{
                try{context.checkpoint();}catch(InterruptedIOException failure){throw new UncheckedIOException(failure);}
            };
            // Check the same opened archive that supplies the payload. Its owner holds the storage pin.
            if(file.length()!=identity.bytes()||!ResourceFiles.checksum(java.nio.channels.Channels.newInputStream(file.getChannel()),checkpoint).equals(identity.sha256())
                    ||file.length()!=identity.bytes())throw new IOException("Verified archive content changed");
            byte[] buffer=new byte[64*1024];
            PackageZip.readVerified(file,members::contains,checkpoint,(name,stream,length)->{
                Path target=context.directory.resolve(name);
                try(var output=context.output(target)) {
                    for(int count;(count=stream.read(buffer))!=-1;)output.write(buffer,0,count);
                }
                jsonFiles.put(name,target);
            });
        }
        if(!jsonFiles.containsKey("manifest.json")||!jsonFiles.containsKey("graph.json"))
            throw new IOException("Package requires manifest.json and graph.json");
        return jsonFiles;
    }

    static Contents read(Path archive,PackageValidationContext context,Path resources)throws IOException {
        Set<String> seen=context.workspace==null?new HashSet<>():context.workspace.set();
        Map<String,String> hashes=context.workspace==null?new HashMap<>():context.workspace.map();
        Map<String,Long> sizes=context.workspace==null?new HashMap<>():context.workspace.map();
        Map<String,Long> spans=context.workspace==null?new HashMap<>():context.workspace.map();
        var jsonFiles=new HashMap<String,Path>();
        long[] expanded={0};
        byte[] buffer=new byte[64*1024];
        PackageZip.read(archive,spans,()->{
            try {context.checkpoint();}catch(InterruptedIOException e){throw new UncheckedIOException(e);}
        },(name,stream,length)->{
            safePath(name);
            boolean resource=name.matches("resources/[a-f0-9]{64}");
            if(!resource&&!Set.of("manifest.json","graph.json","history.json").contains(name))throw new IOException("Unsupported package member");
            if(!seen.add(name))throw new IOException("Duplicate package member");
            expanded[0]=checkedAdd(expanded[0],length);
            Path target=resource?(resources==null?null:resources.resolve(name.substring(10))):context.directory.resolve(name);
            var digest=sha256();
            try(var output=target==null?OutputStream.nullOutputStream():context.output(target)) {
                for(int count;(count=stream.read(buffer))!=-1;) {
                    digest.update(buffer,0,count);
                    output.write(buffer,0,count);
                }
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            if(resource&&!hash.equals(name.substring(10)))throw new IOException("Resource content does not match its content-addressed entry");
            hashes.put(name,hash);
            sizes.put(name,length);
            if(!resource)jsonFiles.put(name,target);
        });
        if(!jsonFiles.containsKey("manifest.json")||!jsonFiles.containsKey("graph.json"))throw new IOException("Package requires manifest.json and graph.json");
        return new Contents(seen,hashes,sizes,jsonFiles);
    }
    static long checkedAdd(long left,long right)throws IOException {try{return Math.addExact(left,right);}catch(ArithmeticException e){throw new IOException("Package size is not representable",e);}}
    private static void safePath(String name)throws IOException {
        if(name==null||name.isBlank()||name.startsWith("/")||name.startsWith("\\")||name.matches("^[A-Za-z]:.*")||name.indexOf('\\')>=0)throw new IOException("Unsafe package path");
        for(String part:name.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe package path");
    }
    static void validateManifest(Map<String,Object> manifest,Map<String,String> actual,Map<String,Long> sizes)throws IOException {
        for(String field:List.of("packageId","sourceNamespaceId","name","description","depositVersion","historyMode","exportedRevision","createdAt","rootIds","scope","files","includeHistory"))if(!manifest.containsKey(field))throw new IOException("Missing manifest field: "+field);
        if(!Set.of("packageId","sourceNamespaceId","name","description","depositVersion","historyMode","exportedRevision","createdAt","rootIds","scope","files","includeHistory","maxHops","rootClassBoundary","boundaryReferences","omittedEdgeCount").containsAll(manifest.keySet()))throw new IOException("Unknown manifest field");
        try{PackageIdentity.canonical((String)manifest.get("packageId"));PackageIdentity.canonical((String)manifest.get("sourceNamespaceId"));}catch(RuntimeException e){throw new IOException("Invalid manifest identity");}
        for(String field:List.of("name","description","createdAt"))if(!(manifest.get(field) instanceof String)&&!(field.equals("description")&&manifest.get(field) instanceof PackageWorkspace.Text))throw new IOException("Invalid manifest text");
        Object depositVersion=manifest.get("depositVersion");
        if(depositVersion!=null&&(!(depositVersion instanceof String version)||!version.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")))throw new IOException("Invalid manifest deposit version");
        if(!(manifest.get("includeHistory") instanceof Boolean))throw new IOException("Invalid history flag");
        if(!Set.of("disabled","transient","persistent").contains(manifest.get("historyMode")))throw new IOException("Invalid Namespace history mode");
        PackageValueCodec.metadataInteger(manifest.get("exportedRevision"));
        try{java.time.ZonedDateTime.parse((String)manifest.get("createdAt"));}catch(RuntimeException e){throw new IOException("Invalid export timestamp",e);}
        if(!(manifest.get("rootIds") instanceof List<?> roots))throw new IOException("Invalid root IDs");for(Object id:roots)try{PackageIdentity.canonical((String)id);}catch(RuntimeException e){throw new IOException("Invalid root UUID");}
        if(!Set.of("namespace","subgraph").contains(manifest.get("scope")))throw new IOException("Invalid package scope");
        if("subgraph".equals(manifest.get("scope"))&&Boolean.TRUE.equals(manifest.get("includeHistory")))throw new IOException("Subgraph packages do not include Namespace history");
        boolean expectedHistory="namespace".equals(manifest.get("scope"))&&"persistent".equals(manifest.get("historyMode"));
        if(Boolean.TRUE.equals(manifest.get("includeHistory"))!=expectedHistory)throw new IOException("Package history flag differs from Namespace history mode");
        if("namespace".equals(manifest.get("scope"))&&!roots.isEmpty()||"subgraph".equals(manifest.get("scope"))&&roots.isEmpty())throw new IOException("Package roots differ from its scope");
        if(manifest.containsKey("maxHops"))PackageValueCodec.metadataInteger(manifest.get("maxHops"));
        if(manifest.containsKey("maxHops")&&(!(manifest.get("maxHops") instanceof Number hops)||hops.longValue()<0||hops.longValue()>10))throw new IOException("Invalid traversal limit");
        if(manifest.containsKey("rootClassBoundary")&&!(manifest.get("rootClassBoundary") instanceof Boolean))throw new IOException("Invalid root boundary setting");
        if(manifest.containsKey("omittedEdgeCount"))PackageValueCodec.metadataInteger(manifest.get("omittedEdgeCount"));
        if(manifest.containsKey("omittedEdgeCount")&&(!(manifest.get("omittedEdgeCount") instanceof Number omitted)||omitted.longValue()<0))throw new IOException("Invalid omitted edge count");
        if(manifest.containsKey("boundaryReferences")){if(!(manifest.get("boundaryReferences") instanceof List<?> boundaries))throw new IOException("Invalid boundary references");for(Object raw:boundaries){var row=ValueCodec.map(raw);if(!row.keySet().equals(Set.of("kind","id","sourceNamespaceId"))||!Set.of("Object","Class","Pattern","Descriptor","Relationship","ClassRelationship").contains(row.get("kind"))||!Objects.equals(row.get("sourceNamespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("Invalid boundary reference");try{PackageIdentity.canonical((String)row.get("id"));}catch(RuntimeException e){throw new IOException("Invalid boundary reference UUID");}}}
        Object files=manifest.get("files");if(!(files instanceof Map<?,?> fm))throw new IOException("Invalid manifest files");
        long matched=0;
        for(var item:fm.entrySet()) {
            if(!(item.getKey() instanceof String path)||!(item.getValue() instanceof Map<?,?> info)||!info.keySet().equals(Set.of("bytes","sha256")))throw new IOException("Invalid file manifest row");
            safePath(path);if(path.equals("manifest.json"))throw new IOException("Manifest cannot hash itself");matched=Math.addExact(matched,1);
            PackageValueCodec.metadataInteger(info.get("bytes"));if(!(info.get("bytes") instanceof Number bytes)||bytes.longValue()<0||!Objects.equals(bytes.longValue(),sizes.get(path)))throw new IOException("Invalid file length");
            Object digest=info.get("sha256");if(!(digest instanceof String s)||!s.matches("[a-f0-9]{64}")||!s.equals(actual.get(path)))throw new IOException("Package file checksum mismatch");
        }
        long members=actual instanceof LongSized sized?sized.longSize():actual.size();
        if(matched!=members-1)throw new IOException("Manifest file list does not match archive");
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
