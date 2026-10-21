package deposit.common;

import deposit.repository.CoreRepository;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Storage lifecycle and maintenance scheduling/recovery coordinator. */
public final class StorageManager implements AutoCloseable {
    public final ResourceFiles files = new ResourceFiles();
    public final ResourceStorage content;
    private final ServerContext server;
    private final GraphDatabaseAPI db;
    private final StorageMaintenanceJobs jobs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deposit-resource-maintenance"); t.setDaemon(true); return t;
    });
    public volatile String diagnostic;

    public StorageManager(ServerContext server, GraphDatabaseAPI db) {
        this.server = server;
        this.db = db;
        for (Path directory : List.of(db.databaseLayout().databaseDirectory(), db.databaseLayout().getTransactionLogsDirectory())) {
            Path path = directory.toAbsolutePath().normalize();
            for (Path root : List.of(files.root, files.exports))
                if (root.startsWith(path) || path.startsWith(root)) throw new IllegalStateException("Storage overlaps database directories");
        }
        try { content = new OcflResourceStorage(files.root.resolve("ocfl"), files.root.resolve("ocfl-work")); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot initialize physical resource storage", e); }
        jobs = new StorageMaintenanceJobs(server, db, files, () -> content, value -> diagnostic = value);
    }

    public void start() { worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS); }

    @Override public void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Resource maintenance did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Resource shutdown interrupted", e);
        }
        content.close();
    }

    private List<String> ids(String label) {
        try (var tx = db.beginTx()) {
            return new CoreRepository(tx, ZonedDateTime.now(java.time.ZoneOffset.UTC)).nodes(label, Map.of()).stream().map(CoreRepository::id).toList();
        }
    }

    /** Startup reconciliation order is persisted purges, staging expiry, locations, orphans, then backend work. */
    public void recover() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        try (var tx = db.beginTx()) {
            for (Node loc : new CoreRepository(tx, ZonedDateTime.now(java.time.ZoneOffset.UTC)).nodes("DepositResourceLocation", Map.of()))
                if (loc.hasProperty("path") || loc.hasProperty("suffix")) throw new IllegalStateException("Legacy resource records require fresh provisioning");
        }
        for (String id : ids("DepositPruneItem")) { jobs.collect(id); jobs.collect(id); }
        try { jobs.expireImportPlans(); jobs.expireTables(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot expire staged operational data", e); }
        for (String id : ids("DepositResourceLocation")) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Resource recovery exceeded startup bound");
            jobs.process(id, true);
        }
        jobs.discoverOrphans();
        try { content.recoverWork(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile resource work", e); }
    }

    private void tick() {
        try {
            jobs.expireImportPlans(); jobs.expireTables();
            for (String id : ids("DepositResourceLocation")) {
                if (Thread.currentThread().isInterrupted()) return;
                jobs.process(id, false);
            }
            for (String id : ids("DepositPruneItem")) jobs.collect(id);
        } catch (Exception e) { diagnostic = "Resource maintenance pending: " + e.getClass().getSimpleName(); }
    }
}
