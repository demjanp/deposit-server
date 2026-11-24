package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Permanent initialization and operation admission state. */
public final class OperationalStateRepository {
    private final OperationalStore store;

    public OperationalStateRepository(OperationalStore store) { this.store = store; }

    public record Bootstrap(boolean initialized, String pendingUser) {}

    public Bootstrap bootstrap() {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT initialized,bootstrap_user_id FROM server_state WHERE id=1");
                 var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing operational server state");
                return new Bootstrap(row.getBoolean(1), row.getString(2));
            }
        });
    }

    public void beginBootstrap(String user) {
        store.write(0, db -> {
            try (var update = db.prepareStatement("""
                    UPDATE server_state SET bootstrap_user_id=?
                    WHERE id=1 AND initialized=0 AND bootstrap_user_id IS NULL
                    """)) {
                update.setString(1, user);
                if (update.executeUpdate() != 1) throw ExceptionHandler.fail("AUTH_FAILED", "Bootstrap unavailable");
            }
            return null;
        });
    }

    public void settleBootstrap(String user, boolean committed) {
        store.write(0, db -> {
            try (var update = db.prepareStatement("""
                    UPDATE server_state SET initialized=?, bootstrap_user_id=NULL
                    WHERE id=1 AND bootstrap_user_id=?
                    """)) {
                update.setBoolean(1, committed);
                update.setString(2, user);
                if (update.executeUpdate() == 0) {
                    try (var query = db.prepareStatement("SELECT initialized,bootstrap_user_id FROM server_state WHERE id=1");
                         var row = query.executeQuery()) {
                        if (!row.next() || row.getBoolean(1) != committed || Objects.nonNull(row.getString(2)))
                            throw new SQLException("Bootstrap intent changed before settlement");
                    }
                }
            }
            return null;
        });
    }

    public boolean admit(String operationId, long now) {
        long time = OperationAttemptRepository.idTime(operationId);
        return store.write(0, db -> {
            var state = admission(db);
            if (now < state.observedTime)
                throw ExceptionHandler.fail("OUTCOME_UNKNOWN", "Operation admission is suspended until the server clock settles");
            if (time <= Math.max(state.rejectedThrough, now - OperationAttemptRepository.RETENTION)
                    || time > now + OperationAttemptRepository.FUTURE_SKEW) return false;
            advance(db, state.rejectedThrough, now);
            return true;
        });
    }

    public record Admission(long rejectedThrough, long observedTime) {}

    public Admission admission() { return store.read(OperationalStateRepository::admission); }

    public void advanceAdmission(long rejectedThrough, long observedTime) {
        store.write(0, db -> {
            advance(db, rejectedThrough, observedTime);
            return null;
        });
    }

    /** Can share the collector's SQLite transaction before deleting retained operation rows. */
    public static void advance(Connection db, long rejectedThrough, long observedTime) throws SQLException {
        try (var update = db.prepareStatement("""
                UPDATE server_state
                SET rejected_through=max(rejected_through,?), observed_time=max(observed_time,?) WHERE id=1
                """)) {
            update.setLong(1, rejectedThrough);
            update.setLong(2, observedTime);
            if (update.executeUpdate() != 1) throw new SQLException("Missing operation admission state");
        }
    }

    private static Admission admission(Connection db) throws SQLException {
        try (var query = db.prepareStatement("SELECT rejected_through, observed_time FROM server_state WHERE id=1");
             var row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("Missing operation admission state");
            return new Admission(row.getLong(1), row.getLong(2));
        }
    }
}
