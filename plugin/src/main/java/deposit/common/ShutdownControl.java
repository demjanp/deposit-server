package deposit.common;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

/** Pinned launcher coordination. Authority is the protected Windows filesystem ACL, never a client session. */
final class ShutdownControl implements AutoCloseable {
    static final int VERSION=1, MAX_BYTES=4096;
    private final ServerContext server;
    private final Runnable awaitInitialization;
    private final Path directory,runDirectory;
    private final UserPrincipal runtimeIdentity;
    private final Set<UserPrincipal> allowedIdentities;
    private final Map<String,Object> identity;
    private final Set<String> requests=new HashSet<>(),rejectedRequests=new HashSet<>();
    private final Map<String,Map<String,Object>> publishedAcknowledgements=new HashMap<>();
    private Map<String,Object> publishedDescriptor;
    private volatile boolean closed;
    private volatile Map<String,Object> progress;
    private volatile CompletableFuture<Void> drain;
    private Thread watcher;

    static ShutdownControl start(ServerContext server,UUID database,Runnable awaitInitialization)throws IOException {
        Path config=Path.of(System.getProperty("deposit.config")).toAbsolutePath().normalize();
        Properties values=new Properties();try(var input=Files.newInputStream(config)){values.load(input);}
        String configuredSecretRoot=values.getProperty("secretRoot");
        if(configuredSecretRoot==null||configuredSecretRoot.isBlank())throw new IOException("Deposit secretRoot is required");
        Path secrets=Path.of(configuredSecretRoot).toAbsolutePath().normalize();
        ShutdownControl control=new ShutdownControl(server,secrets,database,config.getParent().getParent(),awaitInitialization);
        control.watcher=Thread.ofVirtual().name("deposit-shutdown-control").start(control::watch);
        return control;
    }

