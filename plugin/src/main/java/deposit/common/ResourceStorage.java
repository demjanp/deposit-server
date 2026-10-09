package deposit.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Completed immutable content only. Deposit owns identity, authority and retention. */
public interface ResourceStorage extends AutoCloseable {
    record Content(String resourceId, String storedFilename, long bytes, String checksum) {}
    /** A guarded staging input is copied; publication never consumes the only recovery source. */
    void publish(Content content, Path stagedFile) throws IOException;
    void verify(Content content) throws IOException;
    InputStream open(Content content, long offset, long length) throws IOException;
    boolean exists(String resourceId) throws IOException;
    /** Discovery does not create application identity or confer access. */
    List<Content> discover() throws IOException;
    long physicalBytes(String resourceId) throws IOException;
    /** Idempotent complete-object deletion, called only after Deposit authorizes pruning. */
    void purge(String resourceId) throws IOException;
    void recoverWork() throws IOException;
    @Override void close();
}
