package deposit.api;

import deposit.service.GetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class GetInterfaceTest {

    @Mock
    private GraphDatabaseService db;

    @Mock
    private Log log;

    @Mock
    private GetService getService;

    @InjectMocks
    private GetInterface getInterface;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        getInterface = new GetInterface(db, log);
        getInterface.setGetService(getService);
    }

    @Test
    void testGetDatabaseNames() {
        // Arrange
        List<GetService.DatabaseNameResult> mockResults = Arrays.asList(
            new GetService.DatabaseNameResult("db1"),
            new GetService.DatabaseNameResult("db2"),
            new GetService.DatabaseNameResult("db3")
        );
        when(getService.getDatabaseNames()).thenReturn(mockResults.stream());

        // Act
        List<GetService.DatabaseNameResult> result = getInterface.getDatabaseNames().collect(Collectors.toList());

        // Assert
        assertEquals(3, result.size());
        assertEquals("db1", result.get(0).databaseName);
        assertEquals("db2", result.get(1).databaseName);
        assertEquals("db3", result.get(2).databaseName);
    }

    @Test
    void testGetDatabaseNamesEmpty() {
        // Arrange
        when(getService.getDatabaseNames()).thenReturn(Stream.empty());

        // Act
        List<GetService.DatabaseNameResult> result = getInterface.getDatabaseNames().collect(Collectors.toList());

        // Assert
        assertEquals(0, result.size());
    }
}
