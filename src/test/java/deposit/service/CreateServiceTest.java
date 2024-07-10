package deposit.service;

import deposit.repository.CreateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CreateServiceTest {

    @Mock
    private GraphDatabaseService db;

    @Mock
    private Log log;

    @Mock
    private CreateRepository createRepository;

    @Mock
    private Node node;

    private CreateService createService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        createService = new CreateService(db, log);
        createService.setCreateRepository(createRepository); // Ensure the repository is set
    }

    @Test
    public void createClassDescriptorShouldCallRepository() {
        doNothing().when(createRepository).createClassDescriptor(anyString(), anyString(), anyString(), anyLong(), anyString());
        createService.createClassDescriptor("class", "descriptor", "String", 1L, "comment");
        verify(createRepository, times(1)).createClassDescriptor(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    public void createClassShouldCallRepositoryAndCreateDescriptors() {
        Transaction tx = mock(Transaction.class);
        when(db.beginTx()).thenReturn(tx);
        List<Double> position = Arrays.asList(1.0, 2.0);
        List<List<String>> descriptors = Arrays.asList(Arrays.asList("descriptor1", "String"), Arrays.asList("descriptor2", "Integer"));
        when(createRepository.createClass(anyString(), anyLong(), anyList(), anyString())).thenReturn(node);
        createService.createClass("name", 1L, position, "comment", descriptors);
        verify(createRepository, times(1)).createClass(anyString(), anyLong(), anyList(), anyString());
        verify(createRepository, times(descriptors.size())).createClassDescriptor(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    public void createDatabaseShouldCallRepository() {
        doNothing().when(createRepository).createDatabase(anyString());
        createService.createDatabase("database");
        verify(createRepository, times(1)).createDatabase(anyString());
    }

    @Test
    public void createDatabaseShouldThrowExceptionWhenDatabaseNameEndsWithArchive() {
        assertThrows(RuntimeException.class, () -> createService.createDatabase("database_archive"));
    }
}