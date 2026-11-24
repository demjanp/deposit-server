package deposit.common;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.*;
import java.util.List;
import java.util.Set;
import deposit.repository.ExchangeRepository;
import deposit.repository.ExportGrantRepository;
import deposit.repository.ImportResultStore;
import deposit.repository.OperationRepository;
import deposit.repository.StagedTableRepository;
import deposit.repository.ImportPlanRepository;
import deposit.repository.ResourceTransferStore;
import deposit.repository.PruneRepository;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StoreId;

/** The private database shared by Deposit's operational repositories. */
public final class OperationalStore implements AutoCloseable {
    private static final int VERSION = 3;
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");
    private final Path path;
    private final StorageAdmission admission;
    private final Connection anchor;

    OperationalStore(Path root, GraphDatabaseAPI database, StorageAdmission admission) throws IOException {
        this.admission = admission;
        Path directory = root.resolve("operational");
        path = directory.resolve("operational.sqlite");
        var storeId = database.getDependencyResolver().resolveDependency(StorageEngine.class).metadataProvider().getStoreId();
        if (storeId.equals(StoreId.UNKNOWN)) throw new IOException("Neo4j store identity unavailable");
        boolean create = Files.notExists(directory, LinkOption.NOFOLLOW_LINKS);
        if (create) {
            try (var tx = database.beginTx(); var nodes = tx.getAllNodes().iterator()) {
                if (nodes.hasNext() || tx.schema().getConstraints().iterator().hasNext())
                    throw new IOException("Operational storage is missing for an existing Deposit database");
            }
            createPrivateDirectory(directory);
        }
        verifyPrivateDirectory(directory);
        if (!create) verifyFile(path);
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = path.resolveSibling(path.getFileName() + suffix);
            if (!Files.notExists(sidecar, LinkOption.NOFOLLOW_LINKS)) verifyFile(sidecar);
        }
        try {
            anchor = connect(create);
            try {
                if (create) {
                    admission.operationalWrite(path, 256 * 1024, () -> {
                        initialize(storeId);
                        return null;
                    });
                }
                verifyBinding(storeId);
                try (var statement = anchor.createStatement();
                     var rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                    if (!rows.next() || !"wal".equalsIgnoreCase(rows.getString(1)))
                        throw new SQLException("WAL is unavailable on the operational filesystem");
                }
            } catch (SQLException | IOException | RuntimeException e) {
                try { anchor.close(); } catch (SQLException close) { e.addSuppressed(close); }
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot open Deposit operational storage", e);
        }
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PRIVATE));
        } else {
            Files.createDirectory(directory);
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Private operational storage permissions are unavailable");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));
        }
    }

    private static void verifyPrivateDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())
                || !Files.getFileStore(directory).equals(Files.getFileStore(directory.getParent())))
            throw new IOException("Invalid private operational storage directory");
        var posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        if (posix != null) {
            if (!posix.readAttributes().permissions().equals(PRIVATE))
                throw new IOException("Operational storage requires owner-only permissions");
        } else {
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Private operational storage permissions are unavailable");
            var owner = acl.getOwner();
            var entries = acl.getAcl();
            if (entries.stream().noneMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && entry.principal().equals(owner) && entry.flags().contains(AclEntryFlag.FILE_INHERIT))
                    || entries.stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)))
                throw new IOException("Operational storage requires an inheritable owner-only ACL");
        }
    }

    private static void verifyFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath()))
            throw new IOException("Invalid or missing operational storage file " + file.getFileName());
    }

    private Connection connect(boolean create) throws SQLException {
        var settings = new org.sqlite.SQLiteConfig();
        if (!create) settings.resetOpenMode(org.sqlite.SQLiteOpenMode.CREATE);
        settings.enforceForeignKeys(true);
        settings.setBusyTimeout(1000);
        settings.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.FULL);
        return settings.createConnection("jdbc:sqlite:" + path);
    }

    private void initialize(StoreId store) throws SQLException {
        anchor.setAutoCommit(false);
        try (var sql = anchor.createStatement()) {
            sql.execute("""
                CREATE TABLE server_state (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    store_created_at INTEGER NOT NULL,
                    store_random INTEGER NOT NULL,
                    initialized INTEGER NOT NULL DEFAULT 0 CHECK (initialized IN (0, 1)),
                    bootstrap_user_id TEXT CHECK (initialized = 0 OR bootstrap_user_id IS NULL),
                    rejected_through INTEGER NOT NULL DEFAULT -1,
                    observed_time INTEGER NOT NULL DEFAULT -1
                ) STRICT
                """);
            OperationRepository.initialize(sql);
            ExchangeRepository.initialize(sql);
            ExportGrantRepository.initialize(sql);
            ImportResultStore.initialize(sql);
            StagedTableRepository.initialize(sql);
            ImportPlanRepository.initialize(sql);
            ResourceTransferStore.initialize(sql);
            PruneRepository.initialize(sql);
            try (var insert = anchor.prepareStatement(
                    "INSERT INTO server_state(id,store_created_at,store_random) VALUES (1,?,?)")) {
                insert.setLong(1, store.getCreationTime());
                insert.setLong(2, store.getRandom());
                insert.executeUpdate();
            }
            sql.execute("PRAGMA user_version=" + VERSION);
            anchor.commit();
        } catch (SQLException | RuntimeException e) {
            anchor.rollback();
            throw e;
        } finally {
            anchor.setAutoCommit(true);
        }
    }

    private void verifyBinding(StoreId store) throws SQLException, IOException {
        try (var statement = anchor.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            if (!rows.next() || rows.getInt(1) != VERSION) throw new IOException("Unexpected operational SQLite schema");
        }
        try (var statement = anchor.createStatement();
             var rows = statement.executeQuery("SELECT store_created_at,store_random FROM server_state WHERE id=1")) {
            if (!rows.next() || rows.getLong(1) != store.getCreationTime() || rows.getLong(2) != store.getRandom())
                throw new IOException("Operational SQLite belongs to a different Neo4j store");
        }
    }

    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    public <T> T read(SqlWork<T> work) {
        try (var connection = connect(false)) { return work.run(connection); }
        catch (SQLException e) { throw new IllegalStateException("Cannot read Deposit operational storage", e); }
    }

    public <T> T write(long payloadBytes, SqlWork<T> work) {
        try {
            // Reserve short-lived headroom for table/index pages, WAL and checkpoint growth.
            return admission.operationalWrite(path, Math.addExact(64 * 1024L, Math.multiplyExact(4, payloadBytes)), () -> {
                try (var connection = connect(false)) {
                    connection.setAutoCommit(false);
                    try {
                        T result = work.run(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
                }
            });
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Cannot update Deposit operational storage", e);
        }
    }

    @Override public void close() {
        try (anchor; var statement = anchor.createStatement();
             var rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rows.next() || rows.getInt(1) != 0) throw new SQLException("Operational SQLite checkpoint is busy");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot checkpoint Deposit operational storage", e);
        }
    }
}
