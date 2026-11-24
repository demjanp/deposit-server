package deposit.common;

import deposit.repository.OperationAttemptRepository;
import deposit.repository.OperationAttemptRepository.Key;
import deposit.repository.OperationAttemptRepository.RetentionCursor;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Closes abandoned registrations and collects expired evidence with the durable admission fence. */
final class OperationMaintenance {
    private final ServerContext server;
    private final GraphDatabaseAPI database;
    private Key after;
    private RetentionCursor attemptCursor,receiptCursor;

    OperationMaintenance(ServerContext server,GraphDatabaseAPI database) {
        this.server=server;
        this.database=database;
    }

    void recover() {
        server.storage.attempts.recoverRegistrations(server.sessionTime());
        do { expireRegistrations(); } while(after!=null);
    }

    void tick() {
        expireRegistrations();
        collect();
    }

    private void expireRegistrations() {
        var attempts=server.storage.attempts;
        var records=attempts.registered(after);
        long now=server.sessionTime();
        for(var attempt:records) {
            var binding=server.operationBinding(attempt.key());
            if(binding!=null && attempt.claimDeadline()>now && server.sessionLive(binding.session())
                    && server.connectionLive(binding.connection()))continue;
            if(attempts.cancel(attempt,false,now))server.forgetOperationBinding(attempt.key());
        }
        after=records.size()==128?records.getLast().key():null;
    }

    void finishCleanup() {
        var attempts=server.storage.attempts;
        for(var item:attempts.cleanupCandidates()) {
            var key=item.key();
            if(!server.storage.admission.hasOutputIntent(key.actor(),key.scope(),key.id()))attempts.cleanup(item,"complete");
        }
    }

    private void collect() {
        long now=server.sessionTime();
        var attempts=server.storage.attempts;
        try(var tx=database.beginTx()) {
            server.lockOperationAdmission(((InternalTransaction)tx).kernelTransaction());
            var expired=attempts.expiryCandidates(now,attemptCursor);
            var receipts=server.storage.receipts.standaloneExpiry(now-OperationAttemptRepository.RETENTION,receiptCursor);
            attempts.collect(expired.stream().filter(item->!hasCleanup(item.key())).toList(),
                receipts.stream().filter(item->!hasCleanup(item.cursor().key())).toList(),now);
            attemptCursor=expired.size()==128?expired.getLast():null;
            receiptCursor=receipts.size()==128?receipts.getLast().cursor():null;
            tx.commit();
        }
    }

    private boolean hasCleanup(Key key) {
        return server.storage.admission.hasOutputIntent(key.actor(),key.scope(),key.id());
    }
}
