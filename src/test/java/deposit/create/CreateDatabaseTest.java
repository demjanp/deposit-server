package deposit.create;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class CreateDatabaseTest {

    private CreateDatabase createDatabase;
    private GraphDatabaseService mockDb;
    private Log mockLog;

    @BeforeEach
    public void setUp() {
        mockDb = Mockito.mock(GraphDatabaseService.class);
        mockLog = Mockito.mock(Log.class);

        createDatabase = new CreateDatabase();
        createDatabase.db = mockDb;
        createDatabase.log = mockLog;
    }

    @Test
    public void testCreateDatabaseSuccessfully() {
        String databaseName = "testDb";

        Transaction mockTransaction = mock(Transaction.class);
        when(mockDb.beginTx()).thenReturn(mockTransaction);

        // Using doNothing() because executeTransactionally() is void
        doNothing().when(mockDb).executeTransactionally(anyString(), anyMap());
        doNothing().when(mockDb).executeTransactionally(anyString());

        assertDoesNotThrow(() -> createDatabase.createDatabase(databaseName));

        verify(mockDb).executeTransactionally(String.format("CREATE DATABASE `%s`", databaseName));
        verify(mockDb).executeTransactionally(String.format("USE `%s`", databaseName));
        verify(mockDb).executeTransactionally(anyString(), anyMap());
        verify(mockTransaction).commit();
    }

    @Test
    public void testCreateDatabaseThrowsExceptionForArchive() {
        String databaseName = "testDb_archive";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> createDatabase.createDatabase(databaseName));
        assertEquals("Database name cannot end with '_archive'", exception.getMessage());
    }

    @Test
    public void testCreateDatabaseThrowsExceptionOnError() {
        String databaseName = "testDb";

        Transaction mockTransaction = mock(Transaction.class);
        when(mockDb.beginTx()).thenReturn(mockTransaction);

        // Using doThrow() to simulate an error
        doThrow(new RuntimeException("Simulated error")).when(mockDb).executeTransactionally(anyString());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> createDatabase.createDatabase(databaseName));
        assertTrue(exception.getMessage().contains("Failed to create database: Simulated error"));

        verify(mockLog).error(eq("Error creating database: " + databaseName), any(Exception.class));
    }
}
