package deposit.common;

import java.util.*;

/** Transaction-owned shared/exclusive authorization fence; never thread-owned. */
final class AuthorizationGuard {
    private final Set<ServerContext.TxState> readers = Collections.newSetFromMap(new IdentityHashMap<>());
    private ServerContext.TxState writer;
    private int waitingWriters;

    synchronized void acquire(ServerContext.TxState state, boolean exclusive) {
        if (writer == state || (!exclusive && readers.contains(state))) return;
        // An upgrade is only possible before the transaction's first mutation/lock.
        // Drop the read lease and reauthenticate after acquiring exclusive access.
        if (exclusive && readers.remove(state)) notifyAll();
        if (exclusive) waitingWriters++;
        try {
            while (writer != null || (exclusive ? !readers.isEmpty() : waitingWriters > 0)) {
                state.kernel.assertOpen();
                try { wait(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ExceptionHandler.fail("CANCELLED", "Authorization wait interrupted"); }
            }
            state.kernel.assertOpen();
            if (exclusive) writer = state; else readers.add(state);
        } finally { if (exclusive) {waitingWriters--; notifyAll();} }
    }

    synchronized void release(ServerContext.TxState state) {
        readers.remove(state);
        if (writer == state) writer = null;
        notifyAll();
    }
}
