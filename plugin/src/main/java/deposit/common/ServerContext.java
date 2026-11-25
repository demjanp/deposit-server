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
import deposit.repository.OperationRepository;
import deposit.repository.OperationRepository.Attempt;
import deposit.repository.OperationRepository.Key;
import org.neo4j.kernel.api.net.NetworkConnectionTracker;

/** Per-database state. No transaction or native entity is retained in a session. */
public final class ServerContext implements TransactionExecutionMonitor, org.neo4j.graphdb.event.TransactionEventListener<Void> {
    private static final ConcurrentMap<UUID,ServerContext> DATABASES=new ConcurrentHashMap<>();
    public static ServerContext forDatabase(UUID id) {
        ServerContext value=DATABASES.get(id);
        if(value==null) throw new IllegalStateException("Deposit database lifecycle unavailable");
        return value;
    }
    public static ServerContext attach(UUID id) {
        var value=new ServerContext(id);
        if(DATABASES.putIfAbsent(id,value)!=null)throw new IllegalStateException("Deposit database lifecycle already attached");
        return value;
    }
    public static void detach(UUID id) {DATABASES.remove(id);}
    private final UUID databaseId;
    private final TransactionLifecycle transactionLifecycle;
    private ServerContext(UUID databaseId) {this.databaseId=databaseId;this.transactionLifecycle=new TransactionLifecycle(this,databaseId,lifecycleLock);}
    private final SessionRequestRegistry sessionsAndRequests=new SessionRequestRegistry();
    public record OperationBinding(String session,String connection,Map<String,Object> selectors) {
        public OperationBinding {
            selectors=Collections.unmodifiableMap(new LinkedHashMap<>(selectors));
        }
    }
    private record ActiveOperation(OperationBinding binding,TxState worker) {}
    private final ConcurrentMap<Key,ActiveOperation> activeOperations=new ConcurrentHashMap<>();
    final Map<String, UploadBinding.Writer> stagingWriters = new ConcurrentHashMap<>();
    public void registerSession(String tokenHash, Session session) { sessionsAndRequests.register(tokenHash,session); }
    public Session session(String tokenHash) { return sessionsAndRequests.session(tokenHash); }
    public boolean removeSession(String tokenHash) { return sessionsAndRequests.removeSession(tokenHash); }
    public boolean removeSession(String tokenHash, Session expected) { return sessionsAndRequests.removeSession(tokenHash,expected); }
    void completeRequests(TxState state) { sessionsAndRequests.completeRequests(state); }
    public boolean cancel(String requestId,String actor,boolean administrator) { return sessionsAndRequests.cancel(requestId,actor,administrator); }
    public List<Map<String,Object>> activeRequests(String actor,boolean administrator,TxState caller) { return sessionsAndRequests.activeRequests(actor,administrator,caller); }
    public boolean cancelSession(String tokenHash,TxState caller) { return sessionsAndRequests.cancelSession(tokenHash,caller); }
    private final ResourceReaderPins readerPins=new ResourceReaderPins();
    private final Object lifecycleLock=new Object();
    public long sessionTime() {return sessionsAndRequests.sessionTime();}
    void sessionClockForTesting(java.util.function.LongSupplier clock) { sessionsAndRequests.clock(clock); }
    public final byte[] cursorKey=randomBytes(32);
    public final String runId=UUID.randomUUID().toString();
    private volatile boolean initialized;
    private volatile boolean stopping;
    private final Object shutdownMonitor=new Object();
    private boolean shutdownRunning,shutdownComplete;
    private volatile ResourceDownloadServer downloads;
    public volatile String downloadPublicBaseUrl;
    private GraphDatabaseAPI database;
    public volatile StorageManager storage;
    private volatile java.util.function.Consumer<String> storageObserver=point->{};
    public void storagePoint(String point){storageObserver.accept(point);}
    void storageObserverForTesting(java.util.function.Consumer<String> observer){storageObserver=observer;}
    public GraphDatabaseAPI database() {return database;}
    public TxState activeTransaction(long sequence) { return transactionLifecycle.find(sequence); }
    public void registerOperation(TxState state,Attempt attempt,OperationBinding binding) {
        storage.operations.register(attempt);
        state.pendingRegistration=attempt.key();
        state.pendingRegistrationBinding=binding;
        if(binding!=null)activeOperations.put(attempt.key(),new ActiveOperation(binding,null));
    }
    public OperationBinding operationBinding(Key key) {
        var entry=activeOperations.get(key);
        return entry==null?null:entry.binding();
    }
    public boolean cancelRegistration(TxState state,Key key) {
        var binding=operationBinding(key);
        if(!storage.operations.stageRegistrationCancellation(key))return false;
        state.pendingRegistration=key;
        state.pendingRegistrationBinding=binding;
        state.registrationCancellation=true;
        return true;
    }
    public TxState operationWorker(Key key) {
        var entry=activeOperations.get(key);
        return entry==null?null:entry.worker();
    }
    void forgetOperationBinding(Key key,OperationBinding binding) {
        activeOperations.computeIfPresent(key,(ignored,entry)->
            entry.binding()==binding&&entry.worker()==null?null:entry);
    }
    void completeOperation(TxState state) {
        activeOperations.computeIfPresent(state.operationKey(),(key,entry)->entry.worker()==state?null:entry);
    }
    public void lockAttempt(KernelTransaction tx,Key key) {
        transactionLifecycle.lockCoordination(tx,"operation:"+key.actor()+"/"+key.scope()+"/"+key.id());
    }
    public boolean connectionLive(String connection) {
        return connection != null && database.getDependencyResolver()
            .resolveDependency(NetworkConnectionTracker.class).get(connection) != null;
    }
    public boolean sessionLive(String hash) { return sessionsAndRequests.session(hash) != null; }
    void checkRequestLiveness() {
        long now = sessionTime();
        for (TxState state : transactionLifecycle.activeTransactions()) {
            if(state.completing.get())continue;
            if (state.operationClaimed && (!sessionLive(state.tokenHash) || !connectionLive(state.operationConnection())))
                state.signalOperationCancellation();
            if (now >= state.tableReadDeadline) {
                state.kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);
                if(state.tableReadConnection!=null){
                    var connection=database.getDependencyResolver().resolveDependency(NetworkConnectionTracker.class).get(state.tableReadConnection);
                    if(connection!=null)connection.close();
                }
            }
        }
    }
    void attachDownloadsForTesting(ResourceDownloadServer server) { downloads=server; }
    public void requireResourceLinks() {
        ResourceDownloadServer current = downloads;
        if (stopping || current == null || !current.accepting()) throw ResourceDownloadServer.linksUnavailable();
    }
    public ResourceDownloadServer.LinkPublication resourceLinkPublication(boolean required) {
        if (!required) return null;
        ResourceDownloadServer current = downloads;
        if (stopping || current == null) throw ResourceDownloadServer.linksUnavailable();
        return current.acquireLinkPublication();
    }
    private final ConcurrentMap<String,Semaphore> storageLocks=new ConcurrentHashMap<>();
    public void holdStorage(TxState state,String key) {
        if(!state.storageKeys.add(key))return;
        Semaphore lock=storageLocks.computeIfAbsent(key,k->new Semaphore(1,true));
        try {while(!lock.tryAcquire(100,TimeUnit.MILLISECONDS))state.kernel.assertOpen();state.release.add(lock::release);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw ExceptionHandler.fail("CANCELLED","Storage wait interrupted");}
    }
    private final CompletableFuture<Void> readiness=new CompletableFuture<>();
    private Path secretDirectory;
    public static byte[] randomBytes(int count) {byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return bytes;}
    public static String randomToken() {return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));}
    public TxState state(KernelTransaction tx) {return transactionLifecycle.state(tx);}
    /** Wait for initialization, then atomically admit this procedure transaction against shutdown. */
    public TxState admit(KernelTransaction tx) {
        return transactionLifecycle.admit(tx);
    }
    boolean admissionOpenUnderGate() { return !stopping && initialized; }
    TxState transactionForTesting(long sequence) { return transactionLifecycle.find(sequence); }
    int transactionCountForTesting() { return transactionLifecycle.transactionCount(); }
    void markReadyForTesting() { synchronized(lifecycleLock) { initialized=true; readiness.complete(null); } }
    @Override public void start(KernelTransaction tx) {}
    @Override public void commit(KernelTransaction tx) {complete(tx,true);}
    @Override public void rollback(KernelTransaction tx,Throwable cause) {complete(tx,false);}
    private void complete(KernelTransaction tx,boolean committed) { transactionLifecycle.complete(tx,committed); }
    public void guard(TxState state, boolean exclusive) {
        transactionLifecycle.guard(state,exclusive);
    }
    public void revokeUser(String actor) { sessionsAndRequests.revokeUser(actor); }
    public void demoteUser(String actor) { sessionsAndRequests.demoteUser(actor); }
    public void clearNamespace(String namespace) { sessionsAndRequests.clearNamespace(namespace); }
    @Override public Void beforeCommit(org.neo4j.graphdb.event.TransactionData data, Transaction tx, GraphDatabaseService db) {
        var internal=(org.neo4j.kernel.impl.coreapi.InternalTransaction)tx;
        TxState state=transactionLifecycle.find(internal.kernelTransaction().getTransactionSequenceNumber());
        if(state==null||state.tokenHash==null)return null;
        if(state.operationClaimed && (!state.operationFinal()
                || !connectionLive(state.operationConnection()) || stopping))
            throw ExceptionHandler.fail("CANCELLED","Operation execution is no longer live before commit");
        Session session=sessionsAndRequests.session(state.tokenHash);
        if(session==null)throw ExceptionHandler.fail("AUTH_FAILED","Session revoked before commit");
        synchronized(session) {
            long now=sessionTime();
            if(!state.operationClaimed&&!state.writerContinuation&&(now-session.touched>=1_800_000L||now-session.created>=28_800_000L))throw ExceptionHandler.fail("SESSION_EXPIRED","Session expired before commit");
        }
        Node user=state.protectedActor;
        if(user==null)user=tx.findNode(Label.label("User"),"id",session.actor);
        if(user==null||(!state.selfAccountChange&&!Boolean.TRUE.equals(user.getProperty("enabled",false))))throw ExceptionHandler.fail("AUTH_FAILED","Account unavailable before commit");
        if(state.privileged&&!state.selfAccountChange&&!"administrator".equals(user.getProperty("serverRole","user")))throw ExceptionHandler.fail("FORBIDDEN","Administrator required before commit");
        return null;
    }
    @Override public void afterCommit(org.neo4j.graphdb.event.TransactionData data,Void state,GraphDatabaseService db) {}
    @Override public void afterRollback(org.neo4j.graphdb.event.TransactionData data,Void state,GraphDatabaseService db) {}
    public void lockServer(TxState state) {
        transactionLifecycle.lockServer(state);
        requireOperationalScope(state.kernel, "User", null);
    }
    public void lockNamespace(KernelTransaction tx,String id) {
        transactionLifecycle.lockCoordination(tx,"namespace:"+id);
        requireOperationalScope(tx, "Namespace", id);
    }
    public void lockActor(KernelTransaction tx,String id) {
        transactionLifecycle.lockCoordination(tx,"actor:"+id);
        requireOperationalScope(tx, "User", id);
    }
    public void lockOperationAdmission(KernelTransaction tx) {
        transactionLifecycle.lockCoordination(tx, "operation-admission");
    }
    public void requireOperationalScope(KernelTransaction tx, String kind, String id) {
        if (storage == null) return;
        var state = transactionLifecycle.find(tx.getTransactionSequenceNumber());
        var unit = state == null ? null : state.pendingCommit;
        storage.operations.requireSettled(kind, id, unit == null ? null : unit.id());
    }
    public void beginBootstrap(TxState state, String user) {
        state.bootstrapUser = user;
        storage.operationalState.beginBootstrap(user);
    }
    public void beginOperationalCommit(TxState state,Key key,String inputHash,OperationBinding binding) {
        var unit=operationUnit(key,inputHash);
        activeOperations.compute(key,(ignored,entry)->{
            if(entry!=null&&entry.worker()!=null)
                throw ExceptionHandler.fail("OPERATION_NOT_CLAIMABLE","Operation already has a worker");
            if(entry==null||entry.binding()!=binding||!storage.operations.begin(unit,sessionTime()))
                throw ExceptionHandler.fail("OPERATION_NOT_CLAIMABLE","Registration expired or changed. Inspect the operation");
            return claimWorker(state,unit,binding);
        });
    }
    public void beginFreshOperationalCommit(TxState state,Attempt attempt,OperationBinding binding) {
        var unit=operationUnit(attempt.key(),attempt.inputHash());
        activeOperations.compute(attempt.key(),(key,entry)->{
            if(entry!=null)throw ExceptionHandler.fail("OPERATION_NOT_CLAIMABLE","Generated operation ID is already in use");
            storage.operations.beginFresh(attempt,unit);
            return claimWorker(state,unit,binding);
        });
    }
    private static OperationRepository.Unit operationUnit(Key key,String inputHash) {
        boolean serverScope=key.scope().equals("server");
        return new OperationRepository.Unit(UUID.randomUUID().toString(),key.actor(),key.scope(),key.id(),inputHash,
            serverScope?"User":"Namespace",serverScope?key.actor():key.scope());
    }
    private static ActiveOperation claimWorker(TxState state,OperationRepository.Unit unit,OperationBinding binding) {
        state.pendingCommit=unit;
        state.operationBinding=binding;
        state.operationClaimed=true;
        return new ActiveOperation(binding,state);
    }
    public void prepareOperationalCommit(TxState state, Node owner, String outcome, String result) {
        var unit = state.pendingCommit;
        storage.operations.prepare(unit, (String)owner.getProperty(OperationRepository.MARKER, null),
            outcome, result);
    }
    public void writeOperationalMarker(TxState state, Node owner) {
        owner.setProperty(OperationRepository.MARKER, state.pendingCommit.id());
    }
    public void lockSpatial(KernelTransaction tx) { transactionLifecycle.lockCoordination(tx,"spatial"); }
    public void lockExchange(KernelTransaction tx, String id) {
        transactionLifecycle.lockCoordination(tx, "exchange:" + id);
        storage.exchange.requireSettled(id, operationalUnit(tx));
    }
    public void lockImportResult(KernelTransaction tx, String id) {
        transactionLifecycle.lockCoordination(tx, "import-result:" + id);
    }
    public String operationalUnit(KernelTransaction tx) {
        var state = transactionLifecycle.find(tx.getTransactionSequenceNumber());
        return state == null || state.pendingCommit == null ? null : state.pendingCommit.id();
    }
    public String prepareWorkflowCommit(RequestContext request, String scope, Node owner) {
        var state = request.state;
        if (state.pendingCommit != null) return state.pendingCommit.id();
        if(owner==null)throw ExceptionHandler.fail("OUTCOME_UNKNOWN","Operation commit marker owner is unavailable");
        String id = UUID.randomUUID().toString();
        String kind = scope.equals("server") ? "User" : "Namespace";
        var unit = new OperationRepository.Unit(id, request.actorId, scope, id, null,
            kind, (String)owner.getProperty("id"));
        storage.operations.beginWorkflow(unit);
        state.pendingCommit = unit;
        prepareOperationalCommit(state, owner, "COMPLETED_NO_DOMAIN_WRITE", "{}");
        writeOperationalMarker(state, owner);
        return id;
    }
    public long revision(TxState state) {
        return transactionLifecycle.revision(state);
    }
    public void initialize(GraphDatabaseAPI db) {
        synchronized(lifecycleLock){if(initialized)return;if(stopping)throw new IllegalStateException("Deposit is shutting down");database=db;}
        try {
            Path configPath=Path.of(System.getProperty("deposit.config")).toAbsolutePath();
            Properties configuration=new Properties();try(var input=Files.newInputStream(configPath)){configuration.load(input);}
            String restoreTime=configuration.getProperty("operationRestoreTime");
            Long restoredAt=restoreTime==null?null:java.time.Instant.parse(restoreTime).toEpochMilli();
            if(restoredAt!=null && restoredAt>sessionTime())throw new IllegalStateException("Operation restore time is ahead of the server clock");
            String configuredSecretRoot=configuration.getProperty("secretRoot");
            if(configuredSecretRoot==null||configuredSecretRoot.isBlank())throw new IllegalStateException("Deposit secretRoot is required");
            secretDirectory=Path.of(configuredSecretRoot).toAbsolutePath().normalize();
            if(!Path.of(configuredSecretRoot).isAbsolute()||!Files.isDirectory(secretDirectory)||Files.isSymbolicLink(secretDirectory)||!Files.isWritable(secretDirectory))throw new IllegalStateException("Deposit secretRoot is unavailable");
            Path cursorPath=secretDirectory.resolve("deposit-"+db.databaseId().databaseId().uuid()+"-cursor.key");
            byte[] persisted=Base64.getUrlDecoder().decode(secret(cursorPath));
            System.arraycopy(persisted,0,cursorKey,0,cursorKey.length);
            storage = new StorageManager(this, db);
            SchemaRepository.initialize(db);
            if (restoredAt != null)
                storage.operationalState.advanceAdmission(Math.addExact(restoredAt, OperationRepository.FUTURE_SKEW), -1);
            storage.recoverCommitBoundary();
            storage.verifyBootstrap();
            HistoryLifecycle.recover(this);
            storage.recoverResources();
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
        for(TxState state:transactionLifecycle.activeTransactions()) if(state.operationClaimed)state.signalOperationCancellation();
    }
    /** Called by the authenticated launcher before it signals Neo4j teardown. */
    public void closeAdmission(){
        beginStop();
        ResourceDownloadServer currentDownloads=downloads;
        if(currentDownloads!=null)currentDownloads.stopAccepting();
    }
    Map<String,Object> shutdownCounts(){
        ResourceDownloadServer currentDownloads=downloads;
        return Map.of("transactions",transactionLifecycle.transactionCount(),"readers",currentDownloads==null?0:currentDownloads.activeReaderCount(),
            "handlers",currentDownloads==null?0:currentDownloads.activeHandlerCount());
    }
    public void shutdown(){shutdown(30,TimeUnit.SECONDS);}
    void shutdown(long timeout,TimeUnit unit){
        closeAdmission();
        synchronized(shutdownMonitor){
            while(shutdownRunning)try{shutdownMonitor.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while waiting for Deposit shutdown",e);}
            if(shutdownComplete)return;
            shutdownRunning=true;
        }
        try {
            ResourceDownloadServer currentDownloads=downloads;
            if(currentDownloads!=null)currentDownloads.stopAccepting();
            boolean drained=transactionLifecycle.awaitTransactions(timeout,unit);
            if(!drained){
                for(TxState state:transactionLifecycle.activeTransactions())try{state.kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);}catch(RuntimeException ignored){}
                drained=transactionLifecycle.awaitTransactions(timeout,unit);
            }
            if(!drained){
                Throwable cleanupFailure=transactionLifecycle.activeTransactions().stream().map(state->state.completionFailure).filter(Objects::nonNull).findFirst().orElse(null);
                throw new IllegalStateException("Deposit transactions did not drain; storage remains open",cleanupFailure);
            }
            if(currentDownloads!=null){currentDownloads.close();downloads=null;}
            StorageManager currentStorage=storage;if(currentStorage!=null){currentStorage.close();storage=null;}
            synchronized(shutdownMonitor){shutdownComplete=true;}
        } finally {
            synchronized(shutdownMonitor){shutdownRunning=false;shutdownMonitor.notifyAll();}
        }
    }
    public AutoCloseable pinResource(String resourceId){
        return readerPins.pin(resourceId);
    }
    public boolean hasReaderPin(String resourceId){return readerPins.hasPin(resourceId);}
    private static String secret(Path path) throws java.io.IOException {
        if(!Files.exists(path)) {
            Path temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".partial");
            Files.createFile(temporary);
            var acl=Files.getFileAttributeView(temporary,AclFileAttributeView.class);
            if(acl==null)throw new IllegalStateException("Deposit secrets require Windows owner ACL");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
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
    public Session authenticate(RequestContext c,String principal) {
        return authenticate(c,principal,true);
    }
    public long committedRevision() {return transactionLifecycle.committedRevision();}
    public Session authenticate(RequestContext c,String principal,boolean protect) {
        if(protect)guard(c.state, false);
        return sessionsAndRequests.authenticateBolt(c,principal);
    }
    /** Browser validation is deliberately separate from Bolt principal authentication. */
    public Session authenticateBrowser(RequestContext c) {
        guard(c.state,false);
        return sessionsAndRequests.authenticateBrowser(c);
    }
    public boolean touchBrowserSession(String token,Session expected) {
        return sessionsAndRequests.touchBrowserSession(token,expected);
    }
    public enum Transport { BOLT, BROWSER }
    public void checkLoginDelay(String username,String origin,KernelTransaction tx) { sessionsAndRequests.checkLoginDelay(username,origin,tx); }
    public void loginFailed(String username,String origin) { sessionsAndRequests.loginFailed(username,origin); }
    public static final class Session {
        public final String actor,principal; public final long created;public final Transport transport;public volatile String csrfToken;
        public long touched; public volatile String namespace;
        public volatile boolean administrative;
        public final Map<String,Long> baselines=new HashMap<>();
        public long observationGeneration;
        public volatile String lastOperation, lastScope;
        /** Opaque identity only; session state must not retain the transaction state or kernel handle. */
        public Object mutationOwner;
        public Session(String actor,String principal,long created) {this(actor,principal,created,Transport.BOLT);}
        public Session(String actor,String principal,long created,Transport transport) {this.actor=actor;this.principal=principal;this.created=created;this.touched=created;this.transport=transport;}
    }
    public static final class TxState {
        public final KernelTransaction kernel;
        public String tokenHash,namespace;
        String requestActor;
        // These facts are populated only after the authorization fence is acquired.
        public Node protectedActor;
        public long authorizationGeneration;
        public final Map<String,Node> protectedNamespaces=new HashMap<>();
        public final Map<String,String> namespaceRoles=new HashMap<>();
        public void invalidateAuthorization() {
            authorizationGeneration++;
            protectedActor=null;
            protectedNamespaces.clear();
            namespaceRoles.clear();
        }
        public boolean observationsCaptured;
        public long observationGeneration;
        public final Map<String,Long> observations=new HashMap<>();
        public boolean scoped,selection,mutation,serverLocked,serverChanged,administrative,selfAccountChange,privileged,auditRegistered;
        public boolean writerContinuation;
        final java.util.concurrent.atomic.AtomicBoolean completing=new java.util.concurrent.atomic.AtomicBoolean();
        volatile Throwable completionFailure;
        String bootstrapUser;
        OperationRepository.Unit pendingCommit;
        public final List<Runnable> afterCommit=new ArrayList<>();
        public final List<Runnable> release=new ArrayList<>();
        final Set<String> coordinationKeys=new HashSet<>();
        public final Set<String> storageKeys=new HashSet<>();
        public final Map<String,org.neo4j.gis.spatial.EditableLayer> geometryLayers=new HashMap<>();
        public ImportWriteBudget importWriteBudget;
        public final Set<String> requestIds=ConcurrentHashMap.newKeySet();
        public volatile boolean operationClaimed;
        Key pendingRegistration;
        OperationBinding pendingRegistrationBinding;
        boolean registrationCancellation;
        public volatile boolean operationSettled;
        private OperationBinding operationBinding;
        public Key operationKey() { return pendingCommit.key(); }
        public String operationConnection() { return operationBinding.connection(); }
        public volatile long tableReadDeadline=Long.MAX_VALUE;
        public String tableReadConnection;
        private final java.util.concurrent.atomic.AtomicInteger operationGate=new java.util.concurrent.atomic.AtomicInteger();
        public boolean enterOperationFinal() { return operationGate.compareAndSet(0,1); }
        public boolean operationFinal() { return operationGate.get()==1; }
        public boolean operationCancelled() { return operationGate.get()==2; }
        public void signalOperationCancellation() {
            if(operationGate.compareAndSet(0,2))
                kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);
        }
        private final Object identityToken=new Object();
        TxState(KernelTransaction kernel) {this.kernel=kernel;}
        public Object identityToken() { return identityToken; }
    }
}
