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
    private final AuthorizationGuard authorization = new AuthorizationGuard();
    private final Object drainMonitor = new Object();
    private volatile long revision;

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
            try { if (state.serverLocked) serverLock.release(); }
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

    void guard(ServerContext.TxState state, boolean exclusive) {
        if (exclusive && state.mutation) throw ExceptionHandler.fail("VALIDATION_FAILED", "Authorization changes must be the transaction's first mutation");
        authorization.acquire(state, exclusive);
    }

    void lockServer(ServerContext.TxState state) {
        if (state.serverLocked) return;
        acquire(state.kernel);
        state.serverLocked = true;
    }

    long revision(ServerContext.TxState state) {
        if (state.serverLocked) return revision + (state.serverChanged ? 1 : 0);
        acquire(state.kernel);
        try { return revision; } finally { serverLock.release(); }
    }

    private void acquire(KernelTransaction tx) {
        try { while (!serverLock.tryAcquire(100, TimeUnit.MILLISECONDS)) tx.assertOpen(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ExceptionHandler.fail("CANCELLED", "Request interrupted"); }
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
