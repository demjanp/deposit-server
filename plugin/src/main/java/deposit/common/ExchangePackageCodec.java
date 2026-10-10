package deposit.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Portable Deposit exchange ZIP writer and bounded, independent archive validator. */
public final class ExchangePackageCodec {
    public static final int MAX_ENTRIES=100_000;
    public static final long MAX_EXPANDED=2L*1024*1024*1024;
    public record ValidationSummary(String packageId,String sourceNamespaceId,String scope,long exportedRevision,boolean includeHistory,Map<String,Long> counts) {}
    public static ValidationSummary validateSummary(Path archive)throws IOException{return validateSummary(archive,()->{});}
    public static ValidationSummary validateSummary(Path archive,Runnable checkpoint)throws IOException{return validate(archive,true,checkpoint,pkg->{var counts=new TreeMap<String,Long>();pkg.graph().forEach((key,value)->{if(value instanceof List<?> rows)counts.put(key,(long)rows.size());});return new ValidationSummary((String)pkg.manifest().get("packageId"),(String)pkg.manifest().get("sourceNamespaceId"),(String)pkg.manifest().get("scope"),((Number)pkg.manifest().get("exportedRevision")).longValue(),pkg.history()!=null,Collections.unmodifiableMap(counts));});}
    public record PackageData(Map<String,Object> manifest, Map<String,Object> graph, Map<String,Object> history) {}
    private ExchangePackageCodec() {}

