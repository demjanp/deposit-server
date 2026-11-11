package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;

class StorageMaintenanceJobsTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseService database;

    @BeforeEach void startDatabase() {
        management = new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"), type -> type != DepositExtension.class).build();
        database = management.database("neo4j");
    }

    @AfterEach void stopDatabase() {
        if (management != null) management.shutdown();
    }

    @Test void terminalChunkCleanupDeletesOnlyBoundedBatchesAndKeepsActiveUploads() {
        try (var tx = database.beginTx()) {
            createUpload(tx, "finished", "finished", 3);
            createUpload(tx, "active", "uploading", 2);
            tx.commit();
        }

        try (var tx = database.beginTx()) {
            assertEquals(2, StorageMaintenanceJobs.collectTerminalTransferChunks(tx, 2));
            tx.commit();
        }
        assertEquals(3, chunkCount());

        try (var tx = database.beginTx()) {
            assertEquals(1, StorageMaintenanceJobs.collectTerminalTransferChunks(tx, 2));
            tx.commit();
        }
        assertEquals(0, chunkCountFor("finished"));
        assertEquals(2, chunkCountFor("active"));
    }

    private void createUpload(org.neo4j.graphdb.Transaction tx, String uploadId, String state, int count) {
        var transfer = tx.createNode(Label.label("DepositTransfer"));
        transfer.setProperty("id", uploadId);
        transfer.setProperty("state", state);
        for (long offset = 0; offset < count; offset++) {
            var chunk = tx.createNode(Label.label("DepositTransferChunk"));
            chunk.setProperty("id", uploadId + ":" + offset);
            chunk.setProperty("uploadId", uploadId);
            chunk.setProperty("offset", offset);
        }
    }

    private long chunkCount() {
        try (var tx = database.beginTx(); var rows = tx.findNodes(Label.label("DepositTransferChunk"))) {
            return rows.stream().count();
        }
    }

    private long chunkCountFor(String uploadId) {
        try (var tx = database.beginTx(); var rows = tx.findNodes(Label.label("DepositTransferChunk"), "uploadId", uploadId)) {
            return rows.stream().count();
        }
    }
}
