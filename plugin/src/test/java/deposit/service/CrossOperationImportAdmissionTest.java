package deposit.service;
import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class CrossOperationImportAdmissionTest {
 @TempDir Path temporary;
 DatabaseManagementService management;
 GraphDatabaseAPI database;
 ServerContext server;
 String previousConfig,namespace,actor,token;
 @BeforeEach void setUp()throws Exception {
  previousConfig=System.getProperty("deposit.config");Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports"));Path config=temporary.resolve("deposit.properties");var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"stage7");}System.setProperty("deposit.config",config.toString());
  management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);actor=UUID.randomUUID().toString();token=ServerContext.randomToken();
  try(var tx=database.beginTx()){var repo=new CoreRepository(tx,java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);Node ns=repo.create("Namespace",null,actor,Json.map("name","Cross operation receiver","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");var session=new ServerContext.Session(actor,((InternalTransaction)tx).securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;session.baselines.put(namespace,0L);server.registerSession(Json.hash(token),session);tx.commit();}
 }
 @AfterEach void tearDown(){try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}finally{try{if(server!=null&&server.storage!=null)server.storage.close();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}}
 @Test void analysisAndApplyReleaseSecondaryReservationsAcrossOperations()throws Exception {
  String packageId=publishPackage();UUID analyze=UUID.randomUUID(),apply=UUID.randomUUID();
  Map<String,Object> analysis=call("deposit.import.analyze",analyze,args("source",Json.map("packageResourceId",packageId),"rules",Json.map("alwaysCreate",List.of("Specimen"))));
  String planId=(String)analysis.get("planId");
  Map<String,Object> initialPlan=planSnapshot(planId);
  assertNotNull(planId);assertEquals(0,collectionCount(analysis,"conflicts"));
  assertTrue(commitments(server.storage.admission.volumeId(server.storage.files.root))<=65537L,"only plan metadata survives analysis");
  assertFalse(admissions(analyze).isEmpty(),"ready plan retains its staged package");

  Map<String,Object> applied=call("deposit.import.apply",apply,args("planId",planId));
  String resultId=(String)applied.get("resultId");

  Map<String,Object> byKinds=call("deposit.get.importResultPage",UUID.randomUUID(),args("resultId",resultId,"leaseId",applied.get("leaseId"),"collection","identityMappings","cursor",null,"limit",128L));
  String kindCollection=null;
  for(Object raw:(List<?>)byKinds.get("entries")){
   var row=ValueCodec.map(raw);String kind=(String)ValueCodec.map(row.get("key")).get("value");
   if("Object".equals(kind)){kindCollection=(String)ValueCodec.map(row.get("value")).get("collection");break;}
  }
  assertNotNull(kindCollection);
  Map<String,Object> objectPage=call("deposit.get.importResultPage",UUID.randomUUID(),args("resultId",resultId,"leaseId",applied.get("leaseId"),"collection",kindCollection,"cursor",null,"limit",128L));
  List<?> objectRows=(List<?>)objectPage.get("entries");
  assertEquals(1,objectRows.size(),"one actual Object identity in the apply receipt");
  String target=(String)ValueCodec.map(ValueCodec.map(objectRows.get(0)).get("value")).get("value");assertNotNull(target);
  assertEquals(ImportResultRepositoryTest.withoutLease(applied),call("deposit.import.apply",apply,args("planId",planId)),"same receipt replay must not create new objects");
  assertEquals(ImportResultRepositoryTest.withoutLease(applied),ImportResultRepositoryTest.withoutLease(call("deposit.get.importResult",UUID.randomUUID(),args("resultId",resultId))));
  Map<String,Object> beforeInspection=planSnapshot(planId);
  Map<String,Object> inspected=readOnlyImportPlan(planId);
  assertEquals("applied",ValueCodec.map(inspected.get("plan")).get("state"));
  assertEquals(analysis.get("resultId"),ValueCodec.map(inspected.get("analysisResult")).get("resultId"),"inspection returns the immutable analysis result");
  assertEquals(analysis.get("kind"),ValueCodec.map(inspected.get("analysisResult")).get("kind"));
  assertEquals(initialPlan.get("expiresAt"),ValueCodec.map(inspected.get("analysisResult")).get("expiresAt"),"applied analysis expiry remains immutable");
  assertEquals(beforeInspection,planSnapshot(planId),"read-only inspection must not change plan fields");
  assertEquals(1,countNodes("Object"));assertEquals(1,countNodes("DResource"),"source package DResource is preserved, with no extra imported binary record");
  assertTrue(admissions(apply).isEmpty(),"ordinary replay releases operation reservations: "+Json.write(readAdmissionFiles()));
 }
 private Map<String,Object> readOnlyImportPlan(String planId)throws Exception {
  try(var tx=database.beginTx()){
   var context=new RequestContext(tx,args("token",token));
   var actions=new SessionActionService(context);actions.begin("deposit.get.importPlan",List.of(planId));var row=((Results.DataRow)new ProcedureService(context).execute().findFirst().orElseThrow()).data;actions.observe();

   tx.rollback();return row;
  }
 }
 private Map<String,Object> planSnapshot(String planId)throws Exception {
  try(var tx=database.beginTx()){
   Node plan=tx.findNode(Label.label("DepositImportPlan"),"id",planId);assertNotNull(plan);
   Map<String,Object> values=new LinkedHashMap<>(plan.getAllProperties());
   tx.rollback();return values;
  }
 }

 private String publishPackage()throws Exception {
  String source=UUID.randomUUID().toString(),clazz=UUID.randomUUID().toString();
  String object=UUID.randomUUID().toString(),descriptor=UUID.randomUUID().toString(),member=UUID.randomUUID().toString();
  var decl=List.of(entity("ClassDescriptor",source,UUID.randomUUID().toString(),args("classId",clazz,"name","Code","type","String","order",0L,"comment","")));
  var clazzRow=entity("Class",source,clazz,args("name","Specimen","position",0L,"order",0L,"comment","","descriptors",decl));
  var obj=entity("Object",source,object,args("classId",clazz,"position",List.of(0.0,0.0)));
  var desc=entity("Descriptor",source,descriptor,args("objectId",object,"name","Code","value","one"));
  var mem=entity("Membership",source,member,args("objectId",object,"classId",clazz));
  var manifest=args("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",source,"name","Cross","description","","depositVersion",null,"historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");
  var graph=args("classes",List.of(clazzRow),"objects",List.of(obj),"descriptors",List.of(desc),"geometries",List.of(),"memberships",List.of(mem),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());
  Path archive=temporary.resolve("small.zip");
  ExchangePackageCodec.write(archive,manifest,graph,null,Map.of());
  long length=Files.size(archive);
  String checksum=ResourceFiles.checksum(archive),id=UUID.randomUUID().toString();
  String stored=ResourceFiles.filename("small.zip",checksum);
  server.storage.content.publish(new ResourceStorage.Content(id,stored,length,checksum),archive);
  try(var tx=database.beginTx()){
   var core=new CoreRepository(tx,java.time.ZonedDateTime.now());
   var repository=new ResourceRepository(tx,core);
   repository.record("DResource",id,args("namespaceId",namespace,"filename","small.zip","storedFilename",stored,
       "mediaType","application/zip","bytes",length,"checksum",checksum,"state","ready","createdBy",actor));
   repository.record("DepositResourceLocation",id,args("namespaceId",namespace,"storedFilename",stored,
       "bytes",length,"checksum",checksum,"published",true,"everAttached",false));
   repository.record("DepositExportGrant",UUID.randomUUID().toString(),args("resourceId",id,"namespaceId",namespace,
       "actorId",actor,"expires",server.sessionTime()+ResourceFiles.LEASE));
   tx.commit();
  }
  return id;
 }
 private long countNodes(String label){try(var tx=database.beginTx()){long count=tx.findNodes(Label.label(label)).stream().count();tx.rollback();return count;}}
 private Map<String,Object> call(String procedure,UUID operation,Map<String,Object> request)throws Exception {try(var tx=database.beginTx()){var context=new RequestContext(tx,args("token",token,"operationId",operation.toString()));context.publicCall=true;context.procedure=procedure;context.request=request;var actions=new SessionActionService(context);actions.begin(procedure,ProcedureCatalog.spec(procedure).bindings().stream().map(request::get).collect(java.util.stream.Collectors.toList()));var row=(DataRow)new ProcedureService(context).execute().findFirst().orElseThrow();actions.observe();tx.commit();return row.data;}}
 private Set<String> admissions(UUID operation)throws Exception {var ids=new TreeSet<String>();try(var paths=Files.list(server.storage.files.root.resolve("admission"))){for(Path p:paths.filter(Files::isRegularFile).toList()){if(operation.toString().equals(Json.read(Files.readString(p)).get("operationId")))ids.add(Json.read(Files.readString(p)).get("ownerKind")+":"+Json.read(Files.readString(p)).get("purpose"));}}return ids;}
 private List<Map<String,Object>> readAdmissionFiles()throws Exception {var rows=new ArrayList<Map<String,Object>>();try(var paths=Files.list(server.storage.files.root.resolve("admission"))){for(Path path:paths.filter(Files::isRegularFile).toList())rows.add(Json.read(Files.readString(path)));}return rows;}
 private long commitments(String volume)throws Exception {
  long held=0;
  for(var row:readAdmissionFiles()) {
   var reservation=server.storage.admission.resume((String)row.get("id"));
   held=Math.addExact(held,reservation.commitments().getOrDefault(volume,0L));
  }
  return held;
 }
 private static long collectionCount(Map<String,Object> envelope,String name){Object value=ValueCodec.map(envelope.get("collections")).get(name);return value==null?0L:((Number)ValueCodec.map(value).get("count")).longValue();}
 private static Map<String,Object> args(Object... values){var map=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);return map;}
 private static Map<String,Object> entity(String kind,String source,String id,Map<String,Object> fields){var map=new LinkedHashMap<String,Object>();map.put("kind",kind);map.put("id",id);map.put("namespaceId",source);map.putAll(fields);return map;}
}
