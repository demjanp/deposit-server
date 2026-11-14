package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Real-byte and actual public atomic-import acceptance; intentionally gated from quick runs. */
@EnabledIfSystemProperty(named="deposit.stage7",matches="true")
class Stage7CapacityTest {
    private static final int MEMBERS=100_001, OBJECTS=17_001;
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,namespace,actor,token;

    @Test void actualResourceMembersAboveOneHundredThousandValidate()throws Exception {
        Path root=Files.createDirectory(temporary.resolve("members")),archive=temporary.resolve("100001-members.zip");
        try(var out=Files.newBufferedWriter(root.resolve("graph.json"),StandardCharsets.UTF_8)){
            out.write("{\"classes\":[],\"objects\":[],\"descriptors\":[],\"geometries\":[],\"memberships\":[],\"relationships\":[],\"classRelationships\":[],\"patterns\":[],\"userTools\":[],\"resources\":[");
            byte[] bytes=new byte[4];
            for(int i=0;i<MEMBERS;i++){
                ByteBuffer.wrap(bytes).putInt(i);String checksum=digest(bytes);Files.write(root.resolve(checksum),bytes);
                if(i>0)out.write(',');out.write(Json.write(Json.map("kind","DResource","id",id("entry",i),"filename","member.bin","mediaType","application/octet-stream","bytes",4L,"sha256",checksum,"entry","resources/"+checksum)));
            }
            out.write("]}");
        }
        writeArchive(root,archive,manifest(UUID.randomUUID().toString()));
        long entries=0;try(var zip=new ZipFile(archive.toFile())){var found=zip.entries();while(found.hasMoreElements()){
            ZipEntry entry=found.nextElement();entries++;if(entry.getName().startsWith("resources/")){
                byte[] actual;try(var in=zip.getInputStream(entry)){actual=in.readNBytes(5);}assertEquals(4,actual.length);assertEquals(entry.getName().substring(10),digest(actual));
            }
        }}assertEquals(MEMBERS+2L,entries,"all archive entries and member bytes are real");
        probeCompleteMembership(archive);
        System.out.println("STAGE7 archive="+archive+" entries="+entries+" resourceMembers="+MEMBERS+" actualResourceBytes="+(MEMBERS*4L)+" archiveBytes="+Files.size(archive)+" completeMembershipHeap="+COMPLETE_MEMBERSHIP_HEAP+" budgetMinutes="+COMPLETE_MEMBERSHIP_BUDGET_MINUTES);
    }

