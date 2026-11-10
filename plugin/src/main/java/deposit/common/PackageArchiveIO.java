package deposit.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;
import java.util.zip.*;

/** Bounded ZIP staging, manifest checksums and portable archive paths. */
final class PackageArchiveIO {
    private PackageArchiveIO() {}
    static void write(Path destination, Map<String,Object> manifest, Map<String,Object> graph,
                             Map<String,Object> history, Map<String,Path> resources) throws IOException {
        Path workspace=Files.createTempDirectory(destination.getParent(),"deposit-package-");
        try {
            var generated=new TreeMap<String,Path>();var contentFiles=new TreeMap<String,Path>();var fileInfo=new TreeMap<String,Object>();
            Path graphFile=workspace.resolve("graph.json");try(OutputStream out=Files.newOutputStream(graphFile)){Json.write(out,graph);}generated.put("graph.json",graphFile);
            if(history!=null){Path historyFile=workspace.resolve("history.json");try(OutputStream out=Files.newOutputStream(historyFile)){Json.write(out,history);}generated.put("history.json",historyFile);}
            for(var item:resources.entrySet()) {
                String hash=item.getKey().toLowerCase(Locale.ROOT);if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid resource checksum");Path file=item.getValue();String actual=ResourceFiles.checksum(file);
                if(!hash.equals(actual))throw new IOException("Resource checksum mismatch");String path="resources/"+hash;contentFiles.put(path,file);fileInfo.put(path,Json.map("bytes",Files.size(file),"sha256",hash));
            }
            for(var entry:generated.entrySet())fileInfo.put(entry.getKey(),Json.map("bytes",Files.size(entry.getValue()),"sha256",ResourceFiles.checksum(entry.getValue())));
            long expanded=0;for(Path file:generated.values())expanded=checkedAdd(expanded,Files.size(file));for(Path file:contentFiles.values())expanded=checkedAdd(expanded,Files.size(file));
            var finalManifest=new LinkedHashMap<>(manifest);finalManifest.put("files",fileInfo);finalManifest.put("includeHistory",history!=null);Path manifestFile=workspace.resolve("manifest.json");try(OutputStream out=Files.newOutputStream(manifestFile)){Json.write(out,finalManifest);}
            expanded=checkedAdd(expanded,Files.size(manifestFile));
            var all=new TreeMap<String,Path>();all.putAll(generated);all.putAll(contentFiles);all.put("manifest.json",manifestFile);
            try(var out=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)))) {byte[] buffer=new byte[64*1024];for(var entry:all.entrySet()){var zip=new ZipEntry(entry.getKey());zip.setTime(0);out.putNextEntry(zip);try(var in=Files.newInputStream(entry.getValue())){int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.closeEntry();}}
        } finally {try(var paths=Files.walk(workspace)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
    }

    record Contents(Set<String> paths,Map<String,String> hashes,Map<String,Long> sizes,Map<String,Path> jsonFiles) {}
    static Contents read(Path archive,PackageValidationContext context)throws IOException {
        var seen=new HashSet<String>();var hashes=new HashMap<String,String>();var sizes=new HashMap<String,Long>();
        var jsonFiles=new HashMap<String,Path>();long expanded=0;
        try(var raw=Files.newInputStream(archive);var zip=new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();safePath(name);
                if(!Set.of("manifest.json","graph.json","history.json").contains(name)&&!name.matches("resources/[a-f0-9]{64}"))throw new IOException("Unsupported package entry");
                if(!seen.add(name))throw new IOException("Duplicate package entry");
                if(entry.isDirectory())throw new IOException("Directory entries are not allowed");
                if(entry.getMethod()!=ZipEntry.DEFLATED&&entry.getMethod()!=ZipEntry.STORED)throw new IOException("Unsupported compression method");
                MessageDigest digest=sha256();Path jsonFile=null;OutputStream json=null;
                if(name.equals("manifest.json")||name.equals("graph.json")||name.equals("history.json")){jsonFile=context.directory.resolve(name);json=Files.newOutputStream(jsonFile,StandardOpenOption.CREATE_NEW);}
                byte[] buffer=new byte[64*1024];long size=0;int n;
                try {while((n=zip.read(buffer))!=-1) {
                    context.checkpoint();size=checkedAdd(size,n);expanded=checkedAdd(expanded,n);
                    digest.update(buffer,0,n);if(json!=null)json.write(buffer,0,n);
                }} finally {if(json!=null)json.close();}
                String hash=HexFormat.of().formatHex(digest.digest());hashes.put(name,hash);
                if(name.startsWith("resources/")&&!hash.equals(name.substring(10)))throw new IOException("Resource content does not match its content-addressed entry");
                sizes.put(name,size);
                if(name.startsWith("resources/")&&!name.substring(10).matches("[a-f0-9]{64}"))throw new IOException("Invalid resource entry path");
                if(jsonFile!=null)jsonFiles.put(name,jsonFile);
                zip.closeEntry();
            }
        }
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
        for(String field:List.of("name","description","depositVersion","createdAt"))if(!(manifest.get(field) instanceof String))throw new IOException("Invalid manifest text");
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
        var expected=new HashSet<String>();
        for(var item:fm.entrySet()) {
            if(!(item.getKey() instanceof String path)||!(item.getValue() instanceof Map<?,?> info))throw new IOException("Invalid file manifest row");
            safePath(path);if(path.equals("manifest.json"))throw new IOException("Manifest cannot hash itself");expected.add(path);
            PackageValueCodec.metadataInteger(info.get("bytes"));if(!(info.get("bytes") instanceof Number bytes)||bytes.longValue()<0||!Objects.equals(bytes.longValue(),sizes.get(path)))throw new IOException("Invalid file length");
            Object digest=info.get("sha256");if(!(digest instanceof String s)||!s.matches("[a-f0-9]{64}")||!s.equals(actual.get(path)))throw new IOException("Package file checksum mismatch");
        }
        var actualFiles=new HashSet<>(actual.keySet());actualFiles.remove("manifest.json");
        if(!actualFiles.equals(expected))throw new IOException("Manifest file list does not match archive");
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
