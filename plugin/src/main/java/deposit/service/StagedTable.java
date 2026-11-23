package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Table admission shares the Namespace lock with table and source-lease cleanup. */
final class StagedTable extends BaseService {
    private static final long READER_GRACE = 900_000L;

    StagedTable(RequestContext c) { super(c); }

    Node acquire(Object raw) {
        String id = RequestContext.uuid(raw);
        repo.lockNamespace(c.namespaceId);
        Node table = repo.find("DepositTabularTable", "id", id);
        if (table != null && !c.namespaceId.equals(table.getProperty("namespaceId")))
            c.fail("SCOPE_MISMATCH", "Table belongs to another Namespace");
        long now = c.server.sessionTime();
        if (table == null || (long)table.getProperty("expiresAt") <= now
                || !c.actorId.equals(table.getProperty("creatorId")) && !"owner".equals(effectiveRole(c.namespaceId)))
            c.fail("NOT_FOUND", "Table is unavailable");
        c.state.tableReadConnection=((InternalTransaction)c.tx).clientInfo().connectionId();
        c.state.tableReadDeadline = now + READER_GRACE;
        table.setProperty("lastAccessAt", now);
        return table;
    }

    static void checkpoint(RequestContext c) {
        c.state.kernel.assertOpen();
        if (c.server.sessionTime() >= c.state.tableReadDeadline) c.fail("RESULT_EXPIRED", "Table reader lease expired");
    }
}
