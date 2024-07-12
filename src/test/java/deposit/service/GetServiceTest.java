package deposit.service;

import deposit.repository.GetRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class GetServiceTest {

    @Mock
    private GraphDatabaseService db;

    @Mock
    private Log log;

    @Mock
    private GetRepository getRepository;

    @InjectMocks
    private GetService getService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        getService = new GetService(db, log);
        getService.setGetRepository(getRepository);
    }

    @Test
    void testGetDatabaseNames() {
        // Arrange
        List<String> mockDatabaseNames = Arrays.asList("db1", "db2", "db3");
        when(getRepository.getDatabaseNames()).thenReturn(mockDatabaseNames);

        // Act
        List<GetService.DatabaseNameResult> result = getService.getDatabaseNames().collect(Collectors.toList());

        // Assert
        assertEquals(3, result.size());
        assertEquals("db1", result.get(0).databaseName);
        assertEquals("db2", result.get(1).databaseName);
        assertEquals("db3", result.get(2).databaseName);
    }

    @Test
    void testGetDatabaseNamesEmpty() {
        // Arrange
        when(getRepository.getDatabaseNames()).thenReturn(Arrays.asList());

        // Act
        List<GetService.DatabaseNameResult> result = getService.getDatabaseNames().collect(Collectors.toList());

        // Assert
        assertEquals(0, result.size());
    }

    @Test
    void testGetDatabaseNamesWithArchives() {
        // Arrange
        List<String> mockDatabaseNames = Arrays.asList("db1", "db2_archive", "db3");
        when(getRepository.getDatabaseNames()).thenReturn(mockDatabaseNames.stream().filter(name -> !name.endsWith("_archive")).collect(Collectors.toList()));

        // Act
        List<GetService.DatabaseNameResult> result = getService.getDatabaseNames().collect(Collectors.toList());

        // Assert
        assertEquals(2, result.size());
        assertEquals("db1", result.get(0).databaseName);
        assertEquals("db3", result.get(1).databaseName);
    }
}
