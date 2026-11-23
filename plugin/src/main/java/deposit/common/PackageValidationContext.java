package deposit.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Owns staged archive JSON and all semantic spill indexes until callback completion. */
final class PackageValidationContext implements AutoCloseable {
    final Path directory;
    final PackageWorkspace workspace;
    private final Runnable checkpoint;
    final ExportRecords.StorageBudget budget;
    private RuntimeException checkpointFailure;
    private InterruptedIOException interruption;

    PackageValidationContext(boolean streaming,Runnable checkpoint)throws IOException {
        this(streaming,checkpoint,null,null);
    }

    PackageValidationContext(boolean streaming,Runnable checkpoint,Path parent,ExportRecords.StorageBudget budget)throws IOException {
        this.checkpoint=checkpoint;
        this.budget=budget;
        checkpoint();
        directory=parent==null?Files.createTempDirectory("deposit-package-validation-"):Files.createTempDirectory(parent,"validation-");
        try {workspace=streaming?new PackageWorkspace(directory,this::workspaceCheckpoint,budget):null;}
        catch(IOException|RuntimeException failure){
            try {removeDirectory();}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    /** Preserve the caller's signal even when a nested validator wraps runtime failures. */
    private void runCheckpoint() {
        try {checkpoint.run();}
        catch(RuntimeException failure){checkpointFailure=failure;throw failure;}
    }
    private void workspaceCheckpoint() {
        runCheckpoint();
        if(Thread.currentThread().isInterrupted()){
            interruption=new InterruptedIOException("Package validation cancelled");
            throw new UncheckedIOException(interruption);
        }
    }
    void checkpoint()throws InterruptedIOException {
        runCheckpoint();
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Package validation cancelled");
    }
    void rethrowCheckpointFailure()throws InterruptedIOException {
        if(checkpointFailure!=null)throw checkpointFailure;
        if(interruption!=null)throw interruption;
    }
    OutputStream output(Path path)throws IOException {
        var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW);
        if(budget==null)return output;
        return accounted(output,budget,this::workspaceCheckpoint);
    }
    static OutputStream accounted(OutputStream output,ExportRecords.StorageBudget budget,Runnable checkpoint) {
        if(budget==null)return output;
        return new FilterOutputStream(output) {
            @Override public void write(int value)throws IOException {write(new byte[]{(byte)value},0,1);}
            @Override public void write(byte[] bytes,int offset,int count)throws IOException {
                checkpoint.run();
                budget.reserve(count);
                out.write(bytes,offset,count);
                budget.materialized(count);
            }
        };
    }

    Map<String,Object> parse(Path file)throws IOException {
        return workspace==null?Json.read(Files.readString(file,StandardCharsets.UTF_8)):workspace.parse(file);
    }
    private void removeDirectory()throws IOException {
        try(var paths=Files.walk(directory)){
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
    @Override public void close()throws IOException {
        IOException failure=null;
        try {if(workspace!=null)workspace.close();}catch(IOException close){failure=close;}
        try {removeDirectory();}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if(failure!=null)throw failure;
    }
}
