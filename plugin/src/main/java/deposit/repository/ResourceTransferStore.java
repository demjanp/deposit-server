package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import deposit.common.ResourceStorage;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Durable transfer progress and private cleanup identities. */
public final class ResourceTransferStore {
    private final OperationalStore store;

    public record Transfer(String id, String resource, String namespace, String actor, String filename,
                           String storedFilename, String mediaType, Long bytes, String checksum,
                           Long nextOffset, String state, long expires, String admissionId, String stagingPath,
                           boolean writerBound, Long orphanSince, String deleting, boolean collected,
                           boolean quarantined, String diagnostic) {
        public ResourceStorage.Content content() {
            return new ResourceStorage.Content(resource,storedFilename,bytes,checksum);
        }
        public Transfer progress(long offset, String nextState, long expiry, String admission, Long orphan) {
            return new Transfer(id,resource,namespace,actor,filename,storedFilename,mediaType,bytes,checksum,
                offset,nextState,expiry,admission,stagingPath,writerBound,orphan,deleting,collected,quarantined,diagnostic);
        }
        public Transfer finish(long expiry, String admission) {
            return new Transfer(id,resource,namespace,actor,null,null,null,null,null,null,
                "finished",expiry,admission,stagingPath,writerBound,null,deleting,collected,quarantined,diagnostic);
        }
        public Transfer cleanup(String path, String deletion, boolean done, boolean quarantine, String message) {
            return new Transfer(id,resource,namespace,actor,filename,storedFilename,mediaType,bytes,checksum,
                nextOffset,state,expires,admissionId,path,writerBound,orphanSince,deletion,done,quarantine,message);
        }
    }

    public record Cleanup(String id, String namespace, String storedFilename, String stagingPath, long bytes,
                          String checksum, long quarantinedAt) {}

