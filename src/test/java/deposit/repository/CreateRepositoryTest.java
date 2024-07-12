package deposit.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class CreateRepositoryTest {

 @Mock
 private GraphDatabaseService db;

 @Mock
 private Log log;

 private CreateRepository createRepository;

 @BeforeEach
 public void setUp() {
  MockitoAnnotations.openMocks(this);
  createRepository = new CreateRepository(db, log);
 }

 @Test
 public void shouldCreateClassDescriptorSuccessfully() {
  ZonedDateTime now = ZonedDateTime.now();
  String className = "TestClass";
  String name = "TestName";
  String type = "TestType";
  Long order = 1L;
  String comment = "TestComment";

  Transaction tx = mock(Transaction.class);
  when(db.beginTx()).thenReturn(tx);

  createRepository.createClassDescriptor(now, className, name, type, order, comment);

  verify(db, times(1)).beginTx();
 }

 @Test
 public void shouldCreateClassSuccessfully() {
  ZonedDateTime now = ZonedDateTime.now();
  String name = "TestClass";
  Long order = 1L;
  List<Double> position = Arrays.asList(1.0, 2.0);
  String comment = "TestComment";

  Transaction tx = mock(Transaction.class);
  Result result = mock(Result.class);
  Node node = mock(Node.class);

  when(db.beginTx()).thenReturn(tx);
  when(tx.execute(anyString(), anyMap())).thenReturn(result);
  when(result.hasNext()).thenReturn(true);
  when(result.next()).thenReturn(Map.of("a", node));

  createRepository.createClass(now, name, order, position, comment);

  verify(db, times(1)).beginTx();
  verify(tx, times(1)).commit();
 }

 @Test
 public void shouldCreateDatabaseSuccessfully() {
  ZonedDateTime now = ZonedDateTime.now();
  String databaseName = "TestDB";

  Transaction tx = mock(Transaction.class);
  when(db.beginTx()).thenReturn(tx);

  createRepository.createDatabase(now, databaseName);

  verify(db, times(1)).beginTx();
 }

 @Test
 public void shouldThrowExceptionWhenCreateClassDescriptorFails() {
  ZonedDateTime now = ZonedDateTime.now();
  String className = "TestClass";
  String name = "TestName";
  String type = "TestType";
  Long order = 1L;
  String comment = "TestComment";

  doThrow(new RuntimeException()).when(db).beginTx();

  assertThrows(RuntimeException.class, () -> createRepository.createClassDescriptor(now, className, name, type, order, comment));
 }

 @Test
 public void shouldThrowExceptionWhenCreateClassFails() {
  ZonedDateTime now = ZonedDateTime.now();
  String name = "TestClass";
  Long order = 1L;
  List<Double> position = Arrays.asList(1.0, 2.0);
  String comment = "TestComment";

  doThrow(new RuntimeException()).when(db).beginTx();

  assertThrows(RuntimeException.class, () -> createRepository.createClass(now, name, order, position, comment));
 }

 @Test
 public void shouldThrowExceptionWhenCreateDatabaseFails() {
  ZonedDateTime now = ZonedDateTime.now();
  String databaseName = "TestDB";

  doThrow(new RuntimeException()).when(db).beginTx();

  assertThrows(RuntimeException.class, () -> createRepository.createDatabase(now, databaseName));
 }
}