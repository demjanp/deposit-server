package deposit.api;

import deposit.service.CreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CreateInterfaceTest {

    @Mock
    private GraphDatabaseService db;

    @Mock
    private Log log;

    @Mock
    private Node node;

    @Mock
    private CreateService createService;

    @InjectMocks
    private CreateInterface createInterface;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        createInterface = new CreateInterface(db, log);
        createInterface.setCreateService(createService); // Ensure the service is set
    }

    @Test
    public void createClassDescriptorShouldCallService() {
        createInterface.createClassDescriptor("class", "descriptor", "String", 1L, "comment");
        verify(createService, times(1)).createClassDescriptor("class", "descriptor", "String", 1L, "comment");
    }

    @Test
    public void createClassShouldCallService() {
        List<Double> position = Arrays.asList(1.0, 2.0);
        List<List<String>> descriptors = Arrays.asList(Arrays.asList("descriptor1", "String"), Arrays.asList("descriptor2", "Integer"));
        createInterface.createClass("name", 1L, position, "comment", descriptors);
        verify(createService, times(1)).createClass("name", 1L, position, "comment", descriptors);
    }

    @Test
    public void createDatabaseShouldCallService() {
        createInterface.createDatabase("database");
        verify(createService, times(1)).createDatabase("database");
    }

    @Test
    public void createDatabaseShouldThrowExceptionWhenDatabaseNameEndsWithArchive() {
        doThrow(new RuntimeException("Invalid database name")).when(createService).createDatabase("database_archive");
        assertThrows(RuntimeException.class, () -> createInterface.createDatabase("database_archive"));
    }
}