    public ResourceTransferStore(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE resource_transfers (
                id TEXT NOT NULL, resource_id TEXT NOT NULL, namespace_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                filename TEXT, stored_filename TEXT, media_type TEXT, bytes INTEGER,
                checksum TEXT, next_offset INTEGER, state TEXT NOT NULL, expires_at INTEGER NOT NULL,
                admission_id TEXT, staging_path TEXT, writer_bound INTEGER NOT NULL, orphan_since INTEGER,
                deleting TEXT, collected INTEGER NOT NULL, quarantined INTEGER NOT NULL, diagnostic TEXT,
                unit_id TEXT NOT NULL, PRIMARY KEY (id,unit_id), UNIQUE (resource_id,unit_id),
                CHECK ((state='finished' AND filename IS NULL AND stored_filename IS NULL AND media_type IS NULL
                        AND bytes IS NULL AND checksum IS NULL AND next_offset IS NULL)
                    OR (state<>'finished' AND filename IS NOT NULL AND stored_filename IS NOT NULL AND media_type IS NOT NULL
                        AND bytes IS NOT NULL AND checksum IS NOT NULL AND next_offset IS NOT NULL))
            ) STRICT
            """);
        sql.execute("CREATE INDEX resource_transfer_pending ON resource_transfers(unit_id) WHERE unit_id<>''");
        sql.execute("CREATE INDEX resource_transfer_scope ON resource_transfers(namespace_id,resource_id)");
        sql.execute("CREATE INDEX resource_transfer_expiry ON resource_transfers(expires_at) WHERE unit_id=''");
        sql.execute("""
            CREATE TABLE resource_cleanup (
                id TEXT PRIMARY KEY, namespace_id TEXT NOT NULL, stored_filename TEXT,
                staging_path TEXT UNIQUE, bytes INTEGER NOT NULL, checksum TEXT NOT NULL, quarantined_at INTEGER NOT NULL
            ) STRICT
            """);
    }

    public Transfer transfer(String id) { return find("id",id,""); }
    public Transfer resource(String resource, String unit) { return find("resource_id",resource,unit); }

    public void requireSettled(String resource,String unit) {
        boolean pending=store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT 1 FROM resource_transfers WHERE resource_id=? AND unit_id<>'' AND unit_id IS NOT ? LIMIT 1
                    """)) {
                query.setString(1,resource);
                query.setString(2,unit);
                try(var rows=query.executeQuery()) { return rows.next(); }
            }
        });
        if(pending)throw ExceptionHandler.fail("OUTCOME_UNKNOWN","Resource transfer is awaiting settlement");
    }

    private Transfer find(String column, String id, String unit) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT * FROM resource_transfers WHERE "+column
                    +"=? AND unit_id IN ('',?) ORDER BY unit_id DESC LIMIT 1")) {
                query.setString(1,id);
                query.setString(2,unit);
                try (var rows=query.executeQuery()) { return rows.next()?read(rows):null; }
            }
        });
    }

    public List<Transfer> scoped(String namespace) {
        return store.read(db -> {
            var result=new ArrayList<Transfer>();
            String scope=namespace==null?"":" AND namespace_id=?";
            try(var query=db.prepareStatement("SELECT * FROM resource_transfers WHERE unit_id=''"+scope+" ORDER BY resource_id")) {
                if(namespace!=null)query.setString(1,namespace);
                try(var rows=query.executeQuery()) { while(rows.next())result.add(read(rows)); }
            }
            return result;
        });
    }

    public List<String> provisionalIds(String after) {
        return store.read(db -> {
            var ids=new ArrayList<String>();
            try(var query=db.prepareStatement("""
                    SELECT resource_id FROM resource_transfers WHERE unit_id='' AND resource_id>?
                      AND state<>'finished' AND collected=0 AND quarantined=0 ORDER BY resource_id LIMIT 128
                    """)) {
                query.setString(1,after);
                try(var rows=query.executeQuery()) { while(rows.next())ids.add(rows.getString(1)); }
            }
            return ids;
        });
    }

    private static Transfer read(ResultSet row) throws SQLException {
        Long orphan=nullableLong(row,"orphan_since");
        return new Transfer(row.getString("id"),row.getString("resource_id"),row.getString("namespace_id"),
            row.getString("actor_id"),row.getString("filename"),row.getString("stored_filename"),
            row.getString("media_type"),nullableLong(row,"bytes"),row.getString("checksum"),nullableLong(row,"next_offset"),
            row.getString("state"),row.getLong("expires_at"),row.getString("admission_id"),row.getString("staging_path"),
            row.getBoolean("writer_bound"),orphan,row.getString("deleting"),row.getBoolean("collected"),
            row.getBoolean("quarantined"),row.getString("diagnostic"));
    }

    private static Long nullableLong(ResultSet row,String column) throws SQLException {
        long value=row.getLong(column);
        return row.wasNull()?null:value;
    }

    public void save(Transfer transfer, String unit) {
        store.write(0,db -> {
            try(var write=db.prepareStatement("INSERT OR REPLACE INTO resource_transfers VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                write.setString(1,transfer.id());
                write.setString(2,transfer.resource());
                write.setString(3,transfer.namespace());
                write.setString(4,transfer.actor());
                write.setString(5,transfer.filename());
                write.setString(6,transfer.storedFilename());
                write.setString(7,transfer.mediaType());
                write.setObject(8,transfer.bytes());
                write.setString(9,transfer.checksum());
                write.setObject(10,transfer.nextOffset());
                write.setString(11,transfer.state());
                write.setLong(12,transfer.expires());
                write.setString(13,transfer.admissionId());
                write.setString(14,transfer.stagingPath());
                write.setBoolean(15,transfer.writerBound());
                write.setObject(16,transfer.orphanSince());
                write.setString(17,transfer.deleting());
                write.setBoolean(18,transfer.collected());
                write.setBoolean(19,transfer.quarantined());
                write.setString(20,transfer.diagnostic());
                write.setString(21,unit);
                write.executeUpdate();
            }
            return null;
        });
    }

    public List<String> expired(long cutoff) {
        return store.read(db -> {
            var ids = new ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM resource_transfers WHERE unit_id='' AND expires_at<=?
                      AND staging_path IS NULL AND quarantined=0 AND deleting IS NULL
                      AND (state='finished' OR collected=1) ORDER BY expires_at,id LIMIT 128
                    """)) {
                query.setLong(1,cutoff);
                try(var rows=query.executeQuery()) { while(rows.next())ids.add(rows.getString(1)); }
            }
            return ids;
        });
    }

    public void delete(String id) {
        store.write(0,db -> {
            execute(db,"DELETE FROM resource_transfers WHERE id=? AND unit_id=''",id);
            return null;
        });
    }

    public void quarantine(Cleanup record) {
        store.write(0,db -> {
            try(var insert=db.prepareStatement("INSERT OR IGNORE INTO resource_cleanup VALUES (?,?,?,?,?,?,?)")) {
                insert.setString(1,record.id());
                insert.setString(2,record.namespace());
                insert.setString(3,record.storedFilename());
                insert.setString(4,record.stagingPath());
                insert.setLong(5,record.bytes());
                insert.setString(6,record.checksum());
                insert.setLong(7,record.quarantinedAt());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public List<Cleanup> quarantined(String after,int limit) {
        return store.read(db -> {
            var records=new ArrayList<Cleanup>();
            try(var query=db.prepareStatement("SELECT * FROM resource_cleanup WHERE id>? ORDER BY id LIMIT ?")) {
                query.setString(1,after);
                query.setInt(2,limit);
                try(var rows=query.executeQuery()) {
                    while(rows.next())records.add(new Cleanup(rows.getString("id"),rows.getString("namespace_id"),
                        rows.getString("stored_filename"),rows.getString("staging_path"),rows.getLong("bytes"),
                        rows.getString("checksum"),rows.getLong("quarantined_at")));
                }
            }
            return records;
        });
    }

    public void clearQuarantine(String id) {
        store.write(0,db -> {
            execute(db,"DELETE FROM resource_cleanup WHERE id=?",id);
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        execute(db,committed?"UPDATE OR REPLACE resource_transfers SET unit_id='' WHERE unit_id=?"
            :"DELETE FROM resource_transfers WHERE unit_id=?",unit);
    }

    private static void execute(Connection db,String sql,String value) throws SQLException {
        try(var statement=db.prepareStatement(sql)) {
            statement.setString(1,value);
            statement.executeUpdate();
        }
    }
}
