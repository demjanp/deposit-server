package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ShutdownControlTest {
    @TempDir Path temporary;
    Path protectedDirectory()throws Exception{
        Path path=Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
        var owner=Files.getOwner(path);
        var runtime=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());
        if(!owner.equals(runtime)){Files.setOwner(path,runtime);owner=runtime;}
        var permissions=EnumSet.allOf(AclEntryPermission.class);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(permissions).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
        return path;
    }
    Map<String,Object> identity(Path secrets)throws Exception{
        var value=Json.read(Files.readString(secrets.resolve("deposit-control/run.json")));
        value.remove("transactions");value.remove("readers");value.remove("handlers");return value;
    }
    String request(Path secrets,Map<String,Object> identity)throws Exception{
        String id=UUID.randomUUID().toString();var value=new LinkedHashMap<>(identity);value.put("requestId",id);
        Files.writeString(secrets.resolve("deposit-control").resolve((String)identity.get("incarnation")).resolve("request-"+id+".json"),Json.write(value));return id;
    }
    Map<String,Object> ack(ShutdownControl control,Path secrets,Map<String,Object> identity,String id,String state)throws Exception{
        Path path=secrets.resolve("deposit-control").resolve((String)identity.get("incarnation")).resolve("ack-"+id+".json");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do{control.poll();if(Files.exists(path)){var value=Json.read(Files.readString(path));if(state.equals(value.get("state")))return value;}Thread.sleep(10);}while(System.nanoTime()<deadline);
        fail("Missing "+state+" acknowledgement");return Map.of();
    }
    @Test void concurrentRequestsJoinOneDrainAndTeardownIsIdempotent()throws Exception{
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);Path secrets=protectedDirectory();AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var control=new ShutdownControl(server,secrets,database,temporary,()->{calls.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}})){
            var identity=identity(secrets);String first=request(secrets,identity),second=request(secrets,identity);
            control.poll();assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,server::requireReady,"initialization cannot admit after stop");
            assertFalse(Files.exists(secrets.resolve("deposit-control").resolve((String)identity.get("incarnation")).resolve("ack-"+first+".json")),"progress waits for initializer cleanup");
            release.countDown();var completed=ack(control,secrets,identity,first,"complete");ack(control,secrets,identity,second,"complete");
            assertEquals(1,calls.get());assertEquals(0L,completed.get("transactions"));assertEquals(true,completed.get("storageClosed"));
            assertDoesNotThrow(()->server.shutdown());control.poll();assertEquals(1,calls.get());
        }finally{release.countDown();server.shutdown();ServerContext.detach(database);}
    }
    @Test void staleUnsupportedMalformedAndOversizedRequestsCannotStartDrain()throws Exception{
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);Path secrets=protectedDirectory();AtomicInteger calls=new AtomicInteger();
        try(var control=new ShutdownControl(server,secrets,database,temporary,calls::incrementAndGet)){
            var identity=identity(secrets);
            Path run=secrets.resolve("deposit-control").resolve((String)identity.get("incarnation"));
            for(String key:List.of("incarnation","databaseId","installation","pid","startMillis","version")){
                var stale=new LinkedHashMap<>(identity);stale.put(key,key.equals("version")?2:"stale");String id=UUID.randomUUID().toString();stale.put("requestId",id);Files.writeString(run.resolve("request-"+id+".json"),Json.write(stale));
            }
            Files.writeString(run.resolve("request-"+UUID.randomUUID()+".json"),"{");
            Files.writeString(run.resolve("request-"+UUID.randomUUID()+".json"),"x".repeat(ShutdownControl.MAX_BYTES+1));
            control.poll();assertEquals(0,calls.get());
            assertThrows(IllegalArgumentException.class,()->ShutdownControl.readRequest("{\"version\":1,\"version\":1}"));
            var wrongType=new LinkedHashMap<>(identity);String id=UUID.randomUUID().toString();wrongType.put("requestId",id);wrongType.put("version","1");assertFalse(ShutdownControl.validRequest(wrongType,identity,id));
        }finally{server.shutdown();ServerContext.detach(database);}
    }
    @Test void failedCleanupStaysClosedAndDuplicateRequestsReceiveFailure()throws Exception{
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);Path secrets=protectedDirectory();AtomicInteger calls=new AtomicInteger();
        try(var control=new ShutdownControl(server,secrets,database,temporary,()->{calls.incrementAndGet();throw new IllegalStateException("injected initialization cleanup failure");})){
            var identity=identity(secrets);String first=request(secrets,identity);ack(control,secrets,identity,first,"failed");
            String second=request(secrets,identity);ack(control,secrets,identity,second,"failed");assertEquals(1,calls.get());assertThrows(IllegalStateException.class,server::requireReady);
        }finally{server.shutdown();ServerContext.detach(database);}
    }
    @Test void terminalSuccessWaitsForAdmittedTransactionCompletionCallbacks()throws Exception{
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);Path secrets=protectedDirectory();
        var transaction=(org.neo4j.kernel.api.KernelTransaction)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.neo4j.kernel.api.KernelTransaction.class},(proxy,method,args)->switch(method.getName()){case "getTransactionSequenceNumber"->713L;case "getDatabaseId"->database;default->null;});
        var state=server.state(transaction);CountDownLatch callback=new CountDownLatch(1),release=new CountDownLatch(1);
        state.afterCommit.add(()->{callback.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
        Thread completion=null;
        try(var control=new ShutdownControl(server,secrets,database,temporary,()->{})){
            var identity=identity(secrets);String id=request(secrets,identity);var progress=ack(control,secrets,identity,id,"admissionClosed");assertEquals(1L,progress.get("transactions"));
            completion=Thread.ofPlatform().start(()->server.commit(transaction));assertTrue(callback.await(2,TimeUnit.SECONDS));
            control.poll();Path ack=secrets.resolve("deposit-control").resolve((String)identity.get("incarnation")).resolve("ack-"+id+".json");assertEquals("admissionClosed",Json.read(Files.readString(ack)).get("state"));
            release.countDown();completion.join(2_000);assertEquals(0L,ack(control,secrets,identity,id,"complete").get("transactions"));
        }finally{release.countDown();if(completion!=null)completion.join(2_000);server.shutdown();ServerContext.detach(database);}
    }
    @Test void unsafeAccessAndReparsePathsAreRejected()throws Exception{
        Path secrets=protectedDirectory();var acl=Files.getFileAttributeView(secrets,AclFileAttributeView.class);var owner=Files.getOwner(secrets);
        ShutdownControl.validateAcl(secrets,Set.of(owner));
        var everyone=secrets.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");var entries=new ArrayList<>(acl.getAcl());entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone).setPermissions(AclEntryPermission.READ_DATA).build());acl.setAcl(entries);
        assertThrows(java.io.IOException.class,()->ShutdownControl.validateAcl(secrets,Set.of(owner)));
        Path junction=temporary.resolve("junction");Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",junction.toString(),secrets.toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.waitFor(),output);
        try{assertThrows(java.io.IOException.class,()->ShutdownControl.validatePath(junction));}finally{Files.delete(junction);}
    }

    @Test void runtimeIdentityComesFromProcessUserNotCreatedFileOwner()throws Exception{
        Path created=Files.createFile(temporary.resolve("default-owner.tmp"));UserPrincipal defaultOwner=Files.getOwner(created);
        UserPrincipal runtime=()->"fixture-runtime-user";
        UserPrincipalLookupService lookup=new UserPrincipalLookupService(){
            @Override public UserPrincipal lookupPrincipalByName(String name)throws java.io.IOException{if(!"fixture-runtime-user".equals(name))throw new java.nio.file.attribute.UserPrincipalNotFoundException(name);return runtime;}
            @Override public GroupPrincipal lookupPrincipalByGroupName(String group)throws java.io.IOException{throw new java.nio.file.attribute.UserPrincipalNotFoundException(group);}
        };
        assertNotEquals(defaultOwner,runtime,"fixture must model the elevated-token owner mismatch");
        assertSame(runtime,ShutdownControl.runtimePrincipal("fixture-runtime-user",lookup));
        assertThrows(java.io.IOException.class,()->ShutdownControl.runtimePrincipal("missing-user",lookup));
    }

    @Test void existingRuntimeOwnerDoesNotRequireWriteOwnerPermission()throws Exception{
        Path directory=protectedDirectory(),file=Files.createFile(directory.resolve("owner-fixture.tmp"));
        var runtime=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());
        for(Path path:List.of(directory,file)){
            if(!Files.getOwner(path).equals(runtime))Files.setOwner(path,runtime);
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);var permissions=EnumSet.allOf(AclEntryPermission.class);permissions.remove(AclEntryPermission.WRITE_OWNER);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(runtime).setPermissions(permissions).build()));
            assertEquals(runtime,Files.getOwner(path));assertFalse(acl.getAcl().stream().anyMatch(entry->entry.permissions().contains(AclEntryPermission.WRITE_OWNER)));
            assertDoesNotThrow(()->ShutdownControl.ensureOwner(path,runtime));
            assertEquals(runtime,Files.getOwner(path));
        }
    }

    @Test void retainedRequestsDoNotStarveLaterRequestsAfterSixtyFourEntries()throws Exception{
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);Path secrets=protectedDirectory();
        try(var control=new ShutdownControl(server,secrets,database,temporary,()->{})){
            var identity=identity(secrets);String first=request(secrets,identity);ack(control,secrets,identity,first,"complete");
            for(int index=0;index<70;index++){String id=request(secrets,identity);var result=ack(control,secrets,identity,id,"complete");assertEquals(id,result.get("requestId"));}
        }finally{server.shutdown();ServerContext.detach(database);}
    }
}