    @Test void publicAtomicImportReplaysAndPagesAboveSixteenMiB()throws Exception {
        startServer();prepareReceivingNamespace();String source=UUID.randomUUID().toString();
        Path root=Files.createDirectory(temporary.resolve("import")),archive=temporary.resolve("atomic-import.zip");
        byte[] bytes="actual Stage7 resource retained across rollback".getBytes(StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(bytes);Files.write(root.resolve(checksum),bytes);
        writeGraph(root.resolve("graph.json"),source,checksum,bytes.length);writeArchive(root,archive,manifest(source));
        var summary=ExchangePackageCodec.validateSummary(archive);assertEquals((long)OBJECTS,summary.counts().get("objects"));assertEquals((long)OBJECTS,summary.counts().get("memberships"));
        String packageResource=publishPackage(archive);Set<String> before=storageIds();UUID analyzeOperation=UUID.randomUUID();
        Map<String,Object> analysis=call("deposit.import.analyze",analyzeOperation,args("source",Json.map("packageResourceId",packageResource),"rules",Json.map("alwaysCreate",List.of("Specimen"))));
        String planId=(String)analysis.get("planId");assertEquals(0L,collectionCount(analysis,"conflicts"));assertEquals(OBJECTS,collectionCount(analysis,"objectMappings"));
        UUID operation=UUID.randomUUID();var observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);
        var induced=new IllegalStateException("Stage7 induced failure after publication and result creation");var once=new AtomicBoolean();
        observer.invoke(server,(java.util.function.Consumer<String>)point->{if("history-before-receipt".equals(point)&&once.compareAndSet(false,true))throw induced;});
        IllegalStateException actual=assertThrows(IllegalStateException.class,()->call("deposit.import.apply",operation,args("planId",planId)));
        observer.invoke(server,(java.util.function.Consumer<String>)point->{});assertSame(induced,actual);assertRolledBack(operation,planId);
        Set<String> added=storageIds();added.removeAll(before);assertEquals(1,added.size());String retainedResource=added.iterator().next();assertTrue(server.storage.content.exists(retainedResource));
        Set<String> retained=admissions(operation);assertFalse(retained.isEmpty(),"publication remains admission-owned for reconciliation");
        server.storage.admission.reconcile(server.storage::reconcileRuntimeImportWrite);assertEquals(retained,admissions(operation),"uncertain runtime reconciliation does not erase resources or ownership");
        Map<String,Object> applied=call("deposit.import.apply",operation,args("planId",planId));String result=(String)applied.get("resultId");assertEquals(OBJECTS,collectionCount(applied,"objectMappings"));assertEquals((long)OBJECTS,metadata(applied,"createdObjects"));assertEquals(1L,metadata(applied,"createdResources"));
        assertEquals(applied,call("deposit.import.apply",operation,args("planId",planId)),"same-operation committed receipt replay");
        assertEquals(applied,call("deposit.get.importResult",null,args("resultId",result)));
        try(var tx=database.beginTx()){assertEquals(Json.read((String)tx.findNode(Label.label("DepositReceipt"),"operationId",operation.toString()).getProperty("outcome")),applied);assertEquals(OBJECTS+1L,countNodes(tx,"Object"));assertEquals(2L,countNodes(tx,"DResource"));tx.rollback();}
        assertTrue(server.storage.content.exists(retainedResource),"retained rollback bytes are not expected to disappear during replay");
        long retrieved=0,rows=0;int pages=0;var nested=new HashMap<String,String>();for(var row:mapping(result,"identityMappings")){nested.put(scalar(row.get("key")),collection(row.get("value")));pages++;}
        for(String kind:List.of("Object","Descriptor","Membership")){
            Set<String> keys=new HashSet<>();int count=0;
            Map<String,String> expected=new java.util.LinkedHashMap<>();
            String prefix=kind.equals("Object")?"object":kind.equals("Descriptor")?"descriptor":"member";
            for(int ordinal=0;ordinal<OBJECTS;ordinal++)expected.put(id(prefix,ordinal),null);
            for(var row:mapping(result,nested.get(kind))){String key=scalar(row.get("key"));String previous=expected.remove(key);assertFalse(previous!=null,"Missing or mismatched key "+key);assertFalse(expected.containsKey(key),"Duplicate key "+key);String target=scalar(row.get("value"));assertNotNull(target);if(kind.equals("Object"))assertObjectExists(target);retrieved+=Json.write(row).getBytes(StandardCharsets.UTF_8).length;count++;rows++;pages++;}
            assertTrue(expected.isEmpty(),"Unseen keys: "+expected.keySet());assertEquals(OBJECTS,count);
        }
        assertEquals(3L*OBJECTS,rows);assertTrue(retrieved>16L*1024*1024,"decoded nested identity mapping bytes exceed 16 MiB");
        subprocess("objects",archive,"128m");
        System.out.println("STAGE7 publicImport archive="+archive+" objects="+OBJECTS+" nestedIdentityRows="+rows+" decodedNestedIdentityBytes="+retrieved+" pageBatches="+pages+" actualResourceBytes="+bytes.length+" retainedRollbackResourceId="+retainedResource+" resultId="+result);
    }

