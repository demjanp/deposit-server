package deposit.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Portable Deposit exchange ZIP writer and bounded, independent archive validator. */
public final class ExchangePackageCodec {
    private static final java.util.concurrent.atomic.AtomicLong STAGE7_VALIDATION_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    public record ValidationSummary(String packageId,String sourceNamespaceId,String scope,long exportedRevision,boolean includeHistory,Map<String,Long> counts) {}
    public static ValidationSummary validateSummary(Path archive)throws IOException{return validateSummary(archive,()->{});}
    public static ValidationSummary validateSummary(Path archive,Runnable checkpoint)throws IOException{return validate(archive,true,checkpoint,pkg->{var counts=new TreeMap<String,Long>();pkg.graph().forEach((key,value)->{if(value instanceof List<?> rows)counts.put(key,(long)rows.size());});return new ValidationSummary((String)pkg.manifest().get("packageId"),(String)pkg.manifest().get("sourceNamespaceId"),(String)pkg.manifest().get("scope"),((Number)pkg.manifest().get("exportedRevision")).longValue(),pkg.history()!=null,Collections.unmodifiableMap(counts));});}
    public record PackageData(Map<String,Object> manifest, Map<String,Object> graph, Map<String,Object> history) {}
    private ExchangePackageCodec() {}

    public static void write(Path destination,Map<String,Object> manifest,Map<String,Object> graph,
                             Map<String,Object> history,Map<String,Path> resources)throws IOException {
        write(destination,manifest,graph,history,resources,null,()->{});
    }

    public static void write(Path destination,Map<String,Object> manifest,Map<String,Object> graph,Map<String,Object> history,
            Map<String,Path> resources,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        PackageArchiveIO.write(destination,manifest,graph,history,resources,budget,checkpoint);
    }

    public record StagedMember(String name,Path path,long bytes,String sha256) {
        public void verify(Runnable checkpoint)throws IOException {
            if(Files.size(path)!=bytes || !ResourceFiles.checksum(path,checkpoint).equals(sha256))
                throw new IOException("Staged member content differs");
        }
    }

    public static void writeSnapshot(Path destination,StagedMember manifest,StagedMember graph,StagedMember history,
            Iterable<StagedMember> resources,boolean includeHistory,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        if(includeHistory!=(history!=null))throw new IOException("Structured snapshot history differs from metadata");
        manifest.verify(checkpoint);
        graph.verify(checkpoint);
        if(history!=null)history.verify(checkpoint);
        Path scratch=Files.createTempDirectory(destination.getParent(),"snapshot-write-");
        try(var input=new PackageWorkspace(scratch,checkpoint,budget)) {
            var data=new LinkedHashMap<String,Object>();
            var allowed=Set.of("sourceNamespaceId","name","description","historyMode","exportedRevision","createdAt","rootIds","scope",
                    "maxHops","rootClassBoundary","boundaryReferences","omittedEdgeCount","includeHistory");
            for(var entry:PackageWorkspace.rawEntries(input.parse(manifest.path()))) {
                if(!(entry.getKey() instanceof String key)||!allowed.contains(key))
                    throw new IOException("Unknown or Server-derived structured manifest field");
                data.put(key,entry.getValue());
            }
            if(data.containsKey("includeHistory")&&!Boolean.valueOf(includeHistory).equals(data.get("includeHistory")))
                throw new IOException("Structured manifest history differs from metadata");
            Object boundaries=data.get("boundaryReferences");
            if(boundaries instanceof List<?> rows)data.put("boundaryReferences",new AbstractList<>() {
                public int size(){return rows.size();}
                public Object get(int index){return rawBoundary(rows.get(index));}
                public Iterator<Object> iterator(){return rows.stream().map(ExchangePackageCodec::rawBoundary).iterator();}
            });
            data.put("packageId",UUID.randomUUID().toString());
            data.put("depositVersion",DepositInfo.version());
            PackageArchiveIO.write(destination,data,graph.path(),history==null?null:history.path(),resources,scratch,budget,checkpoint);
        } finally { PackageArchiveIO.deleteScratch(scratch); }
    }

    private static Object rawBoundary(Object row) {
        if(!(row instanceof Map<?,?> fields))return row;
        var raw=new LinkedHashMap<String,Object>();
        for(var entry:PackageWorkspace.rawEntries(fields)) {
            if(!(entry.getKey() instanceof String key)||!Set.of("kind","id","sourceNamespaceId").contains(key))
                throw new UncheckedIOException(new IOException("Unexpected boundary field"));
            raw.put(key,entry.getValue());
        }
        return raw;
    }

    /** Returns detached payload maps, preserving their original text and identity spelling. */
    public static PackageData validate(Path archive)throws IOException {
        return validate(archive,false,()->{},java.util.function.Function.identity());
    }
    /** The package view borrows the validation context and is valid only during the callback. */
    public static <T> T withValidatedPackage(Path archive,Runnable checkpoint,java.util.function.Function<PackageData,T> action)throws IOException {
        Objects.requireNonNull(action,"action");
        return validate(archive,true,checkpoint==null?()->{}:checkpoint,action);
    }
    private static <T> T validate(Path archive,boolean streaming,Runnable checkpoint,java.util.function.Function<PackageData,T> finish)throws IOException {
        return validate(archive,streaming,checkpoint,null,null,null,false,finish);
    }