    ShutdownControl(ServerContext server,Path secrets,UUID database,Path installation,Runnable awaitInitialization)throws IOException {
        this.server=server;this.awaitInitialization=awaitInitialization;
        validatePath(secrets);
        String processUser=ProcessHandle.current().info().user().orElseThrow(()->new IOException("Cannot establish Deposit runtime OS identity"));
        runtimeIdentity=runtimePrincipal(processUser,FileSystems.getDefault().getUserPrincipalLookupService());
        allowedIdentities=Set.copyOf(new HashSet<>(List.of(Files.getOwner(secrets,LinkOption.NOFOLLOW_LINKS),runtimeIdentity)));
        validateAcl(secrets,allowedIdentities);
        directory=secrets.resolve("deposit-control");
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(directory);ensureOwner(directory,runtimeIdentity);}
        validatePath(directory);validateAcl(directory,allowedIdentities);
        String incarnation=UUID.randomUUID().toString();runDirectory=directory.resolve(incarnation);Files.createDirectory(runDirectory);ensureOwner(runDirectory,runtimeIdentity);validateAcl(runDirectory,allowedIdentities);
        identity=Map.of("version",VERSION,"installation",installation.toRealPath().toString(),"databaseId",database.toString(),
            "pid",ProcessHandle.current().pid(),"startMillis",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),"incarnation",incarnation);
        publishDescriptor();
    }

    static UserPrincipal runtimePrincipal(String processUser,UserPrincipalLookupService lookup)throws IOException {
        if(processUser==null||processUser.isBlank())throw new IOException("Cannot establish Deposit runtime OS identity");
        try{return lookup.lookupPrincipalByName(processUser);}
        catch(UserPrincipalNotFoundException e){throw new IOException("Cannot resolve Deposit runtime OS identity",e);}
    }

    static void validatePath(Path path)throws IOException {
        for(Path current=path.toAbsolutePath().normalize();current!=null;current=current.getParent()){
            BasicFileAttributes attrs=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attrs.isSymbolicLink()||attrs.isOther()||!attrs.isDirectory())throw new IOException("Unsafe shutdown control directory");
        }
        if(!path.toAbsolutePath().normalize().equals(path.toRealPath()))throw new IOException("Shutdown control path traverses a reparse point");
    }
    static void validateAcl(Path path,Set<UserPrincipal> allowed)throws IOException {
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(view==null||!allowed.contains(view.getOwner()))throw new IOException("Shutdown control requires a protected Windows ACL");
        for(AclEntry entry:view.getAcl())if(entry.type()==AclEntryType.ALLOW&&!allowed.contains(entry.principal()))throw new IOException("Shutdown control ACL grants another identity access");
    }
    static void ensureOwner(Path path,UserPrincipal expected)throws IOException {
        UserPrincipal current=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS);
        if(!current.equals(expected))Files.setOwner(path,expected);
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(expected))throw new IOException("Cannot establish Deposit runtime ownership");
    }
    private void watch(){
        while(!closed){
            try{poll();Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Shutdown control request rejected",e);try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}
        }
    }
    synchronized void poll()throws IOException {
        try(var files=Files.newDirectoryStream(runDirectory,"request-*.json")){
            int count=0;
            for(Path path:files){
                String name=path.getFileName().toString();String requestId=name.substring(8,name.length()-5);
                if(!validId(requestId)||requests.contains(requestId)||rejectedRequests.contains(requestId))continue;
                if(++count>64)break;
                try{
                    BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!attrs.isRegularFile()||attrs.isSymbolicLink()||attrs.size()>MAX_BYTES){rejectedRequests.add(requestId);continue;}
                    Map<String,Object> request=readRequest(Files.readString(path));
                    if(!validRequest(request,identity,requestId)){rejectedRequests.add(requestId);continue;}
                    requests.add(requestId);
                    if(drain==null)drain=CompletableFuture.runAsync(this::drain,command->Thread.ofVirtual().name("deposit-pre-stop-drain").start(command));
                }catch(RuntimeException ignored){rejectedRequests.add(requestId);/* Malformed requests have no authority. */}
            }
        }
        publishDescriptor();
        Map<String,Object> status=progress;
        if(status!=null)for(String requestId:requests){if(status.equals(publishedAcknowledgements.get(requestId)))continue;var ack=new LinkedHashMap<>(identity);ack.put("requestId",requestId);ack.putAll(status);writeAtomic(runDirectory.resolve("ack-"+requestId+".json"),ack);publishedAcknowledgements.put(requestId,status);}
    }
    static boolean validId(String value){try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
    @SuppressWarnings("unchecked") static Map<String,Object> readRequest(String value){
        try{return new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS).readValue(value,Map.class);}
        catch(IOException e){throw new IllegalArgumentException("Malformed shutdown request",e);}
    }
    static boolean validRequest(Map<String,Object> request,Map<String,Object> identity,String requestId){
        if(request.size()!=identity.size()+1||!requestId.equals(request.get("requestId")))return false;
        for(var entry:identity.entrySet()){
            Object actual=request.get(entry.getKey()),expected=entry.getValue();
            if(expected instanceof Number number){if(!(actual instanceof Long||actual instanceof Integer)||((Number)actual).longValue()!=number.longValue())return false;}
            else if(!expected.equals(actual))return false;
        }
        return true;
    }
    private void drain(){
        try{
            Map<String,Object> admitted=server.shutdownCounts();
            server.closeAdmission();awaitInitialization.run();
            var stopping=new LinkedHashMap<>(admitted);stopping.put("state","admissionClosed");progress=Map.copyOf(stopping);
            server.shutdown();
            var complete=new LinkedHashMap<>(server.shutdownCounts());complete.put("state","complete");complete.put("storageClosed",server.storage==null);progress=Map.copyOf(complete);
        }catch(Throwable failure){progress=Map.of("state","failed","error","Deposit cleanup did not complete");throw new CompletionException(failure);}
    }
    private void publishDescriptor()throws IOException{var descriptor=new LinkedHashMap<>(identity);descriptor.putAll(server.shutdownCounts());if(!descriptor.equals(publishedDescriptor)){writeAtomic(directory.resolve("run.json"),descriptor);publishedDescriptor=descriptor;}}
    private void writeAtomic(Path target,Map<String,Object> value)throws IOException {
        Path temporary=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".partial");
        try{Files.writeString(temporary,Json.write(value),StandardOpenOption.CREATE_NEW);ensureOwner(temporary,runtimeIdentity);validateAcl(temporary,allowedIdentities);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    @Override public void close(){closed=true;if(watcher!=null)watcher.interrupt();}
}
