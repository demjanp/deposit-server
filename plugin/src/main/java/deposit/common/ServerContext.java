package deposit.common;

import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.query.TransactionExecutionMonitor;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import deposit.repository.SchemaRepository;

/** Per-database state. No transaction or native entity is retained in a session. */
public final class ServerContext implements TransactionExecutionMonitor, org.neo4j.graphdb.event.TransactionEventListener<Void> {
    private static final System.Logger LOG=System.getLogger(ServerContext.class.getName());
    private static final ConcurrentMap<UUID,ServerContext> DATABASES=new ConcurrentHashMap<>();
    public static ServerContext forDatabase(UUID id) {
        ServerContext value=DATABASES.get(id);
        if(value==null) throw new IllegalStateException("Deposit database lifecycle unavailable");
        return value;
    }
    public static ServerContext attach(UUID id) {var value=new ServerContext(id); DATABASES.put(id,value);return value;}
    public static void detach(UUID id) {DATABASES.remove(id);}
    private final UUID databaseId;
    private ServerContext(UUID databaseId) {this.databaseId=databaseId;}
    private final ConcurrentMap<Long,TxState> transactions=new ConcurrentHashMap<>();
    private record ActiveRequest(String actor,TxState state) {}
    private final ConcurrentMap<String,ActiveRequest> requests=new ConcurrentHashMap<>();
    public void track(RequestContext c) {
        ActiveRequest request=new ActiveRequest(c.actorId,c.state),old=requests.putIfAbsent(c.requestId,request);
        if(old!=null&&!old.equals(request))c.fail("VALIDATION_FAILED","requestId is already active");
        c.state.requestIds.add(c.requestId);
    }
    public boolean cancel(String requestId,String actor,boolean administrator) {
        ActiveRequest request=requests.get(requestId);if(request==null)return false;
        if(!administrator&&!request.actor().equals(actor))throw ExceptionHandler.fail("FORBIDDEN","Cannot cancel another actor's request");
        request.state().kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);return true;
    }
    public List<Map<String,Object>> activeRequests(String actor,boolean administrator,TxState caller) {
        return requests.entrySet().stream().filter(e->e.getValue().state()!=caller&&(administrator||e.getValue().actor().equals(actor)))
            .map(e->Json.map("requestId",e.getKey(),"actorId",e.getValue().actor(),"namespaceId",e.getValue().state().namespace)).toList();
    }
    public boolean cancelSession(String tokenHash,TxState caller) {
        boolean cancelled=false;
        for(var request:requests.values())if(request.state()!=caller&&Objects.equals(tokenHash,request.state().tokenHash)){
            request.state().kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);cancelled=true;
        }
        return cancelled;
    }
    public final ConcurrentMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,java.util.concurrent.atomic.AtomicInteger> activeReaders=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Failures> failures=new ConcurrentHashMap<>();
    private final Semaphore serverLock=new Semaphore(1,true);
    private final AuthorizationGuard authorization = new AuthorizationGuard();
    private volatile java.util.function.LongSupplier sessionClock=System::currentTimeMillis;
    public long sessionTime() {return sessionClock.getAsLong();}
    public final byte[] cursorKey=randomBytes(32);
    public final String runId=UUID.randomUUID().toString();
    private volatile long revision;
    private volatile boolean initialized;
    private final Object lifecycleLock=new Object();
    private volatile boolean stopping;
    private final Object shutdownMonitor=new Object();
    private boolean shutdownRunning,shutdownComplete;
    private final Object drainMonitor=new Object();
    private ResourceDownloadServer downloads;
    public volatile String downloadPublicBaseUrl;
    private GraphDatabaseAPI database;
    public volatile StorageManager storage;
    private volatile java.util.function.Consumer<String> storageObserver=point->{};
    public void storagePoint(String point){storageObserver.accept(point);}
    public GraphDatabaseAPI database() {return database;}
    private final ConcurrentMap<String,Semaphore> storageLocks=new ConcurrentHashMap<>();
    public void holdStorage(TxState state,String key) {
        if(!state.storageKeys.add(key))return;
        Semaphore lock=storageLocks.computeIfAbsent(key,k->new Semaphore(1,true));
        try {while(!lock.tryAcquire(100,TimeUnit.MILLISECONDS))state.kernel.assertOpen();state.release.add(lock::release);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw ExceptionHandler.fail("CANCELLED","Storage wait interrupted");}
    }
    private final CompletableFuture<Void> readiness=new CompletableFuture<>();
    private String bootstrapHash;
    private Path secretPath;
    private Path secretDirectory;
    private boolean inheritedSecretAcl;
    public static byte[] randomBytes(int count) {byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return bytes;}
    public static String randomToken() {return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));}
    public TxState state(KernelTransaction tx) {return transactions.computeIfAbsent(tx.getTransactionSequenceNumber(),key->new TxState(tx));}
    /** Wait for initialization, then atomically admit this procedure transaction against shutdown. */
    public TxState admit(KernelTransaction tx) {
        requireReady();
        synchronized(lifecycleLock) {
            if(stopping||!initialized)throw new IllegalStateException("Deposit is shutting down");
            return transactions.computeIfAbsent(tx.getTransactionSequenceNumber(),key->new TxState(tx));
        }
    }
    @Override public void start(KernelTransaction tx) {}
    @Override public void commit(KernelTransaction tx) {complete(tx,true);}
    @Override public void rollback(KernelTransaction tx,Throwable cause) {complete(tx,false);}
    private void complete(KernelTransaction tx,boolean committed) {
        if(!databaseId.equals(tx.getDatabaseId()))return;
        TxState state=transactions.get(tx.getTransactionSequenceNumber());
        if(state==null) return;
        if(!state.completing.compareAndSet(false,true))return;
        try {
            if(committed) {
                if(state.serverChanged) revision++;
                for(Runnable callback:state.afterCommit) try {callback.run();}catch(RuntimeException ignored) {/* Durable jobs are retried by maintenance. */}
            }
        } finally {
            Throwable cleanupFailure=null;
            for(int i=state.release.size()-1;i>=0;i--)try{state.release.get(i).run();}catch(Throwable failure){cleanupFailure=recordCleanupFailure(cleanupFailure,failure);}
            try{for(String requestId:state.requestIds)requests.computeIfPresent(requestId,(key,value)->value.state()==state?null:value);}catch(Throwable failure){cleanupFailure=recordCleanupFailure(cleanupFailure,failure);}
            try{if(state.serverLocked)serverLock.release();}catch(Throwable failure){cleanupFailure=recordCleanupFailure(cleanupFailure,failure);}
            try{authorization.release(state);}catch(Throwable failure){cleanupFailure=recordCleanupFailure(cleanupFailure,failure);}
            if(cleanupFailure==null)transactions.remove(tx.getTransactionSequenceNumber(),state);
            else {state.completionFailure=cleanupFailure;LOG.log(System.Logger.Level.ERROR,"Transaction cleanup failed; keeping storage drain blocked",cleanupFailure);}
            synchronized(drainMonitor){drainMonitor.notifyAll();}
        }
    }
    private static Throwable recordCleanupFailure(Throwable prior,Throwable failure){if(prior==null)return failure;if(prior!=failure)try{prior.addSuppressed(failure);}catch(Throwable ignored){}return prior;}
    public void guard(TxState state, boolean exclusive) {
        if(exclusive && state.mutation) throw ExceptionHandler.fail("VALIDATION_FAILED", "Authorization changes must be the transaction's first mutation");
        authorization.acquire(state, exclusive);
    }
    public void revokeUser(String actor) { sessions.entrySet().removeIf(e -> e.getValue().actor.equals(actor)); }
    public void demoteUser(String actor) { sessions.values().stream().filter(s -> s.actor.equals(actor)).forEach(s -> s.administrative=false); }
    public void clearNamespace(String namespace) {sessions.values().forEach(s -> { synchronized(s) { if(namespace.equals(s.namespace)) s.namespace=null; } });}
    @Override public Void beforeCommit(org.neo4j.graphdb.event.TransactionData data, Transaction tx, GraphDatabaseService db) {
        var internal=(org.neo4j.kernel.impl.coreapi.InternalTransaction)tx;
        TxState state=transactions.get(internal.kernelTransaction().getTransactionSequenceNumber());
        if(state==null||state.tokenHash==null)return null;
        Session session=sessions.get(state.tokenHash);
        if(session==null)throw ExceptionHandler.fail("AUTH_FAILED","Session revoked before commit");
        synchronized(session) {
            long now=sessionTime();
            if(now-session.touched>=1_800_000L||now-session.created>=28_800_000L)throw ExceptionHandler.fail("SESSION_EXPIRED","Session expired before commit");
        }
        Node user=tx.findNode(Label.label("User"),"id",session.actor);
        if(user==null||(!state.selfAccountChange&&!Boolean.TRUE.equals(user.getProperty("enabled",false))))throw ExceptionHandler.fail("AUTH_FAILED","Account unavailable before commit");
        if(state.privileged&&!state.selfAccountChange&&!"administrator".equals(user.getProperty("serverRole","user")))throw ExceptionHandler.fail("FORBIDDEN","Administrator required before commit");
        return null;
    }
    @Override public void afterCommit(org.neo4j.graphdb.event.TransactionData data,Void state,GraphDatabaseService db) {}
    @Override public void afterRollback(org.neo4j.graphdb.event.TransactionData data,Void state,GraphDatabaseService db) {}
    public void lockServer(TxState state) {
        if(state.serverLocked) return;
        acquire(state.kernel);state.serverLocked=true;
    }
    private void acquire(KernelTransaction tx) {
        try {while(!serverLock.tryAcquire(100,TimeUnit.MILLISECONDS)) tx.assertOpen();}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw ExceptionHandler.fail("CANCELLED","Request interrupted");}
    }
    public long revision(TxState state) {
        if(state.serverLocked) return revision+(state.serverChanged?1:0);
        acquire(state.kernel);try {return revision;} finally {serverLock.release();}
    }
    public void initialize(GraphDatabaseAPI db) {
        synchronized(lifecycleLock){if(initialized)return;if(stopping)throw new IllegalStateException("Deposit is shutting down");database=db;}
        SchemaRepository.initialize(db);
        try {
            Path configPath=Path.of(System.getProperty("deposit.config")).toAbsolutePath();
            Properties configuration=new Properties();try(var input=Files.newInputStream(configPath)){configuration.load(input);}
            inheritedSecretAcl=configuration.containsKey("secretRoot");
            secretDirectory=inheritedSecretAcl?Path.of(configuration.getProperty("secretRoot")).toAbsolutePath():configPath.getParent();
            if(!Files.isDirectory(secretDirectory)||Files.isSymbolicLink(secretDirectory)||!Files.isWritable(secretDirectory))throw new IllegalStateException("Deposit secret directory is unavailable");
            secretPath=secretDirectory.resolve("deposit-bootstrap.secret");
            Path cursorPath=secretDirectory.resolve("deposit-"+db.databaseId().databaseId().uuid()+"-cursor.key");
            byte[] persisted=Base64.getUrlDecoder().decode(secret(cursorPath,inheritedSecretAcl));
            System.arraycopy(persisted,0,cursorKey,0,cursorKey.length);
            boolean bootstrapped;
            try(var tx=db.beginTx()) {bootstrapped=tx.findNode(Label.label("DepositInitialization"),"id","bootstrap")!=null;tx.commit();}
            if(!bootstrapped) {
                bootstrapHash=Json.hash(secret(secretPath,inheritedSecretAcl));
            }
            storage=new StorageManager(this,db);HistoryLifecycle.recover(this);storage.recover();
            synchronized(lifecycleLock){
                if(stopping)throw new IllegalStateException("Deposit stopped during initialization");
                storage.start();downloads=new ResourceDownloadServer(this,db);downloads.start();downloadPublicBaseUrl=downloads.publicBaseUrl();
                initialized=true;readiness.complete(null);
            }
        } catch(Exception e) {
            ResourceDownloadServer currentDownloads=downloads;
            boolean downloadsClosed=currentDownloads==null;
            if(currentDownloads!=null)try{currentDownloads.close();downloads=null;downloadsClosed=true;}catch(Exception close){e.addSuppressed(close);}
            StorageManager currentStorage=storage;
            if(downloadsClosed&&currentStorage!=null)try{currentStorage.close();storage=null;}catch(Exception close){e.addSuppressed(close);}
            throw new IllegalStateException("Cannot initialize Deposit",e);
        }
    }
    /** Close the request-admission boundary before Neo4j begins stopping its databases. */
    public void beginStop(){
        synchronized(lifecycleLock){
            stopping=true;
            if(!readiness.isDone())readiness.completeExceptionally(new IllegalStateException("Deposit is shutting down"));
        }
    }
    public void shutdown(){shutdown(30,TimeUnit.SECONDS);}
    void shutdown(long timeout,TimeUnit unit){
        beginStop();
        synchronized(shutdownMonitor){
            while(shutdownRunning)try{shutdownMonitor.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while waiting for Deposit shutdown",e);}
            if(shutdownComplete)return;
            shutdownRunning=true;
        }
        try {
            ResourceDownloadServer currentDownloads=downloads;
            if(currentDownloads!=null)currentDownloads.stopAccepting();
            boolean drained=awaitTransactions(timeout,unit);
            if(!drained){
                for(TxState state:transactions.values())try{state.kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);}catch(RuntimeException ignored){}
                drained=awaitTransactions(timeout,unit);
            }
            if(!drained){
                Throwable cleanupFailure=transactions.values().stream().map(state->state.completionFailure).filter(Objects::nonNull).findFirst().orElse(null);
                throw new IllegalStateException("Deposit transactions did not drain; storage remains open",cleanupFailure);
            }
            if(currentDownloads!=null){currentDownloads.close();downloads=null;}
            StorageManager currentStorage=storage;if(currentStorage!=null){currentStorage.close();storage=null;}
            synchronized(shutdownMonitor){shutdownComplete=true;}
        } finally {
            synchronized(shutdownMonitor){shutdownRunning=false;shutdownMonitor.notifyAll();}
        }
    }
    private boolean awaitTransactions(long timeout,TimeUnit unit){
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        synchronized(drainMonitor){
            while(!transactions.isEmpty()){
                long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
                try{TimeUnit.NANOSECONDS.timedWait(drainMonitor,remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            }
        }
        return true;
    }
    public AutoCloseable pinResource(String resourceId){
        var count=activeReaders.compute(resourceId,(key,current)->{if(current==null)current=new java.util.concurrent.atomic.AtomicInteger();current.incrementAndGet();return current;});
        java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        return ()->{if(closed.compareAndSet(false,true))activeReaders.computeIfPresent(resourceId,(key,current)->current.decrementAndGet()==0?null:current);};
    }
    public boolean hasReaderPin(String resourceId){var value=activeReaders.get(resourceId);return value!=null&&value.get()>0;}
    private static String secret(Path path,boolean inheritDirectoryAcl) throws java.io.IOException {
        if(!Files.exists(path)) {
            Path temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".partial");
            Files.createFile(temporary);
            if(!inheritDirectoryAcl) {
                var acl=Files.getFileAttributeView(temporary,AclFileAttributeView.class);
                if(acl==null)throw new IllegalStateException("Deposit secrets require Windows owner ACL");
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            Files.writeString(temporary,randomToken());Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
        }
        String value=Files.readString(path).trim();if(!value.matches("[A-Za-z0-9_-]{43}"))throw new IllegalStateException("Invalid persisted Deposit secret");return value;
    }
    public void initializationFailed(Throwable failure) {readiness.completeExceptionally(failure);}
    public void requireReady() {
        synchronized(lifecycleLock) {
            if(stopping)throw new IllegalStateException("Deposit is shutting down");
        }
        try {readiness.get(60,TimeUnit.SECONDS);}
        catch(Exception e) {throw new IllegalStateException("Deposit core initialization failed",e);}
        synchronized(lifecycleLock) {
            if(stopping||!initialized)throw new IllegalStateException("Deposit is shutting down");
        }
    }
    public boolean acceptsBootstrap(String secret) {return bootstrapHash!=null && MessageDigest.isEqual(bootstrapHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),Json.hash(secret).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    public void consumeBootstrap() {
        bootstrapHash=null;
        try {Files.deleteIfExists(secretPath);} catch(java.io.IOException ignored) {/* Durable initialization still prevents reuse. */}
    }
    public Session authenticate(RequestContext c,String principal) {
        return authenticate(c,principal,true);
    }
    public long committedRevision() {return revision;}
    public Session authenticate(RequestContext c,String principal,boolean protect) {
        if(protect)guard(c.state, false);
        if(c.token==null || c.token.isEmpty()) {c.fail("AUTH_REQUIRED","Session token required");}
        var session=sessions.get(Json.hash(c.token));
        if(session==null || session.transport!=Transport.BOLT || !Objects.equals(session.principal,principal)) {c.fail("AUTH_FAILED","Invalid session");}
        synchronized(session) {
            long now=sessionTime();
            if(now-session.touched>=1_800_000L || now-session.created>=28_800_000L) {sessions.remove(Json.hash(c.token));c.fail("SESSION_EXPIRED","Session expired");}
            session.touched=now;
        }
        if(c.state.tokenHash!=null && !c.state.tokenHash.equals(Json.hash(c.token))) c.fail("VALIDATION_FAILED","Cannot mix sessions in a transaction");
        c.state.tokenHash=Json.hash(c.token);
        c.session=session;c.actorId=session.actor;
        track(c);
        return session;
    }
    /** Browser validation is deliberately separate from Bolt principal authentication. */
    public Session authenticateBrowser(RequestContext c) {
        guard(c.state,false);
        if(c.token==null||c.token.isEmpty())c.fail("AUTH_REQUIRED","Browser session required");
        String hash=Json.hash(c.token);Session session=sessions.get(hash);
        if(session==null||session.transport!=Transport.BROWSER)c.fail("AUTH_FAILED","Invalid browser session");
        synchronized(session){long now=sessionTime();if(now-session.touched>=1_800_000L||now-session.created>=28_800_000L){sessions.remove(hash,session);c.fail("SESSION_EXPIRED","Browser session expired");}}
        if(c.state.tokenHash!=null&&!c.state.tokenHash.equals(hash))c.fail("VALIDATION_FAILED","Cannot mix sessions in a transaction");
        c.state.tokenHash=hash;c.session=session;c.actorId=session.actor;track(c);return session;
    }
    public boolean touchBrowserSession(String token,Session expected) {
        String hash=Json.hash(token);if(sessions.get(hash)!=expected||expected.transport!=Transport.BROWSER)return false;
        synchronized(expected){long now=sessionTime();if(sessions.get(hash)!=expected||now-expected.touched>=1_800_000L||now-expected.created>=28_800_000L){sessions.remove(hash,expected);return false;}expected.touched=now;return true;}
    }
    public enum Transport { BOLT, BROWSER }
    public void checkLoginDelay(String username,String origin,KernelTransaction tx) {
        long delay=Math.max(delay("user:"+username),delay("origin:"+origin));
        long until=System.currentTimeMillis()+delay;
        while(System.currentTimeMillis()<until) {
            tx.assertOpen();try {Thread.sleep(Math.min(100,until-System.currentTimeMillis()));}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw ExceptionHandler.fail("CANCELLED","Login interrupted");}
        }
    }
    private long delay(String key) {var f=failures.get(key);return f==null?0:f.delay();}
    public void loginFailed(String username,String origin) {for(String key:List.of("user:"+username,"origin:"+origin)) failures.computeIfAbsent(key,k->new Failures()).add();}
    private static final class Failures {
        private final Deque<Long> times=new ArrayDeque<>();
        synchronized void add() {prune();times.addLast(System.currentTimeMillis());}
        synchronized long delay() {prune();return times.size()<5?0:Math.min(5000,250L << Math.min(5,times.size()-5));}
        void prune() {while(!times.isEmpty() && times.getFirst()<System.currentTimeMillis()-300_000) times.removeFirst();}
    }
    public static final class Session {
        public final String actor,principal; public final long created;public final Transport transport;public volatile String csrfToken;
        public long touched; public volatile String namespace;
        public volatile boolean administrative;
        public final Map<String,Long> baselines=new HashMap<>();
        public long observationGeneration;
        public volatile String preparedAction, lastOperation, lastScope;
        public TxState mutationOwner;
        public Session(String actor,String principal,long created) {this(actor,principal,created,Transport.BOLT);}
        public Session(String actor,String principal,long created,Transport transport) {this.actor=actor;this.principal=principal;this.created=created;this.touched=created;this.transport=transport;}
    }
    public static final class TxState {
        public final KernelTransaction kernel;
        public String tokenHash,namespace;
        public boolean observationsCaptured;
        public long observationGeneration;
        public final Map<String,Long> observations=new HashMap<>();
        public boolean scoped,selection,mutation,serverLocked,serverChanged,administrative,selfAccountChange,privileged,auditRegistered;
        private final java.util.concurrent.atomic.AtomicBoolean completing=new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Throwable completionFailure;
        public final List<Runnable> afterCommit=new ArrayList<>();
        public final List<Runnable> release=new ArrayList<>();
        public final Set<String> storageKeys=new HashSet<>();
        public final Map<String,org.neo4j.gis.spatial.EditableLayer> geometryLayers=new HashMap<>();
        public final Set<String> requestIds=ConcurrentHashMap.newKeySet();
        TxState(KernelTransaction kernel) {this.kernel=kernel;}
    }
}