    static <T> T index(Path archive,Path resources,ExportRecords.StorageBudget budget,Runnable checkpoint,
            java.util.function.Function<PackageData,T> action)throws IOException {
        return validate(archive,true,checkpoint,archive.getParent(),resources,budget,true,action);
    }

    public static <T> T withValidatedPackage(Path archive,Path temporary,ExportRecords.StorageBudget budget,
            Runnable checkpoint,java.util.function.Function<PackageData,T> action)throws IOException {
        return validate(archive,true,checkpoint,temporary,null,budget,false,action);
    }

    private static <T> T validate(Path archive,boolean streaming,Runnable checkpoint,Path temporary,Path resources,
            ExportRecords.StorageBudget budget,boolean preserveText,java.util.function.Function<PackageData,T> finish)throws IOException {
        boolean stage7Timing=stage7ValidationTimingEnabled();
        long validationCall=stage7Timing?STAGE7_VALIDATION_SEQUENCE.incrementAndGet():0L;
        String validationLabel=stage7Timing?System.getProperty("deposit.stage7.validation.timing.label","unlabeled"):null;
        long validationStarted=stage7Timing?System.nanoTime():0L;
        try(var context=new PackageValidationContext(streaming,checkpoint,temporary,budget)) {
            PackageData payload;
            try {
                long phaseStarted=stage7Timing?System.nanoTime():0L;
                var files=PackageArchiveIO.read(archive,context,resources);
                if(stage7Timing)recordStage7ValidationTiming(validationLabel,validationCall,"archive-reading",phaseStarted);
                var workspace=context.workspace;
                phaseStarted=stage7Timing?System.nanoTime():0L;
                Map<String,Object> manifest=context.parse(files.jsonFiles().get("manifest.json"));
                Map<String,Object> graph=context.parse(files.jsonFiles().get("graph.json"));
                Map<String,Object> history=files.jsonFiles().containsKey("history.json")?context.parse(files.jsonFiles().get("history.json")):null;
                var original=new PackageData(manifest,graph,history);
                if(workspace==null){manifest=PackageIdentity.view(manifest);graph=PackageIdentity.view(graph);if(history!=null)history=PackageIdentity.view(history);}
                if(stage7Timing)recordStage7ValidationTiming(validationLabel,validationCall,"json-parsing",phaseStarted);
                phaseStarted=stage7Timing?System.nanoTime():0L;
                PackageArchiveIO.validateManifest(manifest,files.hashes(),files.sizes());
                PackageGraphValidator.validate(graph,files.paths(),manifest,context);
                if((history!=null)!=Boolean.TRUE.equals(manifest.get("includeHistory"))&&manifest.containsKey("includeHistory"))throw new IOException("History manifest mismatch");
                if(history!=null){if(!Objects.equals(history.get("namespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("History provenance Namespace differs");PackageHistoryValidator.validate(history,graph,"namespace".equals(manifest.get("scope")),context);}
                payload=workspace==null?original:preserveText?new PackageData(manifest,graph,history):new PackageData(workspace.payload(manifest),workspace.payload(graph),history==null?null:workspace.payload(history));
                if(stage7Timing){recordStage7ValidationTiming(validationLabel,validationCall,"semantic-validation",phaseStarted);recordStage7ValidationTiming(validationLabel,validationCall,"validation-total",validationStarted);}
            } catch(IOException|RuntimeException failure) {
                context.rethrowCheckpointFailure();
                if(failure instanceof StorageAdmission.StoragePressureException pressure)throw pressure;
                if(failure instanceof UncheckedIOException unchecked)throw unchecked.getCause();
                if(failure instanceof IOException io)throw io;
                throw new IOException("Invalid package record",failure);
            }
            // Application exceptions bypass malformed-input conversion; the context remains open.
            try {return finish.apply(payload);}
            catch(RuntimeException failure){context.rethrowCheckpointFailure();throw failure;}
        }
    }
    private static boolean stage7ValidationTimingEnabled(){String path=System.getProperty("deposit.stage7.timing.progress");return Boolean.getBoolean("deposit.stage7.validation.timing.enabled")&&path!=null&&!path.isBlank();}
    private static void recordStage7ValidationTiming(String label,long call,String phase,long started){
        String path=System.getProperty("deposit.stage7.timing.progress");if(path==null||path.isBlank())return;
        String safeLabel=(label==null||label.isBlank()?"unlabeled":label).replaceAll("[^A-Za-z0-9._-]","_");
        String marker="package-validation label="+safeLabel+" call="+call+" phase="+phase+" elapsedMs="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        try{Files.writeString(Path.of(path),marker+System.lineSeparator(),java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    public static Object encodeValue(String type,Object value){return PackageValueCodec.encodeValue(type,value);}
    /** Decode declaration-directed wire forms for repository writes. */
    public static Object decodeValue(String type,Object value){return PackageValueCodec.decodeValue(type,value);}
    /** Decode typed history values into canonical repository form while retaining lazy collection views. */
    public static Object decodeHistoryValue(String type,Object value)throws IOException{return PackageValueCodec.validateValue(type,value);}
}
