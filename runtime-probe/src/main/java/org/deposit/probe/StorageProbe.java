package org.deposit.probe;
import deposit.common.*;
import deposit.repository.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.gis.spatial.*;
import org.neo4j.gis.spatial.index.IndexManager;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.procedure.*;

/** Test-only reflection controls, packaged exclusively in the probe distribution. */
public class StorageProbe {
    @Context public Transaction tx;@Context public GraphDatabaseAPI db;
    private static volatile CountDownLatch pause=new CountDownLatch(0);
    public static class Row{public final Map<String,Object> data;Row(Map<String,Object> data){this.data=data;}}
    private ServerContext server(){return ServerContext.forDatabase(db.databaseId().databaseId().uuid());}
    @Procedure(name="testprobe.storage.failNextPublication",mode=Mode.READ)
    public Stream<Row> failNextPublication()throws Exception {
        var manager=server().storage;ResourceStorage delegate=manager.content;var once=new AtomicBoolean();
        var field=StorageManager.class.getDeclaredField("content");field.setAccessible(true);
        ResourceStorage proxy=(ResourceStorage)java.lang.reflect.Proxy.newProxyInstance(ResourceStorage.class.getClassLoader(),new Class[]{ResourceStorage.class},(p,method,args)->{
            if(method.getName().equals("publish")&&once.compareAndSet(false,true)){
                field.set(manager,delegate);
                throw new java.nio.file.FileSystemException("staged-copy",null,"There is not enough space on the disk (injected I/O failure)");
            }
            try{return method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });field.set(manager,proxy);return Stream.of(new Row(Map.of("armed",true)));
    }
    @Procedure(name="testprobe.storage.partialPurge",mode=Mode.READ)
    public Stream<Row> partialPurge()throws Exception {
        var manager=server().storage;ResourceStorage delegate=manager.content;var once=new AtomicBoolean();
        var field=StorageManager.class.getDeclaredField("content");field.setAccessible(true);
        ResourceStorage proxy=(ResourceStorage)java.lang.reflect.Proxy.newProxyInstance(ResourceStorage.class.getClassLoader(),new Class[]{ResourceStorage.class},(p,method,args)->{
            if(method.getName().equals("purge")&&once.compareAndSet(false,true)){
                field.set(manager,delegate);
                // Test-only partial filesystem deletion under the real committed deletion intent.
                // The external harness terminates this JVM here; production recovery uses purgeObject.
                String h=ResourceFiles.checksum(((String)args[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Files.delete(manager.files.root.resolve("ocfl").resolve(h.substring(0,3)).resolve(h.substring(3,6)).resolve(h.substring(6,9)).resolve(h).resolve("v1/inventory.json"));
                server().storagePoint("collection-partial");
            }
            try{return method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });field.set(manager,proxy);return Stream.of(new Row(Map.of("armed",true)));
    }
    @Procedure(name="testprobe.storage.arm",mode=Mode.READ)
    public Stream<Row> arm(@Name("point")String point,@Name("mode")String mode)throws Exception {
        if(!Set.of("fail","pause","clear").contains(mode))throw new IllegalArgumentException();
        pause.countDown();pause=new CountDownLatch(1);var latch=pause;var once=new AtomicBoolean();var server=server();
        var marker=server.storage.files.root.getParent().resolve("resource-pause.json");Files.deleteIfExists(marker);
        var field=ServerContext.class.getDeclaredField("storageObserver");field.setAccessible(true);
        field.set(server,(java.util.function.Consumer<String>)p->{if(!mode.equals("clear")&&p.equals(point)&&once.compareAndSet(false,true)) {
            if(mode.equals("fail"))throw new IllegalStateException("RESOURCE_FAILURE_INJECTED_"+point);
            try {Files.writeString(marker,Json.write(Json.map("point",point,"state","paused")));if(!latch.await(120,TimeUnit.SECONDS))throw new IllegalStateException("Storage pause timed out");}catch(Exception e){throw new IllegalStateException(e);}
        }});return Stream.of(new Row(Map.of("armed",point)));
    }
    @Procedure(name="testprobe.storage.inspect",mode=Mode.READ)
    public Stream<Row> inspect(@Name("namespace")String namespace) {
        var internal=(InternalTransaction)tx;var spatial=new SpatialDatabaseService(new IndexManager(db,internal.securityContext()));var layers=new ArrayList<Object>();
        for(String name:spatial.getLayerNames(tx))if(name.startsWith("deposit_geometry_"+namespace+"_")) {
            var layer=spatial.getLayer(tx,name,true);var entries=new ArrayList<Object>();for(var node:layer.getIndex().getAllIndexedNodes(tx))entries.add(Json.map("id",node.getProperty("id"),"wkt",node.getProperty("wkt"),"bbox",node.getProperty("bbox")));
            layers.add(Json.map("name",name,"count",layer.getIndex().count(tx),"entries",entries,"crs",layer.getCoordinateReferenceSystem(tx)==null?null:layer.getCoordinateReferenceSystem(tx).toWKT()));
        }return Stream.of(new Row(Json.map("layers",layers)));
    }
    @Procedure(name="testprobe.storage.rebuild",mode=Mode.WRITE)
    public Stream<Row> rebuild(@Name("namespace")String namespace){var core=new CoreRepository(tx,ZonedDateTime.now());new GeometryRepository(tx,core).rebuild(namespace);return Stream.of(new Row(Map.of("rebuilt",true)));}
    @Procedure(name="testprobe.storage.grant",mode=Mode.WRITE)
    public Stream<Row> grant(@Name("namespace")String namespace,@Name("resource")String id,@Name("actor")String actor,@Name("history")boolean history) {
        var core=new CoreRepository(tx,ZonedDateTime.now());core.lockNamespace(namespace);var r=new ResourceRepository(tx,core);r.lock(id);
        r.record("DepositExportGrant",UUID.randomUUID().toString(),Json.map("resourceId",id,"namespaceId",namespace,"actorId",actor,"history",history,"expires",r.now()+ResourceFiles.LEASE));return Stream.of(new Row(Map.of("granted",true)));
    }
    private static void importRefuses(Runnable action,String code) {
        try{action.run();throw new AssertionError("Imported resource misuse was accepted");}
        catch(RuntimeException failure){if(!failure.getMessage().contains(code))throw failure;}
    }
    /** Runs only inside a caller-rolled-back transaction in the test probe distribution. */
    @Procedure(name="testprobe.storage.importAuthority",mode=Mode.WRITE)
    public Stream<Row> importAuthority(@Name("namespace")String namespace,@Name("otherNamespace")String otherNamespace,@Name("actor")String actor,@Name("object")String objectId)throws Exception {
        var core=new CoreRepository(tx,ZonedDateTime.now());var resources=new ResourceRepository(tx,core);
        var object=core.entity("Object",namespace,objectId);
        var descriptor=core.attach("Descriptor",object,actor,Json.map("name","InternalImportProbe"));
        var other=core.create("Descriptor",otherNamespace,actor,Json.map("name","WrongNamespace"));
        var staged=resources.files.path("staging/imports/capability-"+UUID.randomUUID());Files.createDirectories(staged.getParent());Files.writeString(staged,"import authority bytes");
        try(var imported=resources.beginImport(namespace,actor)){
            String id=imported.create(staged,"authority.txt","text/plain",Files.size(staged),ResourceFiles.checksum(staged),ZonedDateTime.now());var value=Json.map("resourceId",id);
            importRefuses(()->core.writeValue(descriptor,"DResource",value,actor),"FORBIDDEN");
            importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,UUID.randomUUID().toString(),imported),"FORBIDDEN");
            importRefuses(()->core.writeImportedValue(other,"DResource",value,actor,imported),"FORBIDDEN");
            try(var separate=db.beginTx()){
                var separateCore=new CoreRepository(separate,ZonedDateTime.now());var separateDescriptor=separateCore.create("Descriptor",namespace,actor,Json.map("name","WrongTransaction"));
                importRefuses(()->separateCore.writeImportedValue(separateDescriptor,"DResource",value,actor,imported),"FORBIDDEN");
            }
            try(var different=resources.beginImport(namespace,actor)){
                importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,different),"FORBIDDEN");
            }
            importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,null),"FORBIDDEN");
            var resource=resources.resource(namespace,id);resource.setProperty("bytes",Files.size(staged)+1);
            importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,imported),"RESOURCE_CORRUPT");resource.setProperty("bytes",Files.size(staged));
            var location=resources.location(id);location.setProperty("deleting",true);
            importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,imported),"RESOURCE_CORRUPT");location.removeProperty("deleting");
            String role=core.role(actor,namespace);core.setRole(actor,namespace,"viewer");
            importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,imported),"FORBIDDEN");core.setRole(actor,namespace,role);
            if(!core.writeImportedValue(descriptor,"DResource",value,actor,imported))throw new AssertionError("Fresh imported resource was not attached");
            var second=core.attach("Descriptor",object,actor,Json.map("name","SharedImportProbe"));
            if(!core.writeImportedValue(second,"DResource",value,actor,imported))throw new AssertionError("Shared resource was not attached");
            imported.close();importRefuses(()->core.writeImportedValue(descriptor,"DResource",value,actor,imported),"FORBIDDEN");
            return Stream.of(new Row(Json.map("refusals",10L,"sharedAttached",true,"resourceId",id)));
        }finally{Files.deleteIfExists(staged);}
    }
}
