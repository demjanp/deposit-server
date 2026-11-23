package deposit.service;

import deposit.common.*;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Bounded free-space commitments for sequential Exchange scratch writes. */
final class ExchangeBudget implements ExportRecords.StorageBudget,AutoCloseable {
    private static final long BLOCK=64*1024;
    private final RequestContext c;
    private final StorageAdmission admission;
    private final StorageAdmission.Reservation reservation;
    private long available,materialized;

    ExchangeBudget(RequestContext c,String purpose)throws IOException {
        this.c=c;
        admission=c.server.storage.admission;
        reservation=admission.acquire("exchange-write",UUID.randomUUID().toString(),c.operationId,purpose,
            Map.of(c.server.storage.files.root,0L),Map.of());
    }

    @Override public void reserve(long bytes)throws IOException {
        if(available>=bytes)return;
        long addition=Math.max(BLOCK,bytes-available);
        admission.grow(reservation,Map.of(c.server.storage.files.root,addition));
        available=Math.addExact(available,addition);
    }

    @Override public void materialized(long bytes)throws IOException {
        if(bytes>available)throw new IOException("Exchange write exceeded its disk reservation");
        available-=bytes;
        materialized=Math.addExact(materialized,bytes);
        if(materialized>=BLOCK) {
            admission.materialized(reservation,c.server.storage.files.root,materialized);
            materialized=0;
        }
    }

    @Override public void close()throws IOException {admission.close(reservation);}
}
