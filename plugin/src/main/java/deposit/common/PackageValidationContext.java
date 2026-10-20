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
    private RuntimeException checkpointFailure;
    private InterruptedIOException interruption;

    PackageValidationContext(boolean streaming,Runnable checkpoint)throws IOException {
        this.checkpoint=checkpoint;
        checkpoint();
        directory=Files.createTempDirectory("deposit-package-validation-");
        try {workspace=streaming?new PackageWorkspace(directory,this::workspaceCheckpoint):null;}
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