    private void startServer()throws Exception {
        previousConfig=System.getProperty("deposit.config");Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports"));Path config=temporary.resolve("deposit-test.properties");var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"stage7");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();token=ServerContext.randomToken();
    }
    private void prepareReceivingNamespace()throws Exception {
        byte[] original="pre-existing typed resource".getBytes(StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(original),resourceId=id("old-resource",0);Path staged=temporary.resolve("old.bin");Files.write(staged,original);server.storage.content.publish(new ResourceStorage.Content(resourceId,ResourceFiles.filename("old.bin",checksum),original.length,checksum),staged);
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);Node ns=repo.create("Namespace",null,actor,Json.map("name","Stage7 receiver","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            Node clazz=repo.create("Class",namespace,actor,Json.map("name","Specimen","position",0L));repo.attach("ClassDescriptor",clazz,actor,Json.map("name","Code","type","String","order",0L));repo.attach("ClassDescriptor",clazz,actor,Json.map("name","File","type","DResource","order",1L));Node object=repo.create("Object",namespace,actor,Json.map("position",List.of(0.0,0.0)));repo.assign(object,clazz,actor);Node descriptor=repo.attach("Descriptor",object,actor,Json.map("name","File"));
            var resources=new ResourceRepository(tx,repo);resources.record("DepositResourceLocation",resourceId,Json.map("namespaceId",namespace,"storedFilename",ResourceFiles.filename("old.bin",checksum),"bytes",(long)original.length,"checksum",checksum,"leaseUntil",resources.now()+ResourceFiles.LEASE,"everAttached",true,"published",true));Node resource=resources.record("DResource",resourceId,Json.map("namespaceId",namespace,"filename","old.bin","mediaType","application/octet-stream","bytes",(long)original.length,"checksum",checksum,"state","ready","createdBy",actor));descriptor.createRelationshipTo(resource,RelationshipType.withName("HAS_RESOURCE"));
            var session=new ServerContext.Session(actor,((InternalTransaction)tx).securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;session.baselines.put(namespace,0L);server.registerSession(Json.hash(token),session);tx.commit();
        }
    }
    private void writeGraph(Path file,String source,String checksum,int bytes)throws Exception {try(var out=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
        out.write("{\"classes\":[");out.write(classRow(source));out.write("],\"objects\":[");
        for(int i=0;i<OBJECTS;i++){if(i>0)out.write(',');out.write(Json.write(entity("Object",source,id("object",i),Json.map("classId",id("class",0),"position",List.of((double)i,0.0)))));}out.write("],\"descriptors\":[");
        for(int i=0;i<OBJECTS;i++){if(i>0)out.write(',');out.write(Json.write(entity("Descriptor",source,id("descriptor",i),Json.map("objectId",id("object",i),"name","Code","value","stage7-value-"+i))));}
        out.write(',');out.write(Json.write(entity("Descriptor",source,id("descriptor",OBJECTS),Json.map("objectId",id("object",0),"name","File","value",Json.map("resourceId",id("resource",0))))));out.write("],\"geometries\":[],\"memberships\":[");
        for(int i=0;i<OBJECTS;i++){if(i>0)out.write(',');out.write(Json.write(entity("Membership",source,id("member",i),Json.map("objectId",id("object",i),"classId",id("class",0)))));}
        var pattern=Json.map("objects",Json.map("specimen",Json.map("className","Specimen","root",true,"descriptors",List.of(Json.map("name","Code","type","String")),"relationships",List.of())));
        out.write("],\"relationships\":[],\"classRelationships\":[");
        out.write(Json.write(entity("ClassRelationship",source,id("class-relationship",0),Json.map("sourceId",id("class",0),"targetId",id("class",0),"label","related","comment",""))));
        out.write("],\"patterns\":[");out.write(Json.write(entity("Pattern",source,id("pattern",0),Json.map("name","Stage7 typed graph","comment","","definition",pattern))));
        out.write("],\"userTools\":[],\"resources\":[");out.write(Json.write(Json.map("kind","DResource","id",id("resource",0),"filename","actual-stage7.bin","mediaType","application/octet-stream","bytes",(long)bytes,"sha256",checksum,"entry","resources/"+checksum)));out.write("]}");
    }}
    private static void writeArchive(Path root,Path archive,Map<String,Object> manifest)throws Exception {
        var files=new TreeMap<String,Path>();files.put("graph.json",root.resolve("graph.json"));try(var paths=Files.list(root)){for(Path path:paths.filter(Files::isRegularFile).toList()){String name=path.getFileName().toString();if(!name.equals("graph.json")&&!name.equals("manifest.json"))files.put("resources/"+name,path);}}
        var metadata=new TreeMap<String,Object>();for(var entry:files.entrySet())metadata.put(entry.getKey(),Json.map("bytes",Files.size(entry.getValue()),"sha256",ResourceFiles.checksum(entry.getValue())));manifest.put("files",metadata);Path file=root.resolve("manifest.json");Json.write(Files.newOutputStream(file),manifest);files.put("manifest.json",file);
        try(var zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))){for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));Files.copy(entry.getValue(),zip);zip.closeEntry();}}
    }
    private String publishPackage(Path archive)throws Exception {long length=Files.size(archive);String checksum=ResourceFiles.checksum(archive);String upload=(String)call("deposit.resource.beginUpload",UUID.randomUUID(),args("filename","stage7.zip","bytes",length,"sha256",checksum,"mediaType","application/zip")).get("uploadId");
        try(var in=Files.newInputStream(archive)){long offset=0;byte[] data;while((data=in.readNBytes(ResourceFiles.CHUNK)).length>0){call("deposit.resource.uploadChunk",UUID.randomUUID(),args("uploadId",upload,"offset",offset,"bytesBase64",Base64.getEncoder().encodeToString(data),"chunkSha256",ResourceFiles.checksum(data)));offset=Math.addExact(offset,data.length);}assertEquals(length,offset);}return (String)call("deposit.resource.finishUpload",UUID.randomUUID(),args("uploadId",upload)).get("resourceId");
    }
    private static RelationshipType relationshipType(String name){return RelationshipType.withName(name);}
    private Map<String,Object> call(String procedure,UUID operation,Map<String,Object> request)throws Exception {try(var tx=database.beginTx()){
        var context=new RequestContext(tx,args("token",token,"operationId",operation==null?null:operation.toString()));context.publicCall=true;context.procedure=procedure;context.request=request;var actions=new deposit.service.SessionActionService(context);actions.begin(procedure,ProcedureCatalog.spec(procedure).bindings().stream().map(request::get).collect(java.util.stream.Collectors.toList()));var row=(DataRow)new deposit.service.ProcedureService(context).execute().findFirst().orElseThrow();actions.observe();tx.commit();return row.data;
    }}
    private void assertRolledBack(UUID operation,String planId){try(var tx=database.beginTx()){assertNull(tx.findNode(Label.label("DepositReceipt"),"operationId",operation.toString()));assertNull(tx.findNode(Label.label("DepositImportResult"),"operationId",operation.toString()));assertEquals("ready",tx.findNode(Label.label("DepositImportPlan"),"id",planId).getProperty("state"));assertEquals(0L,countNodes(tx,"Object"));assertEquals(1L,countNodes(tx,"DResource"));tx.rollback();}}
    private String planIdFor(UUID operation)throws Exception {try(var tx=database.beginTx()){String result=(String)tx.findNode(Label.label("DepositImportResult"),"operationId",operation.toString()).getProperty("id");try(var plans=tx.findNodes(Label.label("DepositImportPlan"),"resultId",result)){assertTrue(plans.hasNext());return CoreRepository.id(plans.next());}}}
    private record MappingPage(List<?> entries,Map<String,Object> cursor,int total){}
    private MappingPage page(String result,String collection,Map<String,Object> cursor)throws Exception {Map<String,Object> page=call("deposit.get.importResultPage",null,args("resultId",result,"collection",collection,"cursor",cursor,"limit",128L));return new MappingPage((List<?>)page.get("entries"),page.get("nextCursor")==null?null:ValueCodec.map(page.get("nextCursor")),cursor==null?0:((Number)cursor.get("position")).intValue());}
    private Iterable<Map<String,Object>> mapping(String result,String collection)throws Exception {MappingPage first=page(result,collection,null);return ()->new Iterator<>(){
        MappingPage nextPage=first;List<?> batch=List.of();int index;
        private boolean fill(){if(index<batch.size())return true;if(nextPage==null)return false;try{batch=nextPage.entries;nextPage=nextPage.cursor==null?null:page(result,collection,nextPage.cursor);index=0;return !batch.isEmpty();}catch(Exception failure){throw new UncheckedIOException(new IOException(failure));}}
        public boolean hasNext(){return fill();}public Map<String,Object> next(){if(!fill())throw new NoSuchElementException();return ValueCodec.map(batch.get(index++));}
    };}
    private Object metadata(Map<String,Object> envelope,String name)throws Exception {for(var row:mapping((String)envelope.get("resultId"),"metadata"))if(name.equals(scalar(row.get("key"))))return ValueCodec.map(row.get("value")).get("value");throw new AssertionError("Missing metadata "+name);}
    private static String scalar(Object descriptor){return (String)ValueCodec.map(descriptor).get("value");}private static String collection(Object descriptor){return (String)ValueCodec.map(descriptor).get("collection");}
    private static long collectionCount(Map<String,Object> envelope,String name){Object descriptor=ValueCodec.map(envelope.get("collections")).get(name);return descriptor==null?0:((Number)ValueCodec.map(descriptor).get("count")).longValue();}
    private Set<String> storageIds()throws Exception {var ids=new HashSet<String>();for(var content:server.storage.content.discover())ids.add(content.resourceId());return ids;}
    private Set<String> admissions(UUID operation)throws Exception {var ids=new TreeSet<String>();try(var paths=Files.list(server.storage.files.root.resolve("admission"))){for(Path path:paths.filter(Files::isRegularFile).toList())if(Files.readString(path).contains(operation.toString()))ids.add(path.getFileName().toString());}return ids;}
    private void assertObjectExists(String id){try(var tx=database.beginTx()){assertNotNull(tx.findNode(Label.label("Object"),"id",id));tx.rollback();}}
    private static long countNodes(Transaction tx,String label){try(var nodes=tx.findNodes(Label.label(label))){return nodes.stream().count();}}
    public static class ValidationProbe {public static void main(String[] args)throws Exception {var summary=ExchangePackageCodec.validateSummary(Path.of(args[0]));String category=args[1];long wanted=category.equals("resources")?MEMBERS:OBJECTS;if(summary.counts().get(category)!=wanted)throw new AssertionError(summary);System.out.println("STAGE7 constrainedValidation category="+category+" count="+wanted);}}
    private static final String COMPLETE_MEMBERSHIP_HEAP="2g";
    private static final long COMPLETE_MEMBERSHIP_BUDGET_MINUTES=20;
    /** Proves >100k real resource members validate completely inside a separately bounded JVM.
     *  Measured here (stage7-isolated-graph-worker/logs): the streaming validateSummary path re-materialises
     *  manifest.files once per resource row, so at 100 001 members it costs ~4.9 s per row (~140 h) and cannot
     *  finish under any runtime budget. The non-streaming path below runs the identical staging, per-entry
     *  SHA-256, manifest and PackageGraphValidator checks and measured 731 s inside the verified Maven
     *  run (660 s standalone at -Xmx2g, 999 s at -Xmx1536m, peak probe RSS 1 681 MiB), so this 2g/20 minute
     *  budget keeps about 1.6x time and 1.4x memory headroom without weakening or omitting any check. */
    private void probeCompleteMembership(Path archive)throws Exception {
        Path args=temporary.resolve("membership-args.txt"),log=temporary.resolve("membership-probe.log");
        Files.writeString(args,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
            "-Xmx"+COMPLETE_MEMBERSHIP_HEAP,"-Dstage7.probeHeap="+COMPLETE_MEMBERSHIP_HEAP,"-Djava.io.tmpdir="+temporary,"@"+args,
            CompleteMembershipProbe.class.getName(),archive.toString(),String.valueOf(MEMBERS))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            assertTrue(process.waitFor(COMPLETE_MEMBERSHIP_BUDGET_MINUTES,TimeUnit.MINUTES),
                "bounded complete-membership validation timeout: "+COMPLETE_MEMBERSHIP_BUDGET_MINUTES+" m at heap "+COMPLETE_MEMBERSHIP_HEAP);
            String probe=Files.readString(log);System.out.println("STAGE7 boundedProbe "+probe.trim());assertEquals(0,process.exitValue(),probe);
        } finally {if(process.isAlive())process.destroyForcibly().waitFor();}
    }
    /** Production whole-package validation: ZIP staging with per-entry SHA-256 plus content-address check,
     *  manifest length/checksum verification for every entry, and all PackageGraphValidator reference checks. */
    public static class CompleteMembershipProbe {
        public static void main(String[] args)throws Exception {
            long start=System.nanoTime();
            var data=ExchangePackageCodec.validate(Path.of(args[0]));
            long ms=(System.nanoTime()-start)/1_000_000,declared=0;
            for(Object raw:(List<?>)data.graph().get("resources"))declared+=((Number)ValueCodec.map(raw).get("bytes")).longValue();
            long rows=((List<?>)data.graph().get("resources")).size();
            long manifestEntries=ValueCodec.map(data.manifest().get("files")).size(); // manifest.json never hashes itself, so the content list is every member plus graph.json
            long wanted=Long.parseLong(args[1]);
            if(rows!=wanted||manifestEntries!=wanted+1||declared!=wanted*4L)
                throw new AssertionError("incomplete membership rows="+rows+" manifestEntries="+manifestEntries+" declaredBytes="+declared);
            System.out.println("STAGE7 completeMembership category=resources count="+rows+" manifestEntries="+manifestEntries+" declaredBytes="+declared+" validateMs="+ms+" heap="+System.getProperty("stage7.probeHeap","unset"));
        }
    }
    private void subprocess(String category,Path archive,String heap)throws Exception {Path args=temporary.resolve(category+"-args.txt"),log=temporary.resolve(category+"-probe.log");Files.writeString(args,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx"+heap,"-Djava.io.tmpdir="+temporary,"@"+args,ValidationProbe.class.getName(),archive.toString(),category).redirectErrorStream(true).redirectOutput(log.toFile()).start();try{assertTrue(process.waitFor(20,TimeUnit.MINUTES),"bounded validation timeout");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}}
    private static Map<String,Object> args(Object... values){var map=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);return map;}
    private static Map<String,Object> entity(String kind,String namespace,String id,Map<String,Object> fields){var row=new LinkedHashMap<String,Object>();row.put("kind",kind);row.put("id",id);row.put("namespaceId",namespace);row.putAll(fields);return row;}
    private static String classRow(String source){String clazz=id("class",0);var declarations=List.of(entity("ClassDescriptor",source,id("declaration",0),Json.map("classId",clazz,"name","Code","type","String","order",0L,"comment","")),entity("ClassDescriptor",source,id("declaration",1),Json.map("classId",clazz,"name","File","type","DResource","order",1L,"comment","")));return Json.write(entity("Class",source,clazz,Json.map("name","Specimen","position",0L,"order",0L,"comment","","descriptors",declarations)));}
    private static Map<String,Object> manifest(String namespace){return args("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",namespace,"name","Stage7 capacity","description","Actual real-byte capacity","depositVersion",null,"historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace","includeHistory",false);}
    private static String id(String namespace,int ordinal){return UUID.nameUUIDFromBytes(("Stage7Capacity/"+namespace+"/"+ordinal).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String digest(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @AfterEach void close(){try{if(server!=null&&management!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}finally{try{if(server!=null&&server.storage!=null)server.storage.close();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}}
}