    public static void write(Path destination, Map<String,Object> manifest, Map<String,Object> graph,
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
            long expanded=0;for(Path file:generated.values())expanded+=Files.size(file);for(Path file:contentFiles.values())expanded+=Files.size(file);
            if(contentFiles.size()+generated.size()+1>MAX_ENTRIES||expanded>MAX_EXPANDED)throw new IOException("Package entry or expanded size limit exceeded");
            var finalManifest=new LinkedHashMap<>(manifest);finalManifest.put("files",fileInfo);finalManifest.put("includeHistory",history!=null);Path manifestFile=workspace.resolve("manifest.json");try(OutputStream out=Files.newOutputStream(manifestFile)){Json.write(out,finalManifest);}
            if(expanded+Files.size(manifestFile)>MAX_EXPANDED)throw new IOException("Package expanded size limit exceeded");
            var all=new TreeMap<String,Path>();all.putAll(generated);all.putAll(contentFiles);all.put("manifest.json",manifestFile);
            try(var out=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)))) {byte[] buffer=new byte[64*1024];for(var entry:all.entrySet()){var zip=new ZipEntry(entry.getKey());zip.setTime(0);out.putNextEntry(zip);try(var in=Files.newInputStream(entry.getValue())){int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.closeEntry();}}
        } finally {try(var paths=Files.walk(workspace)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
    }

    /** Validate archive paths, compression, bounds and manifest checksums without buffering blobs. */
    public static PackageData validate(Path archive) throws IOException {return validate(archive,false,()->{},java.util.function.Function.identity());}
    private static <T> T validate(Path archive,boolean streaming,Runnable checkpoint,java.util.function.Function<PackageData,T> finish) throws IOException {
        checkpoint.run();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Package validation cancelled");
        var seen=new HashSet<String>();var hashes=new HashMap<String,String>();var sizes=new HashMap<String,Long>();
        Path workspacePath=Files.createTempDirectory("deposit-package-validation-");
        var jsonFiles=new HashMap<String,Path>();long expanded=0;int count=0;
        try (var workspace=streaming?new PackageWorkspace(workspacePath,checkpoint):null) {
        try(var raw=Files.newInputStream(archive);var zip=new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(++count>MAX_ENTRIES)throw new IOException("Package entry limit exceeded");
                String name=entry.getName();safePath(name);
                if(!Set.of("manifest.json","graph.json","history.json").contains(name)&&!name.matches("resources/[a-f0-9]{64}"))throw new IOException("Unsupported package entry");
                if(!seen.add(name))throw new IOException("Duplicate package entry");
                if(entry.isDirectory())throw new IOException("Directory entries are not allowed");
                if(entry.getMethod()!=ZipEntry.DEFLATED&&entry.getMethod()!=ZipEntry.STORED)throw new IOException("Unsupported compression method");
                MessageDigest digest=sha256();Path jsonFile=null;OutputStream json=null;
                if(name.equals("manifest.json")||name.equals("graph.json")||name.equals("history.json")){jsonFile=workspacePath.resolve(name);json=Files.newOutputStream(jsonFile,StandardOpenOption.CREATE_NEW);}
                byte[] buffer=new byte[64*1024];long size=0;int n;
                try {while((n=zip.read(buffer))!=-1) {
                    checkpoint.run();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Package validation cancelled");size+=n;expanded+=n;if(expanded>MAX_EXPANDED)throw new IOException("Package expanded size limit exceeded");
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
        Map<String,Object> manifest=workspace==null?Json.read(Files.readString(jsonFiles.get("manifest.json"),StandardCharsets.UTF_8)):workspace.parse(jsonFiles.get("manifest.json"));
        Map<String,Object> graph=workspace==null?Json.read(Files.readString(jsonFiles.get("graph.json"),StandardCharsets.UTF_8)):workspace.parse(jsonFiles.get("graph.json"));
        Map<String,Object> history=jsonFiles.containsKey("history.json")?(workspace==null?Json.read(Files.readString(jsonFiles.get("history.json"),StandardCharsets.UTF_8)):workspace.parse(jsonFiles.get("history.json"))):null;
        var original=new PackageData(manifest,graph,history);
        if(workspace==null){manifest=PackageIdentity.view(manifest);graph=PackageIdentity.view(graph);if(history!=null)history=PackageIdentity.view(history);}
        validateManifest(manifest,hashes,sizes);
        validateGraph(graph,seen,manifest,workspace);
        if((history!=null)!=Boolean.TRUE.equals(manifest.get("includeHistory"))&&manifest.containsKey("includeHistory"))throw new IOException("History manifest mismatch");
        if(history!=null){if(!Objects.equals(history.get("namespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("History provenance Namespace differs");validateHistory(history,graph,"namespace".equals(manifest.get("scope")),workspace);}
        return finish.apply(workspace==null?original:new PackageData(manifest,graph,history));
        } catch(UncheckedIOException e){throw e.getCause();} catch(RuntimeException e){throw new IOException("Invalid package record",e);} finally {try(var paths=Files.walk(workspacePath)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
    }
    public static Object encodeValue(String type,Object value){
        if(value==null)return null;
        return switch(type){
            case "Integer"->Long.toString(((Number)value).longValue());
            case "List"->{var m=ValueCodec.map(value);String item=(String)m.get("itemType");var items=new ArrayList<>();for(Object itemValue:(List<?>)m.get("items"))items.add(encodeValue(item,itemValue));yield Json.map("itemType",item,"items",items);}
            case "DDict"->{var out=new TreeMap<String,Object>();ValueCodec.map(value).forEach((key,raw)->{var row=ValueCodec.map(raw);out.put(key,Json.map("type",row.get("type"),"value",encodeValue((String)row.get("type"),row.get("value"))));});yield out;}
            default->value instanceof Map<?,?>?new TreeMap<>(ValueCodec.map(value)):value;
        };
    }
    private static Object validateValue(String type,Object value)throws IOException{return validateValue(type,value,false,0);}
    private static Object validateValue(String type,Object value,boolean fingerprints,int depth)throws IOException {
        if(depth>0&&Set.of("DGeometry","DResource").contains(type))throw new IOException("Geometry/resources cannot occur in collections");
        if(value==null)return null;
        try{return switch(type){
            case "String"->{if(!(value instanceof String||value instanceof PackageWorkspace.Text))throw new IllegalArgumentException();yield fingerprints?PackageWorkspace.fingerprint(value):PackageWorkspace.materializeText(value);}
            case "Integer"->{if(value instanceof PackageWorkspace.Text large&&large.length()>20)throw new IllegalArgumentException();Object text=PackageWorkspace.materializeText(value);if(!(text instanceof String s)||s.length()>20)throw new IllegalArgumentException();long result=Long.parseLong(s);if(!Long.toString(result).equals(s))throw new IllegalArgumentException();yield result;}
            case "List"->{var m=ValueCodec.map(value);if(!m.keySet().equals(Set.of("itemType","items"))||!(m.get("items") instanceof List<?> list))throw new IllegalArgumentException();String item=ValueCodec.type(m.get("itemType"));if(Set.of("DGeometry","DResource").contains(item))throw new IllegalArgumentException();
                for(Object x:list)validateValue(item,x,fingerprints,depth+1);
                var items=new AbstractList<Object>(){
                    public boolean equals(Object other){if(other==this)return true;if(!(other instanceof List<?> right)||right.size()!=size())return false;var left=iterator();var it=right.iterator();while(left.hasNext())if(!Objects.equals(left.next(),it.next()))return false;return true;}
                    public int size(){return list.size();}public Object get(int i){try{return validateValue(item,list.get(i),fingerprints,depth+1);}catch(IOException e){throw new UncheckedIOException(e);}}
                    public Iterator<Object> iterator(){var iterator=list.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Object next(){try{return validateValue(item,iterator.next(),fingerprints,depth+1);}catch(IOException e){throw new UncheckedIOException(e);}}};}
                };yield Json.map("itemType",item,"items",items);}
            case "DDict"->{var map=fingerprints?PackageWorkspace.dictionary(ValueCodec.map(value)):ValueCodec.map(value);for(var entry:map.entrySet()){var row=ValueCodec.map(entry.getValue());if(!row.keySet().equals(Set.of("type","value")))throw new IllegalArgumentException();validateValue(ValueCodec.type(row.get("type")),row.get("value"),fingerprints,depth+1);}
                yield new AbstractMap<String,Object>(){public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return map.size();}public Iterator<Entry<String,Object>> iterator(){var iterator=map.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Object> next(){var entry=iterator.next();var row=ValueCodec.map(entry.getValue());String t=ValueCodec.type(row.get("type"));try{return new SimpleImmutableEntry<>(entry.getKey(),Json.map("type",t,"value",validateValue(t,row.get("value"),fingerprints,depth+1)));}catch(IOException e){throw new UncheckedIOException(e);}}};}};}};}
            case "DGeometry"->{var map=new LinkedHashMap<>(ValueCodec.map(value));map.put("wkt",PackageWorkspace.materializeText(map.get("wkt")));yield ValueCodec.normalize(type,map);}
            default->ValueCodec.normalize(type,PackageWorkspace.materializeText(value));
        };}catch(RuntimeException e){throw new IOException("Invalid typed scientific value",e);}
    }
    private static boolean baselineEquals(Map<String,Object> baseline,Map<String,Object> first,PackageWorkspace workspace)throws IOException{
        if(!baseline.keySet().equals(first.keySet()))return false;
        for(String key:baseline.keySet())if(!key.equals("touches")&&!Objects.equals(baseline.get(key),first.get(key)))return false;
        if(!(baseline.get("touches") instanceof Map<?,?> left)||!(first.get("touches") instanceof Map<?,?> right))return false;
        int mark=workspace==null?0:workspace.mark();Map<String,Object> indexed=workspace==null?new HashMap<>():workspace.map();
        try{
            for(var entry:right.entrySet()){String key=PackageIdentity.qualified(entry.getKey());if(indexed.containsKey(key))throw new IOException("Duplicate canonical journal identity");indexed.put(key,entry.getValue());}
            for(var entry:left.entrySet()){String key=PackageIdentity.qualified(entry.getKey());if(!indexed.containsKey(key)||!Objects.equals(indexed.remove(key),entry.getValue()))return false;}
            return indexed.isEmpty();
        }finally{if(workspace!=null)workspace.release(mark);}
    }
    private static void validateHistory(Map<String,Object> history,Map<String,Object> graph,boolean wholeNamespace,PackageWorkspace workspace)throws IOException{
        if(!history.keySet().equals(Set.of("namespaceId","historyEpochId","baseline","revisions","complete")))throw new IOException("Unknown or missing history bundle fields");
        try{PackageIdentity.canonical((String)history.get("namespaceId"));PackageIdentity.canonical((String)history.get("historyEpochId"));}catch(RuntimeException e){throw new IOException("Invalid history provenance identity");}
        if(!Boolean.TRUE.equals(history.get("complete"))||!(history.get("revisions") instanceof List<?> revisions)||revisions.isEmpty()||!(history.get("baseline") instanceof Map<?,?> baseline))throw new IOException("History bundle is incomplete");
        if(!baselineEquals(ValueCodec.map(baseline),ValueCodec.map(revisions.getFirst()),workspace)||!"baseline".equals(ValueCodec.map(revisions.getFirst()).get("kind")))throw new IOException("History baseline does not match its first revision");
        Map<String,Map<String,Object>> rawState=workspace==null?new HashMap<>():workspace.map();
        Map<String,String> memberships=workspace==null?new HashMap<>():workspace.map(),declarations=workspace==null?new HashMap<>():workspace.map();
        var state=new WireState(rawState,memberships,declarations);
        Set<String> resourceIds=workspace==null?new HashSet<>():workspace.set(),revisionIds=workspace==null?new HashSet<>():workspace.set();
        for(Object raw:(List<?>)graph.get("resources"))resourceIds.add((String)ValueCodec.map(raw).get("id"));
        long previous=-1;String epoch=(String)history.get("historyEpochId"),parent=null;
        for(Object rawRevision:revisions){
            var revision=ValueCodec.map(rawRevision);
            if(!Set.of("id","revisionId","namespaceId","sequence","historyEpochId","historyMode","operationId","actorId","committedAt","kind","summary","parentRevisionId","changes","touches","dependencies","tag","epoch","reversesRevisionId","reappliesRevisionId","restoresRevisionId").containsAll(revision.keySet()))throw new IOException("Nonportable history metadata");
            if(!Objects.equals(history.get("namespaceId"),revision.get("namespaceId")))throw new IOException("History revision escaped source Namespace");
            if(!Objects.equals(epoch,revision.get("historyEpochId"))||!(revision.get("changes") instanceof List<?> rows)||!(revision.get("revisionId") instanceof String revisionId)||!revisionIds.add(revisionId))throw new IOException("History epoch or changes are invalid");
            try{PackageIdentity.canonical(revisionId);}catch(RuntimeException e){throw new IOException("Invalid revision UUID",e);}
            if(!Objects.equals(parent,revision.get("parentRevisionId")))throw new IOException("History parent chain is incomplete");
            parent=revisionId;metadataInteger(revision.get("sequence"));long sequence=((Number)revision.get("sequence")).longValue();if(sequence<=previous)throw new IOException("History revisions are out of order");previous=sequence;
            if(revision.get("epoch") instanceof Map<?,?> epochRecord&&!Set.of("id","mode","closed","baselineRevisionId").containsAll(epochRecord.keySet()))throw new IOException("Nonportable epoch metadata");
            int journalMark=workspace==null?0:workspace.mark();Set<String> journalIds=workspace==null?new HashSet<>():workspace.set();
            try{if(!(revision.get("touches") instanceof Map<?,?> touches)||!(revision.get("dependencies") instanceof List<?> dependencies))throw new IOException("Invalid history journal");for(Object identity:touches.keySet())if(!journalIds.add(PackageIdentity.qualified(identity)))throw new IOException("Duplicate canonical journal identity");for(Object identity:dependencies)PackageIdentity.qualified(identity);}finally{if(workspace!=null)workspace.release(journalMark);}
            Map<String,Map<String,Object>> changes=workspace==null?new HashMap<>():workspace.map();
            try{
                for(Object raw:rows){var row=ValueCodec.map(raw);String kind=(String)row.get("entityKind"),id=(String)row.get("entityId"),key=HistoryCodec.key(kind,PackageIdentity.canonical(id));
                    if(!HistoryCodec.KINDS.contains(kind)||!wholeNamespace&&"UserTool".equals(kind))throw new IOException("Invalid history image kind or scope");
                    if(changes.put(key,row)!=null)throw new IOException("Duplicate history change identity");
                    Map<String,Object> before=row.get("before") instanceof Map<?,?> b?ValueCodec.map(b):null,after=row.get("after") instanceof Map<?,?> a?ValueCodec.map(a):null;
                    if(before!=null&&!key.equals(HistoryCodec.key(before))||after!=null&&!key.equals(HistoryCodec.key(after)))throw new IOException("History image identity mismatch");
                    if(!Objects.equals(state.get(key),decodeHistoryImage(state,before)))throw new IOException("History before image differs from preceding state");
                }
                // All before-images were checked against one unchanged preceding state.
                for(var change:changes.entrySet()){Object after=change.getValue().get("after");if(after==null)rawState.remove(change.getKey());else rawState.put(change.getKey(),ValueCodec.map(after));}
                memberships.clear();declarations.clear();
                for(var image:rawState.values()){if("Membership".equals(image.get("kind"))){if(memberships.put((String)image.get("objectId"),(String)image.get("classId"))!=null)throw new IOException("Multiple historical memberships");}else if("ClassDescriptor".equals(image.get("kind"))){if(declarations.put(image.get("classId")+"\u0000"+image.get("name"),ValueCodec.type(image.get("type")))!=null)throw new IOException("Duplicate historical declaration");}}
                int mark=workspace==null?0:workspace.mark();try{HistoryCodec.validate(state,workspace,true);}finally{if(workspace!=null)workspace.release(mark);}
                for(var image:state.values()){
                    try{if(!PackageIdentity.canonical((String)image.get("id")).equalsIgnoreCase((String)image.get("id")))throw new IllegalArgumentException();}catch(RuntimeException bad){throw new IOException("Invalid historical UUID",bad);}
                    if(!Objects.equals(history.get("namespaceId"),image.get("namespaceId"))||"Namespace".equals(image.get("kind"))&&!Objects.equals(history.get("namespaceId"),image.get("id")))throw new IOException("Historical image escaped source Namespace");
                    if("Descriptor".equals(image.get("kind"))&&image.get("value")!=null&&"DResource".equals(state.descriptorType(image))&&!resourceIds.contains(ValueCodec.map(image.get("value")).get("resourceId")))throw new IOException("Historical resource is missing");
                }
            }finally{if(changes instanceof PackageWorkspace.DiskMap<?> disk)disk.dispose();}
        }
    }
    private static final class WireState extends AbstractMap<String,Map<String,Object>> implements HistoryCodec.DeclarationState {
        final Map<String,Map<String,Object>> raw;final Map<String,String> memberships,declarations;
        WireState(Map<String,Map<String,Object>> raw,Map<String,String> memberships,Map<String,String> declarations){this.raw=raw;this.memberships=memberships;this.declarations=declarations;}
        public String descriptorType(Map<String,Object> descriptor){String clazz=memberships.get(descriptor.get("objectId"));String type=clazz==null?null:declarations.get(clazz+"\u0000"+descriptor.get("name"));if(type==null)HistoryCodec.invalid("Missing historical declaration");return type;}
        @Override public Map<String,Object> get(Object key){var image=raw.get(key);try{return decodeHistoryImage(this,image);}catch(IOException e){throw new UncheckedIOException(e);}}
        @Override public Set<Entry<String,Map<String,Object>>> entrySet(){return new AbstractSet<>(){public int size(){return raw.size();}public Iterator<Entry<String,Map<String,Object>>> iterator(){var iterator=raw.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Map<String,Object>> next(){var row=iterator.next();return new SimpleImmutableEntry<>(row.getKey(),WireState.this.get(row.getKey()));}};}};}
    }
    private static Map<String,Object> decodeHistoryImage(Map<String,Map<String,Object>> state,Map<String,Object> image)throws IOException{if(image==null)return null;var out=new LinkedHashMap<>(image);if("Descriptor".equals(image.get("kind"))){String type=HistoryCodec.descriptorType(state,image);out.put("value",validateValue(type,image.get("value"),true,0));}return out;}
    private static void safePath(String name)throws IOException {
        if(name==null||name.isBlank()||name.startsWith("/")||name.startsWith("\\")||name.matches("^[A-Za-z]:.*")||name.indexOf('\\')>=0)throw new IOException("Unsafe package path");
        for(String part:name.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe package path");
    }
    private static void validateManifest(Map<String,Object> manifest,Map<String,String> actual,Map<String,Long> sizes)throws IOException {
        for(String field:List.of("packageId","sourceNamespaceId","name","description","depositVersion","exportedRevision","createdAt","rootIds","scope","files","includeHistory"))if(!manifest.containsKey(field))throw new IOException("Missing manifest field: "+field);
        if(!Set.of("packageId","sourceNamespaceId","name","description","depositVersion","exportedRevision","createdAt","rootIds","scope","files","includeHistory","maxHops","rootClassBoundary","boundaryReferences","omittedEdgeCount").containsAll(manifest.keySet()))throw new IOException("Unknown manifest field");
        try{PackageIdentity.canonical((String)manifest.get("packageId"));PackageIdentity.canonical((String)manifest.get("sourceNamespaceId"));}catch(RuntimeException e){throw new IOException("Invalid manifest identity");}
        for(String field:List.of("name","description","depositVersion","createdAt"))if(!(manifest.get(field) instanceof String))throw new IOException("Invalid manifest text");
        if(!(manifest.get("includeHistory") instanceof Boolean))throw new IOException("Invalid history flag");
        metadataInteger(manifest.get("exportedRevision"));
        try{java.time.ZonedDateTime.parse((String)manifest.get("createdAt"));}catch(RuntimeException e){throw new IOException("Invalid export timestamp",e);}
        if(!(manifest.get("rootIds") instanceof List<?> roots))throw new IOException("Invalid root IDs");for(Object id:roots)try{PackageIdentity.canonical((String)id);}catch(RuntimeException e){throw new IOException("Invalid root UUID");}
        if(!Set.of("namespace","subgraph").contains(manifest.get("scope")))throw new IOException("Invalid package scope");
        if("namespace".equals(manifest.get("scope"))&&!roots.isEmpty()||"subgraph".equals(manifest.get("scope"))&&roots.isEmpty())throw new IOException("Package roots differ from its scope");
        if(manifest.containsKey("maxHops"))metadataInteger(manifest.get("maxHops"));
        if(manifest.containsKey("maxHops")&&(!(manifest.get("maxHops") instanceof Number hops)||hops.longValue()<0||hops.longValue()>10))throw new IOException("Invalid traversal limit");
        if(manifest.containsKey("rootClassBoundary")&&!(manifest.get("rootClassBoundary") instanceof Boolean))throw new IOException("Invalid root boundary setting");
        if(manifest.containsKey("omittedEdgeCount"))metadataInteger(manifest.get("omittedEdgeCount"));
        if(manifest.containsKey("omittedEdgeCount")&&(!(manifest.get("omittedEdgeCount") instanceof Number omitted)||omitted.longValue()<0))throw new IOException("Invalid omitted edge count");
        if(manifest.containsKey("boundaryReferences")){if(!(manifest.get("boundaryReferences") instanceof List<?> boundaries))throw new IOException("Invalid boundary references");for(Object raw:boundaries){var row=ValueCodec.map(raw);if(!row.keySet().equals(Set.of("kind","id","sourceNamespaceId"))||!Set.of("Object","Class","Pattern","Descriptor","Relationship","ClassRelationship").contains(row.get("kind"))||!Objects.equals(row.get("sourceNamespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("Invalid boundary reference");try{PackageIdentity.canonical((String)row.get("id"));}catch(RuntimeException e){throw new IOException("Invalid boundary reference UUID");}}}
        Object files=manifest.get("files");if(!(files instanceof Map<?,?> fm))throw new IOException("Invalid manifest files");
        var expected=new HashSet<String>();
        for(var item:fm.entrySet()) {
            if(!(item.getKey() instanceof String path)||!(item.getValue() instanceof Map<?,?> info))throw new IOException("Invalid file manifest row");
            safePath(path);if(path.equals("manifest.json"))throw new IOException("Manifest cannot hash itself");expected.add(path);
            metadataInteger(info.get("bytes"));if(!(info.get("bytes") instanceof Number bytes)||bytes.longValue()<0||!Objects.equals(bytes.longValue(),sizes.get(path)))throw new IOException("Invalid file length");
            Object digest=info.get("sha256");if(!(digest instanceof String s)||!s.matches("[a-f0-9]{64}")||!s.equals(actual.get(path)))throw new IOException("Package file checksum mismatch");
        }
        var actualFiles=new HashSet<>(actual.keySet());actualFiles.remove("manifest.json");
        if(!actualFiles.equals(expected))throw new IOException("Manifest file list does not match archive");
    }
    private static void validateGraph(Map<String,Object> graph,Set<String> paths,Map<String,Object> manifest,PackageWorkspace workspace)throws IOException {
        if(!graph.keySet().equals(Set.of("classes","objects","descriptors","geometries","memberships","relationships","classRelationships","patterns","userTools","resources")))throw new IOException("Unknown or missing graph categories");
        for(String kind:List.of("classes","objects","descriptors","geometries","memberships","relationships","classRelationships","patterns","userTools","resources"))if(!(graph.get(kind) instanceof List<?>))throw new IOException("Missing graph list: "+kind);
        if("subgraph".equals(manifest.get("scope"))&&!((List<?>)graph.get("userTools")).isEmpty())throw new IOException("Subgraph packages cannot contain User Tools");
        var ids=new HashMap<String,Map<String,Map<String,Object>>>();var expected=Map.of("classes","Class","objects","Object","descriptors","Descriptor","geometries","DGeometry","memberships","Membership","relationships","Relationship","classRelationships","ClassRelationship","patterns","Pattern","userTools","UserTool","resources","DResource");
        for(var list:expected.entrySet()){Map<String,Map<String,Object>> category=workspace==null?new HashMap<>():workspace.map();for(Object raw:(List<?>)graph.get(list.getKey())){if(!(raw instanceof Map<?,?> m)||!list.getValue().equals(m.get("kind"))||!(m.get("id") instanceof String id))throw new IOException("Invalid category record: "+list.getKey());validateRecordFields(list.getValue(),m);validatePortableShape(list.getValue(),m);try{if(!PackageIdentity.canonical(id).equalsIgnoreCase(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IOException("Invalid package UUID");}if(category.put(id,(Map<String,Object>)m)!=null)throw new IOException("Duplicate identity within category");}ids.put(list.getValue(),category);}
        Map<String,Map<String,Object>> classes=ids.get("Class"),objects=ids.get("Object"),descriptors=ids.get("Descriptor"),geometries=ids.get("DGeometry"),resources=ids.get("DResource");
        for(var category:ids.values())for(var row:category.values())if(row.containsKey("namespaceId")&&!Objects.equals(row.get("namespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("Entity escaped source Namespace");
        if("subgraph".equals(manifest.get("scope"))){if(((List<?>)manifest.get("rootIds")).isEmpty())throw new IOException("Subgraph package has no roots");for(Object root:(List<?>)manifest.get("rootIds"))if(!objects.containsKey(root))throw new IOException("Subgraph root is absent");}
        Map<String,String> memberships=workspace==null?new HashMap<>():workspace.map();for(Object raw:(List<?>)graph.get("memberships")){var m=(Map<String,Object>)raw;String oid=(String)m.get("objectId"),cid=(String)m.get("classId");if(!objects.containsKey(oid)||!classes.containsKey(cid)||memberships.put(oid,cid)!=null)throw new IOException("Invalid membership reference");}
        for(var object:objects.values())if(!memberships.containsKey(object.get("id"))||!Objects.equals(object.get("classId"),memberships.get(object.get("id"))))throw new IOException("Object and membership Class differ");
        Map<String,String> declaredTypes=workspace==null?new HashMap<>():workspace.map();
        Set<String> declarationIds=workspace==null?new HashSet<>():workspace.set();Set<String> declarationNames=workspace==null?new HashSet<>():workspace.set();for(var clazz:classes.values()){declarationNames.clear();if(!(clazz.get("name") instanceof String)||!(clazz.get("descriptors") instanceof List<?> declarations))throw new IOException("Class declaration list is missing");Set<String> names=declarationNames;for(Object raw:declarations){var declaration=ValueCodec.map(raw);validateRecordFields("ClassDescriptor",declaration);validatePortableShape("ClassDescriptor",declaration);if(!"ClassDescriptor".equals(declaration.get("kind"))||!Objects.equals(declaration.get("classId"),clazz.get("id"))||!Objects.equals(declaration.get("namespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("Invalid Class declaration identity/owner");try{String id=(String)declaration.get("id");if(!PackageIdentity.canonical(id).equalsIgnoreCase(id)||!declarationIds.add(id))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("Invalid or duplicate declaration UUID",e);}if(!(declaration.get("name") instanceof String name)||!names.add(name))throw new IOException("Duplicate or invalid Class declaration");ValueCodec.type(declaration.get("type"));declaredTypes.put(clazz.get("id")+"\u0000"+name,(String)declaration.get("type"));}}
        Set<String> descriptorNames=workspace==null?new HashSet<>():workspace.set();
        Set<String> descriptorGeometry=workspace==null?new HashSet<>():workspace.set();for(var d:descriptors.values()){if(!d.containsKey("value"))throw new IOException("Descriptor value is absent");if(!objects.containsKey(d.get("objectId")))throw new IOException("Descriptor owner is missing");Object value=d.get("value");if(!descriptorNames.add(d.get("objectId")+"\u0000"+d.get("name")))throw new IOException("Duplicate Descriptor field");String classId=memberships.get(d.get("objectId"));String declaredType=declaredTypes.get(classId+"\u0000"+d.get("name"));if("DResource".equals(declaredType)&&value!=null&&!resources.containsKey(ValueCodec.map(value).get("resourceId")))throw new IOException("Descriptor resource is missing");if(declaredType==null)throw new IOException("Descriptor declaration is missing");validateValue(declaredType,value,workspace!=null,0);if("DGeometry".equals(declaredType)&&value!=null&&!(d.get("geometryId") instanceof String))throw new IOException("Geometry identity is missing");if((!"DGeometry".equals(declaredType)||value==null)&&d.get("geometryId")!=null)throw new IOException("Unexpected geometry identity");if(d.get("geometryId") instanceof String geometryId){Map<String,Object> g=geometries.get(geometryId);if(g==null||!Objects.equals(g.get("descriptorId"),d.get("id"))||!descriptorGeometry.add(geometryId))throw new IOException("Invalid geometry reference");if(!Objects.equals(value,Json.map("wkt",g.get("wkt"),"srid",g.get("srid"),"verticalSrid",g.get("verticalSrid"),"axisOrder",g.get("axisOrder"),"dimensions",g.get("dimensions"))))throw new IOException("Geometry descriptor value differs from its record");}}
        for(var g:geometries.values()){if(!Objects.equals(g.get("geometryType"),GeometryCodec.fromWkt(g.get("wkt"),g.get("srid"),g.get("verticalSrid")).get("geometryType")))throw new IOException("Geometry type differs from WKT");if(!descriptors.containsKey(g.get("descriptorId"))||!Objects.equals(descriptors.get(g.get("descriptorId")).get("geometryId"),g.get("id")))throw new IOException("Orphan geometry record");}
        Set<String> edgeKeys=workspace==null?new HashSet<>():workspace.set();
        for(String edgeList:List.of("relationships","classRelationships")){boolean objectEdge=edgeList.equals("relationships");Map<String,Map<String,Object>> endpoints=objectEdge?objects:classes;for(Object raw:(List<?>)graph.get(edgeList)){var edge=(Map<String,Object>)raw;if(!edgeKeys.add(edgeList+"\u0000"+edge.get("sourceId")+"\u0000"+edge.get("targetId")+"\u0000"+edge.get("label")))throw new IOException("Duplicate domain relationship");if(!endpoints.containsKey(edge.get("sourceId"))||!endpoints.containsKey(edge.get("targetId"))||!(edge.get("label") instanceof String))throw new IOException("Invalid relationship endpoint");}}
        Set<String> classEdgeKeys=workspace==null?new HashSet<>():workspace.set();for(Object raw:(List<?>)graph.get("classRelationships")){var edge=(Map<String,Object>)raw;classEdgeKeys.add(edge.get("sourceId")+"\u0000"+edge.get("targetId")+"\u0000"+edge.get("label"));}
        for(Object raw:(List<?>)graph.get("relationships")){var edge=(Map<String,Object>)raw;String sourceClass=memberships.get(edge.get("sourceId")),targetClass=memberships.get(edge.get("targetId"));if(!classEdgeKeys.contains(sourceClass+"\u0000"+targetClass+"\u0000"+edge.get("label")))throw new IOException("Object relationship has no Class relationship");}
        for(Object raw:(List<?>)graph.get("resources")) {
            if(!(raw instanceof Map<?,?> row)||!(row.get("sha256") instanceof String hash)||!(row.get("entry") instanceof String entry))throw new IOException("Invalid resource record");
            if(!entry.equals("resources/"+hash)||!paths.contains(entry)||!hash.equals(entry.substring(10)))throw new IOException("Missing referenced resource");
            Map<String,Object> files=ValueCodec.map(manifest.get("files"));Map<String,Object> info=ValueCodec.map(files.get(entry));if(!Objects.equals(row.get("bytes"),info.get("bytes")))throw new IOException("Resource length differs from its content entry");
        }
        Set<String> entityNames=workspace==null?new HashSet<>():workspace.set();for(String kind:List.of("Pattern","UserTool"))for(var row:ids.get(kind).values())if(!entityNames.add(kind+"\u0000"+row.get("name")))throw new IOException("Duplicate named entity");
        validatePatternRows((List<?>)graph.get("patterns"),classes,workspace);
    }
    private static void validatePortableShape(String kind,Map<?,?> row)throws IOException{
        if(!kind.equals("DResource")&&!(row.get("namespaceId") instanceof String))throw new IOException("Missing source Namespace");
        for(String field:List.of("name","label")){
            boolean required=field.equals("name")?Set.of("Class","ClassDescriptor","Descriptor","Pattern","UserTool").contains(kind):Set.of("Relationship","ClassRelationship").contains(kind);
            if(required||row.containsKey(field)){Object raw=row.get(field);if(!(raw instanceof String name)||name.isBlank()||name.codePointCount(0,name.length())>255||name.codePoints().anyMatch(Character::isISOControl)||!java.text.Normalizer.isNormalized(name,java.text.Normalizer.Form.NFC))throw new IOException("Invalid portable name/label");}
        }
        if(kind.equals("UserTool")&&!(row.get("data") instanceof String))throw new IOException("Missing opaque User Tool data");
        if(row.containsKey("weight"))try{ValueCodec.normalize("Float",row.get("weight"));}catch(RuntimeException e){throw new IOException("Invalid relationship weight",e);}
    }
    private static void validateRecordFields(String kind,Map<?,?> row)throws IOException{
        Set<String> allowed=switch(kind){case "Class"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","position","order","comment","descriptors");case "ClassDescriptor"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","classId","name","type","order","comment");case "Object"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","classId","position");case "Descriptor"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","objectId","value","geometryId");case "DGeometry"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","descriptorId","geometryType","wkt","srid","verticalSrid","axisOrder","dimensions");case "Membership"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","objectId","classId");case "Relationship"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","sourceId","targetId","label","weight");case "ClassRelationship"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","sourceId","targetId","label","comment");case "Pattern"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","comment","definition");case "UserTool"->Set.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","comment","data");case "DResource"->Set.of("kind","id","filename","bytes","mediaType","sha256","entry");default->Set.of();};if(!allowed.containsAll(row.keySet()))throw new IOException("Nonportable fields in "+kind+" record");
    }
    private static void validatePatternRows(List<?> patterns,Map<String,Map<String,Object>> classes,PackageWorkspace workspace)throws IOException{
        try{
            Map<String,String> classNames=workspace==null?new HashMap<>():workspace.map(),declarations=workspace==null?new HashMap<>():workspace.map();
            for(var clazz:classes.values()){
                if(classNames.put((String)clazz.get("name"),(String)clazz.get("id"))!=null)throw new IOException("Duplicate Class name");
                for(Object raw:(List<?>)clazz.getOrDefault("descriptors",List.of())){var declaration=ValueCodec.map(raw);declarations.put(clazz.get("id")+"\u0000"+declaration.get("name"),ValueCodec.type(declaration.get("type")));}
            }
            Map<String,Map<String,String>> schema=new AbstractMap<>(){
                public Map<String,String> get(Object name){String id=classNames.get(name);if(id==null)return null;return new AbstractMap<>(){
                    public String get(Object field){return declarations.get(id+"\u0000"+field);}
                    public boolean containsKey(Object field){return get(field)!=null;}
                    public Set<Entry<String,String>> entrySet(){throw new UnsupportedOperationException("Lookup-only declaration view");}
                };}
                public Set<Entry<String,Map<String,String>>> entrySet(){throw new UnsupportedOperationException("Lookup-only schema view");}
            };
            Collection<Map<String,Object>> definitions=new AbstractCollection<>(){
                public int size(){return patterns.size();}
                public Iterator<Map<String,Object>> iterator(){return patterns.stream().map(raw->ValueCodec.map(ValueCodec.map(raw).get("definition"))).iterator();}
            };
            PatternCodec.validate(definitions,schema,workspace);
        }catch(IOException failure){throw failure;}catch(RuntimeException failure){throw new IOException("Invalid Pattern definition or compatibility",failure);}
    }
    private static void metadataInteger(Object value)throws IOException{if(!(value instanceof Long||value instanceof Integer)||((Number)value).longValue()<0||((Number)value).longValue()>9_007_199_254_740_991L)throw new IOException("Metadata counter is not a portable integer");}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
