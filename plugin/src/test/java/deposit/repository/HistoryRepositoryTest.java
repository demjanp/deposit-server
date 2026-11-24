package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class HistoryRepositoryTest {
    @TempDir Path temporary;
    DatabaseManagementService management;GraphDatabaseAPI database;
    String namespace,descriptor,actor=UUID.randomUUID().toString();
    @BeforeEach void setup(){
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");SchemaRepository.initialize(database);try(var tx=database.beginTx()){tx.schema().awaitIndexesOnline(1,java.util.concurrent.TimeUnit.MINUTES);}
        try(var tx=database.beginTx()){
            var core=core(tx);Node root=core.create("Namespace",null,actor,Json.map("name","History","description","","sequence",0L,"historyMode","persistent"));namespace=CoreRepository.id(root);
            Node clazz=core.create("Class",namespace,actor,Json.map("name","Specimen","position",List.of(0.0,0.0)));core.attach("ClassDescriptor",clazz,actor,Json.map("name","Mass","type","Integer"));
            Node object=core.create("Object",namespace,actor,Json.map("position",List.of(0.0,0.0)));core.assign(object,clazz,actor);Node value=core.attach("Descriptor",object,actor,Json.map("name","Mass"));descriptor=CoreRepository.id(value);core.writeValue(value,"Integer",20L,actor);
            new HistoryRepository(tx,core).baseline(namespace,actor,0,"old-boot",ZonedDateTime.now(ZoneOffset.UTC));tx.commit();
        }
    }
    @AfterEach void close(){if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(management!=null)management.shutdown();}
    private CoreRepository core(Transaction tx){return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));}
    private long count(Transaction tx,String label){try(var rows=tx.execute("MATCH (n:"+label+" {namespaceId:$ns}) RETURN count(n) AS n",Map.of("ns",namespace))){return ((Number)rows.next().get("n")).longValue();}}

    @Test void importAppendPersistsIndependentOwnedRowsAndPortableMetadataWithoutAggregateJournal()throws Exception{
        String revision;
        try(var workspace=new HistoryWorkspace(temporary.resolve("scratch"),()->{},ignored->{},()->{});var tx=database.beginTx()){
            var core=core(tx);var history=new HistoryRepository(tx,core);var before=history.capture(namespace,workspace.newState());var journal=workspace.journal(before);
            core.writeValue(core.entity("Descriptor",namespace,descriptor),"Integer",30L,actor);journal.checkpoint(history.capture(namespace,workspace.newState()));
            revision=history.appendId(namespace,actor,UUID.randomUUID().toString(),1,"import","Import",journal,null,null,ZonedDateTime.now(ZoneOffset.UTC),null);
            Node node=core.entity("Revision",namespace,revision);assertFalse(node.hasProperty("journal"));assertTrue(count(tx,"RevisionTouch")>0);assertTrue(count(tx,"RevisionDependency")>0);
            Revision loaded=history.read(node);assertEquals(20L,loaded.before().get(HistoryCodec.key("Descriptor",descriptor)).get("value"));assertEquals(30L,loaded.after().get(HistoryCodec.key("Descriptor",descriptor)).get("value"));assertEquals(journal.touches,loaded.touches());assertEquals(journal.dependencies,loaded.dependencies());
            try(var records=new ExportRecords(temporary.resolve("exports"))){var portable=history.portableMetadata(node,records);assertEquals(Json.write(journal.touches),Json.write(portable.get("touches")));assertEquals(Json.write(journal.dependencies),Json.write(portable.get("dependencies")));}
            tx.commit();
        }
        try(var tx=database.beginTx()){
            var history=new HistoryRepository(tx,core(tx));var saved=history.revision(namespace,revision);assertEquals("import",saved.kind());assertEquals(Set.of("value"),saved.touches().get(HistoryCodec.key("Descriptor",descriptor)));
            try(var rows=tx.execute("EXPLAIN MATCH (d:RevisionDependency {namespaceId:$ns,revisionId:$id}) WHERE d.entityKey IS NOT NULL RETURN d.entityKey ORDER BY d.entityKey",Map.of("ns",namespace,"id",revision))){String plan=rows.getExecutionPlanDescription().toString();assertTrue(plan.contains("Index"),plan);}
        }
    }
    @Test void restoredClassEdgeSurvivesDiskCaptureAndHistoryRestore()throws Exception{
        String edgeId=UUID.randomUUID().toString(),secondEdge=UUID.randomUUID().toString();
        String restoredClass;try(var tx=database.beginTx()){try{restoredClass=CoreRepository.id(core(tx).find("Class",Map.of("namespaceId",namespace,"name","Specimen")));}finally{tx.rollback();}}
        try(var tx=database.beginTx()){
            var core=core(tx);Node source=core.entity("Class",namespace,restoredClass);
            Node object=core.createRestored("Object",namespace,actor,Json.map("position",List.of(0.0,0.0)),UUID.randomUUID().toString());core.assignRestored(object,source,actor,UUID.randomUUID().toString());
            core.createRelationshipRestored(source,source,"related",Map.of("comment","restored"),actor,edgeId);
            new HistoryRepository(tx,core).baseline(namespace,actor,0,"old-boot",ZonedDateTime.now(ZoneOffset.UTC));tx.rollback();
        }
        // Portable audit for the restored edge predates the restoring transaction and belongs to another actor.
        String historicalActor=UUID.randomUUID().toString();
        ZonedDateTime historical=ZonedDateTime.now(ZoneOffset.UTC).minusDays(1);String historicalCreated=historical.toString();
        Map<String,Object> image=Json.map("kind","ClassRelationship","id",secondEdge,"namespaceId",namespace,"sourceId",restoredClass,"targetId",restoredClass,"label","linked","comment","restore","createdAt",historicalCreated,"createdBy",historicalActor);
        try(var workspace=new HistoryWorkspace(temporary.resolve("restored-edge-scratch"),()->{},ignored->{},()->{});var tx=database.beginTx()){
            var core=core(tx);var history=new HistoryRepository(tx,core);var current=history.capture(namespace);
            var target=new TreeMap<String,Map<String,Object>>();current.forEach((key,value)->target.put(key,new LinkedHashMap<>(value)));
            target.put(HistoryCodec.key(image),image);UndoPlan plan=HistoryPlanner.restore(1,current,target,"restored-class-edge").plan();
            assertTrue(plan.conflicts().isEmpty(),plan.conflicts().toString());history.apply(namespace,actor,plan);
            Relationship restored=core.readRelationship(namespace,"ClassRelationship",secondEdge);assertNotNull(restored);
            assertEquals(restoredClass,CoreRepository.id(restored.getStartNode()));assertEquals(restoredClass,CoreRepository.id(restored.getEndNode()));
            assertEquals("linked",restored.getProperty("label"));assertEquals("restore",restored.getProperty("comment"));
            // Audit timestamps come from the preserved historical edge, not a later restore time.
            assertEquals(historical.toInstant(),((ZonedDateTime)restored.getProperty("createdAt")).toInstant());
            assertEquals(historicalActor,restored.getProperty("createdBy"));assertNotEquals(actor,restored.getProperty("createdBy"));
            Map<String,Map<String,Object>> after=history.capture(namespace,workspace.newState());
            assertEquals(target.keySet(),after.keySet());
            for(String key:target.keySet())assertEquals(HistoryCodec.scientific(target.get(key)),HistoryCodec.scientific(after.get(key)),key);
            Map<String,Object> captured=after.get(HistoryCodec.key(image));assertNotNull(captured);
            for(String field:List.of("createdAt","createdBy","updatedAt","updatedBy"))assertNotNull(captured.get(field),field);
            assertEquals(historicalCreated,captured.get("createdAt"));assertEquals(historicalActor,captured.get("createdBy"));
            assertEquals(actor,captured.get("updatedBy"));assertEquals(secondEdge,captured.get("id"));
            assertNotEquals(historicalCreated,captured.get("updatedAt"),"restoring writes a fresh update audit");
            Map<String,Map<String,Object>> replay=history.capture(namespace,workspace.newState());
            assertEquals(captured,replay.get(HistoryCodec.key(image)),"disk capture must replay the restored edge image unchanged");
            for(String field:List.of("updatedAt","updatedBy"))image.put(field,captured.get(field));
            assertEquals(target,after);tx.rollback();
        }
    }
    @Test void restoredClassEdgeWithoutHistoricalAuditStillCarriesRestoreAudit()throws Exception{
        String secondEdge=UUID.randomUUID().toString();
        String restoredClass;try(var tx=database.beginTx()){try{restoredClass=CoreRepository.id(core(tx).find("Class",Map.of("namespaceId",namespace,"name","Specimen")));}finally{tx.rollback();}}
        // A portable image may omit audit entirely; restoration must still leave a fully audited edge.
        Map<String,Object> image=Json.map("kind","ClassRelationship","id",secondEdge,"namespaceId",namespace,"sourceId",restoredClass,"targetId",restoredClass,"label","linked","comment","restore");
        try(var workspace=new HistoryWorkspace(temporary.resolve("unaudited-edge-scratch"),()->{},ignored->{},()->{});var tx=database.beginTx()){
            var core=core(tx);var history=new HistoryRepository(tx,core);var current=history.capture(namespace);
            var target=new TreeMap<String,Map<String,Object>>();current.forEach((key,value)->target.put(key,new LinkedHashMap<>(value)));
            target.put(HistoryCodec.key(image),image);UndoPlan plan=HistoryPlanner.restore(1,current,target,"unaudited-class-edge").plan();
            assertTrue(plan.conflicts().isEmpty(),plan.conflicts().toString());history.apply(namespace,actor,plan);
            Relationship restored=core.readRelationship(namespace,"ClassRelationship",secondEdge);assertNotNull(restored);
            assertEquals(secondEdge,CoreRepository.id(restored));assertEquals("linked",restored.getProperty("label"));
            for(String field:List.of("createdAt","createdBy","updatedAt","updatedBy"))assertNotNull(restored.getProperty(field),field);
            assertEquals(actor,restored.getProperty("createdBy"));assertEquals(actor,restored.getProperty("updatedBy"));
            Map<String,Map<String,Object>> after=history.capture(namespace,workspace.newState());
            Map<String,Object> captured=after.get(HistoryCodec.key(image));assertNotNull(captured);
            for(String field:List.of("createdAt","createdBy","updatedAt","updatedBy"))assertNotNull(captured.get(field),field);
            assertEquals(HistoryCodec.scientific(image),HistoryCodec.scientific(captured));tx.rollback();
        }
    }
    @Test void rollbackLeavesNoRevisionOrChildRecordsAndScratchCloses()throws Exception{
        long revisions,touches,dependencies;
        try(var tx=database.beginTx()){revisions=count(tx,"Revision");touches=count(tx,"RevisionTouch");dependencies=count(tx,"RevisionDependency");}
        try(var workspace=new HistoryWorkspace(temporary.resolve("scratch"),()->{},ignored->{},()->{});var tx=database.beginTx()){
            var core=core(tx);var history=new HistoryRepository(tx,core);var journal=workspace.journal(history.capture(namespace,workspace.newState()));core.writeValue(core.entity("Descriptor",namespace,descriptor),"Integer",77L,actor);journal.checkpoint(history.capture(namespace,workspace.newState()));history.appendId(namespace,actor,UUID.randomUUID().toString(),1,"import","Import",journal,null,null,ZonedDateTime.now(ZoneOffset.UTC),null);tx.rollback();
        }
        try(var tx=database.beginTx()){assertEquals(revisions,count(tx,"Revision"));assertEquals(touches,count(tx,"RevisionTouch"));assertEquals(dependencies,count(tx,"RevisionDependency"));assertEquals(20L,core(tx).descriptorValue(core(tx).entity("Descriptor",namespace,descriptor)));}
        try(var files=Files.list(temporary.resolve("scratch"))){assertEquals(0,files.count());}
    }
    @Test void transientRestartCleansEveryNewRecordAcrossMultipleExpiredEpochs(){
        try(var tx=database.beginTx()){
            var core=core(tx);Node root=core.namespace(namespace);root.setProperty("historyMode","transient");var history=new HistoryRepository(tx,core);
            history.baseline(namespace,actor,1,"previous-boot",ZonedDateTime.now(ZoneOffset.UTC));history.baseline(namespace,actor,2,"previous-boot",ZonedDateTime.now(ZoneOffset.UTC));root.setProperty("historyMode","disabled");
            try(var epochs=core.scanNodes("DepositHistoryEpoch",Map.of("namespaceId",namespace))){while(epochs.hasNext()){Node epoch=epochs.next();epoch.setProperty("mode","transient");epoch.setProperty("bootId","previous-boot");}}
            tx.commit();
        }
        ServerContext server=ServerContext.attach(database.databaseId().databaseId().uuid());
        try{var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
        HistoryLifecycle.recover(server);
        try(var tx=database.beginTx()){for(String label:List.of("Revision","DepositSnapshot","RevisionTouch","RevisionDependency","DepositHistoryEpoch"))assertEquals(0,count(tx,label),label);}
    }
    @Test void namespaceHistoryDeletionIncludesTouchAndDependencyRows(){
        try(var tx=database.beginTx()){new HistoryRepository(tx,core(tx)).deleteNamespace(namespace);for(String label:List.of("Revision","DepositSnapshot","RevisionTouch","RevisionDependency","DepositHistoryEpoch"))assertEquals(0,count(tx,label),label);assertNotNull(core(tx).namespace(namespace));tx.commit();}
    }
}
