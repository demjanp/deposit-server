package deposit.service;

import deposit.common.*;
import deposit.repository.StagedTableRepository.Table;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Table admission shares the Namespace lock with table and source-lease cleanup. */
final class StagedTable extends BaseService {
    private static final long READER_GRACE = 900_000L;

    StagedTable(RequestContext c) { super(c); }

    Table acquire(Object raw) {
        String id = RequestContext.uuid(raw);
        repo.lockNamespace(c.namespaceId);
        Table table = c.server.storage.tables.table(id);
        if (table != null && !c.namespaceId.equals(table.namespace()))
            c.fail("SCOPE_MISMATCH", "Table belongs to another Namespace");
        long now = c.server.sessionTime();
        if (table == null || table.expires() <= now
                || !c.actorId.equals(table.actor()) && !"owner".equals(effectiveRole(c.namespaceId)))
            c.fail("NOT_FOUND", "Table is unavailable");
        c.state.tableReadConnection=((InternalTransaction)c.tx).clientInfo().connectionId();
        c.state.tableReadDeadline = now + READER_GRACE;
        c.server.storage.tables.touch(id,now);
        return table;
    }

    static void checkpoint(RequestContext c) {
        c.state.kernel.assertOpen();
        if (c.server.sessionTime() >= c.state.tableReadDeadline) c.fail("RESULT_EXPIRED", "Table reader lease expired");
    }
}
