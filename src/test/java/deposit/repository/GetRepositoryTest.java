package deposit.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

public class GetRepositoryTest {

    private GetRepository getRepository;

    @Mock
    private GraphDatabaseService db;

    @Mock
    private Log log;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        getRepository = new GetRepository(db, log);
    }

    @Test
    public void getDatabaseNames_returnsEmptyList_whenNoDatabasesExist() {
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute("CALL dbms.databases()")).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        assertTrue(getRepository.getDatabaseNames().isEmpty());

        verify(tx).close();
    }

    @Test
    public void getDatabaseNames_returnsDatabaseNames_whenDatabasesExist() {
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);
        Result metadataResult = mock(Result.class);

        Map<String, Object> row = new HashMap<>();
        row.put("name", "test_db");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("depositVersion", "1.0");
        metadata.put("versioningType", "type");
        metadata.put("description", "description");
        metadata.put("createdAt", "2022-01-01T00:00:00Z");
        metadata.put("createdBy", "user");
        metadata.put("updatedAt", "2022-01-01T00:00:00Z");
        metadata.put("updatedBy", "user");
        metadata.put("restored", "false");

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute("CALL dbms.databases()")).thenReturn(result);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(row);

        // Mock the subsequent query for metadata
        when(tx.execute("MATCH (m:Metadata) RETURN m")).thenReturn(metadataResult);
        when(metadataResult.hasNext()).thenReturn(true);
        when(metadataResult.next()).thenReturn(Collections.singletonMap("m", metadata));

        assertEquals(Arrays.asList("test_db"), getRepository.getDatabaseNames());

        verify(tx).close();
    }

    @Test
    public void getDatabaseNames_throwsRuntimeException_whenExceptionOccurs() {
        Transaction tx = mock(Transaction.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute("CALL dbms.databases()")).thenThrow(new RuntimeException());

        assertThrows(RuntimeException.class, () -> getRepository.getDatabaseNames());

        verify(tx).close();
    }

    @Test
    void testGetClassByName() {
        // Arrange
        String className = "TestClass";
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);
        Node node = mock(Node.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of("a", node));

        // Act
        List<Node> classNodes = getRepository.getClass(className);

        // Assert
        assertEquals(1, classNodes.size());
        assertEquals(node, classNodes.get(0));
        verify(tx).commit();
    }

    @Test
    void testGetAllClasses() {
        // Arrange
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);
        Node node = mock(Node.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true, true, false);
        when(result.next()).thenReturn(Map.of("a", node), Map.of("a", node));

        // Act
        List<Node> classNodes = getRepository.getClass("");

        // Assert
        assertEquals(2, classNodes.size());
        assertEquals(node, classNodes.get(0));
        assertEquals(node, classNodes.get(1));
        verify(tx).commit();
    }

    @Test
    void testGetClass_NoNodeReturned() {
        // Arrange
        String className = "TestClass";
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        // Act
        List<Node> classNodes = getRepository.getClass(className);

        // Assert
        assertEquals(0, classNodes.size());
        verify(tx).commit();
    }

    @Test
    void testGetClass_ExceptionThrown() {
        // Arrange
        String className = "TestClass";
        Transaction tx = mock(Transaction.class);

        when(db.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenThrow(new RuntimeException("Execution error"));

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            getRepository.getClass(className);
        });
        assertEquals("Failed to get Class nodes: Execution error", exception.getMessage());
        verify(tx, never()).commit();
    }

}
