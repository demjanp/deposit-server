package deposit.repository;

import deposit.common.OperationalStore;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Durable collection jobs and their per-resource deletion intent. */
public final class PruneRepository {
    private final OperationalStore store;

    public record Job(String id,String scope,String actor,long minimumAge,long created,String incarnation) {}
    public record Item(String id,String job,String resource,String state,Long deletionBytes,long reclaimed,
                       String diagnostic,long updated) {}

    public PruneRepository(OperationalStore store) { this.store=store; }

    public static void initialize(Statement sql)throws SQLException {
        sql.execute("""
            CREATE TABLE prune_jobs (
                id TEXT PRIMARY KEY, scope TEXT NOT NULL, actor_id TEXT NOT NULL, minimum_age INTEGER NOT NULL,
                created_at INTEGER NOT NULL, incarnation TEXT NOT NULL, unit_id TEXT,
                retiring INTEGER NOT NULL DEFAULT 0
            ) STRICT
            """);
        sql.execute("CREATE INDEX prune_job_pending ON prune_jobs(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("CREATE INDEX prune_job_expiry ON prune_jobs(created_at) WHERE unit_id IS NULL");
        sql.execute("""
            CREATE TABLE prune_items (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES prune_jobs(id) ON DELETE CASCADE,
                resource_id TEXT NOT NULL, state TEXT NOT NULL, deletion_bytes INTEGER, reclaimed_bytes INTEGER NOT NULL,
                diagnostic TEXT, updated_at INTEGER NOT NULL, UNIQUE(job_id,resource_id)
            ) STRICT
            """);
        sql.execute("CREATE INDEX prune_items_work ON prune_items(updated_at,id) WHERE state IN ('pending','deleting','failed')");
        sql.execute("CREATE INDEX prune_items_resource ON prune_items(resource_id,state)");
    }

    public void create(Job job,List<String> resources,String unit) {
        store.write(256L*resources.size(),db -> {
            try(var insert=db.prepareStatement("""
                    INSERT INTO prune_jobs(id,scope,actor_id,minimum_age,created_at,incarnation,unit_id)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1,job.id());
                insert.setString(2,job.scope());
                insert.setString(3,job.actor());
                insert.setLong(4,job.minimumAge());
                insert.setLong(5,job.created());
                insert.setString(6,job.incarnation());
                insert.setString(7,unit);
                insert.executeUpdate();
            }
            try(var insert=db.prepareStatement("INSERT INTO prune_items VALUES (?,?,?,'pending',NULL,0,NULL,?)")) {
                for(String resource:resources) {
                    insert.setString(1,job.id()+"/"+resource);
                    insert.setString(2,job.id());
                    insert.setString(3,resource);
                    insert.setLong(4,job.created());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    public Job job(String id) {
        return store.read(db -> {
            try(var query=db.prepareStatement("SELECT * FROM prune_jobs WHERE id=? AND unit_id IS NULL AND retiring=0")) {
                query.setString(1,id);
                try(var row=query.executeQuery()) {
                    return row.next()?new Job(id,row.getString("scope"),row.getString("actor_id"),row.getLong("minimum_age"),
                        row.getLong("created_at"),row.getString("incarnation")):null;
                }
            }
        });
    }

    public Item item(String id) {
        var items=items("item.id=?",id);
        return items.isEmpty()?null:items.getFirst();
    }

    public List<Item> items(String job) { return items("item.job_id=?",job); }

    private List<Item> items(String predicate,String key) {
        return store.read(db -> {
            var items=new ArrayList<Item>();
            try(var query=db.prepareStatement("""
                    SELECT item.* FROM prune_items item JOIN prune_jobs job ON job.id=item.job_id
                    WHERE job.unit_id IS NULL AND job.retiring=0 AND
                    """+predicate+" ORDER BY item.id")) {
                query.setString(1,key);
                try(var row=query.executeQuery()) {
                    while(row.next()){
                        long bytes=row.getLong("deletion_bytes");
                        Long deletion=row.wasNull()?null:bytes;
                        items.add(new Item(row.getString("id"),row.getString("job_id"),row.getString("resource_id"),
                            row.getString("state"),deletion,row.getLong("reclaimed_bytes"),row.getString("diagnostic"),
                            row.getLong("updated_at")));
                    }
                }
            }
            return items;
        });
    }

    public void transition(Item item,String next,Long deletionBytes,long reclaimed,String diagnostic,long now) {
        store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE prune_items SET state=?,deletion_bytes=?,reclaimed_bytes=?,diagnostic=?,updated_at=?
                    WHERE id=? AND state IN ('pending','deleting','failed')
                    """)) {
                update.setString(1,next);
                update.setObject(2,deletionBytes);
                update.setLong(3,reclaimed);
                update.setString(4,diagnostic);
                update.setLong(5,now);
                update.setString(6,item.id());
                update.executeUpdate();
            }
            return null;
        });
    }

    public List<String> work() {
        return store.read(db -> {
            var ids=new ArrayList<String>();
            try(var query=db.prepareStatement("""
                    SELECT item.id FROM prune_items item JOIN prune_jobs job ON job.id=item.job_id
                    WHERE job.unit_id IS NULL AND job.retiring=0 AND item.state IN ('pending','deleting','failed')
                    ORDER BY item.updated_at,item.id LIMIT 128
                    """);var row=query.executeQuery()) {
                while(row.next())ids.add(row.getString(1));
            }
            return ids;
        });
    }

    public boolean collected(String resource) {
        return store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT 1 FROM prune_items WHERE resource_id=? AND state='completed' AND deletion_bytes IS NOT NULL LIMIT 1
                    """)) {
                query.setString(1,resource);
                try(var row=query.executeQuery()) { return row.next(); }
            }
        });
    }

    public List<String> expired(long cutoff) {
        return store.read(db -> {
            var ids=new ArrayList<String>();
            try(var query=db.prepareStatement("""
                    SELECT id FROM prune_jobs job WHERE unit_id IS NULL AND
                    (retiring=1 OR (created_at<=? AND NOT EXISTS (
                        SELECT 1 FROM prune_items item WHERE item.job_id=job.id
                        AND (item.state NOT IN ('completed','skipped') OR item.updated_at>?))))
                    ORDER BY created_at LIMIT 32
                    """)) {
                query.setLong(1,cutoff);
                query.setLong(2,cutoff);
                try(var row=query.executeQuery()) { while(row.next())ids.add(row.getString(1)); }
            }
            return ids;
        });
    }

    public void delete(String job) {
        store.write(0,db -> {
            execute(db,"UPDATE prune_jobs SET retiring=1 WHERE id=?",job);
            try(var items=db.prepareStatement("""
                    DELETE FROM prune_items WHERE rowid IN (
                        SELECT rowid FROM prune_items WHERE job_id=? LIMIT 256
                    )
                    """)) {
                items.setString(1,job);
                if(items.executeUpdate()<256)execute(db,"DELETE FROM prune_jobs WHERE id=?",job);
            }
            return null;
        });
    }

    static void settle(Connection db,String unit,boolean committed)throws SQLException {
        execute(db,committed?"UPDATE prune_jobs SET unit_id=NULL WHERE unit_id=?"
            :"UPDATE prune_jobs SET unit_id=NULL,retiring=1,created_at=0 WHERE unit_id=?",unit);
    }

    private static void execute(Connection db,String sql,String id)throws SQLException {
        try(var statement=db.prepareStatement(sql)) {
            statement.setString(1,id);
            statement.executeUpdate();
        }
    }
}
