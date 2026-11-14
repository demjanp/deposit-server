package deposit.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Portable Deposit exchange ZIP writer and bounded, independent archive validator. */
public final class ExchangePackageCodec {
    public record ValidationSummary(String packageId,String sourceNamespaceId,String scope,long exportedRevision,boolean includeHistory,Map<String,Long> counts) {}
    public static ValidationSummary validateSummary(Path archive)throws IOException{return validateSummary(archive,()->{});}
    public static ValidationSummary validateSummary(Path archive,Runnable checkpoint)throws IOException{return validate(archive,true,checkpoint,pkg->{var counts=new TreeMap<String,Long>();pkg.graph().forEach((key,value)->{if(value instanceof List<?> rows)counts.put(key,(long)rows.size());});return new ValidationSummary((String)pkg.manifest().get("packageId"),(String)pkg.manifest().get("sourceNamespaceId"),(String)pkg.manifest().get("scope"),((Number)pkg.manifest().get("exportedRevision")).longValue(),pkg.history()!=null,Collections.unmodifiableMap(counts));});}
    public record PackageData(Map<String,Object> manifest, Map<String,Object> graph, Map<String,Object> history) {}
    private ExchangePackageCodec() {}

    public static void write(Path destination,Map<String,Object> manifest,Map<String,Object> graph,
                             Map<String,Object> history,Map<String,Path> resources)throws IOException {
        PackageArchiveIO.write(destination,manifest,graph,history,resources);
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
        try(var context=new PackageValidationContext(streaming,checkpoint)) {
            PackageData payload;
            try {
                var files=PackageArchiveIO.read(archive,context);
                var workspace=context.workspace;
                Map<String,Object> manifest=context.parse(files.jsonFiles().get("manifest.json"));
                Map<String,Object> graph=context.parse(files.jsonFiles().get("graph.json"));
                Map<String,Object> history=files.jsonFiles().containsKey("history.json")?context.parse(files.jsonFiles().get("history.json")):null;
                var original=new PackageData(manifest,graph,history);
                if(workspace==null){manifest=PackageIdentity.view(manifest);graph=PackageIdentity.view(graph);if(history!=null)history=PackageIdentity.view(history);}
                PackageArchiveIO.validateManifest(manifest,files.hashes(),files.sizes());
                PackageGraphValidator.validate(graph,files.paths(),manifest,context);
                if((history!=null)!=Boolean.TRUE.equals(manifest.get("includeHistory"))&&manifest.containsKey("includeHistory"))throw new IOException("History manifest mismatch");
                if(history!=null){if(!Objects.equals(history.get("namespaceId"),manifest.get("sourceNamespaceId")))throw new IOException("History provenance Namespace differs");PackageHistoryValidator.validate(history,graph,"namespace".equals(manifest.get("scope")),context);}
                payload=workspace==null?original:new PackageData(workspace.payload(manifest),workspace.payload(graph),history==null?null:workspace.payload(history));
            } catch(IOException|RuntimeException failure) {
                context.rethrowCheckpointFailure();
                if(failure instanceof UncheckedIOException unchecked)throw unchecked.getCause();
                if(failure instanceof IOException io)throw io;
                throw new IOException("Invalid package record",failure);
            }
            // Application exceptions bypass malformed-input conversion; the context remains open.
            try {return finish.apply(payload);}
            catch(RuntimeException failure){context.rethrowCheckpointFailure();throw failure;}
        }
    }
    public static Object encodeValue(String type,Object value){return PackageValueCodec.encodeValue(type,value);}
    /** Decode declaration-directed wire forms for repository writes. */
    public static Object decodeValue(String type,Object value){return PackageValueCodec.decodeValue(type,value);}
    /** Decode typed history values into canonical repository form while retaining lazy collection views. */
    public static Object decodeHistoryValue(String type,Object value)throws IOException{return PackageValueCodec.validateValue(type,value);}
}
