package deposit.common;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neo4j.kernel.api.KernelTransaction;

/** One owner for transaction registration, stop admission, completion, revision and authorization fencing. */
final class TransactionLifecycle {
    private static final System.Logger LOG = System.getLogger(TransactionLifecycle.class.getName());
    private final ServerContext server;
    private final UUID databaseId;
    private final Object admissionGate;
    private final ConcurrentMap<Long,ServerContext.TxState> transactions = new ConcurrentHashMap<>();
    private final Semaphore serverLock = new Semaphore(1, true);
    private final ConcurrentMap<String,CoordinationLock> coordinationLocks = new ConcurrentHashMap<>();
    private final AuthorizationGuard authorization = new AuthorizationGuard();
    private final Object drainMonitor = new Object();
    private volatile long revision;

    private static final class CoordinationLock {
        final Semaphore permit = new Semaphore(1, true);
        int references; // Holders and waiters, updated only inside the map's compute for this key.
    }

    TransactionLifecycle(ServerContext server, UUID databaseId, Object admissionGate) {
        this.server = server;
        this.databaseId = databaseId;
        this.admissionGate = admissionGate;
    }

    ServerContext.TxState state(KernelTransaction tx) {
        return transactions.computeIfAbsent(tx.getTransactionSequenceNumber(), key -> new ServerContext.TxState(tx));
    }

    ServerContext.TxState admit(KernelTransaction tx) {
        server.requireReady();
        synchronized (admissionGate) {
            if (!server.admissionOpenUnderGate()) throw new IllegalStateException("Deposit is shutting down");
            return state(tx);
        }
    }

    ServerContext.TxState find(long sequence) { return transactions.get(sequence); }
    int transactionCount() { return transactions.size(); }
    Collection<ServerContext.TxState> activeTransactions() { return List.copyOf(transactions.values()); }
    long committedRevision() { return revision; }

    void complete(KernelTransaction tx, boolean committed) {
        if (!databaseId.equals(tx.getDatabaseId())) return;
        ServerContext.TxState state = transactions.get(tx.getTransactionSequenceNumber());
        if (state == null || !state.completing.compareAndSet(false, true)) return;
        try {
            settleOperationalState(state, committed);
            if (state.operationClaimed) {
                if (!committed && state.operationCancelled()) server.rememberCancelled(state);
                state.operationSettled=true;
            }
            if (committed) {
                if (state.serverChanged) revision++;
                for (Runnable callback : state.afterCommit) try { callback.run(); }
                catch (RuntimeException ignored) { /* Durable jobs are retried by maintenance. */ }
            }
        } finally {
            Throwable cleanupFailure = null;
            for (int i = state.release.size() - 1; i >= 0; i--) try { state.release.get(i).run(); }
            catch (Throwable failure) { cleanupFailure = recordCleanupFailure(cleanupFailure, failure); }
            try { server.completeRequests(state); }
            catch (Throwable failure) { cleanupFailure = recordCleanupFailure(cleanupFailure, failure); }
            try { authorization.release(state); }
            catch (Throwable failure) { cleanupFailure = recordCleanupFailure(cleanupFailure, failure); }
            if (cleanupFailure == null) transactions.remove(tx.getTransactionSequenceNumber(), state);
            else {
                state.completionFailure = cleanupFailure;
                LOG.log(System.Logger.Level.ERROR, "Transaction cleanup failed; keeping storage drain blocked", cleanupFailure);
            }
            synchronized (drainMonitor) { drainMonitor.notifyAll(); }
        }
    }

    private void settleOperationalState(ServerContext.TxState state, boolean committed) {
        if (state.bootstrapUser == null && state.pendingCommit == null) return;
        try {
            server.storagePoint("operational-before-settlement");
            if (state.bootstrapUser != null) server.storage.operationalState.settleBootstrap(state.bootstrapUser, committed);
            else server.storage.operationalCommits.settle(state.pendingCommit.id(), committed, server.sessionTime());
        } catch (RuntimeException failure) {
            // The pending row remains the fence after the in-memory locks are released.
            server.storage.diagnostic = "Operational commit settlement pending";
            LOG.log(System.Logger.Level.ERROR, "Deposit operational commit requires reconciliation", failure);
        }
    }

    void guard(ServerContext.TxState state, boolean exclusive) {
        if (exclusive && state.mutation) throw ExceptionHandler.fail("VALIDATION_FAILED", "Authorization changes must be the transaction's first mutation");
        authorization.acquire(state, exclusive);
    }

    void lockServer(ServerContext.TxState state) {
        if (state.serverLocked) return;
        acquire(serverLock, state.kernel);
        state.serverLocked = true;
        state.release.add(serverLock::release);
    }

    void lockCoordination(KernelTransaction tx, String key) {
        tx.assertOpen();
        var state = state(tx);
        if (state.coordinationKeys.contains(key)) return;
        var lock = coordinationLocks.compute(key, (id, current) -> {
            var entry = current == null ? new CoordinationLock() : current;
            entry.references++;
            return entry;
        });
        boolean acquired = false;
        try {
            acquire(lock.permit, tx);
            acquired = true;
            state.coordinationKeys.add(key);
            state.release.add(() -> {
                lock.permit.release();
                releaseCoordinationReference(key);
            });
        } finally {
            if (!acquired) releaseCoordinationReference(key);
        }
    }

    private void releaseCoordinationReference(String key) {
        coordinationLocks.computeIfPresent(key, (id, lock) -> --lock.references == 0 ? null : lock);
    }

    long revision(ServerContext.TxState state) {
        if (state.serverLocked) return revision + (state.serverChanged ? 1 : 0);
        acquire(serverLock, state.kernel);
        try { return revision; } finally { serverLock.release(); }
    }

    private void acquire(Semaphore permit, KernelTransaction tx) {
        try { while (!permit.tryAcquire(100, TimeUnit.MILLISECONDS)) tx.assertOpen(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ExceptionHandler.fail("CANCELLED", "Request interrupted"); }
        try { tx.assertOpen(); }
        catch (RuntimeException failure) { permit.release(); throw failure; }
    }

    boolean awaitTransactions(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (drainMonitor) {
            while (!transactions.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return true;
    }

    private static Throwable recordCleanupFailure(Throwable prior, Throwable failure) {
        if (prior == null) return failure;
        if (prior != failure) try { prior.addSuppressed(failure); } catch (Throwable ignored) {}
        return prior;
    }
}
