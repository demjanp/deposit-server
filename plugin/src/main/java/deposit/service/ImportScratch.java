package deposit.service;

import deposit.common.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Disposable import indexes; owns its SQLite connection, cursors and temporary directory. */
/** Disposable indexes share no transaction or state with the receiving Neo4j database. */
final class ImportScratch implements AutoCloseable {
    final Path directory;final Connection db;final Runnable checkpoint;
    private final Map<String,PreparedStatement> statements=new HashMap<>();
    private final Set<RowCursor> cursors=new HashSet<>();private int nextMap;RuntimeException interrupted;
    ImportScratch(Path parent,Runnable checkpoint)throws IOException {
        this.checkpoint=checkpoint;Files.createDirectories(parent);directory=Files.createTempDirectory(parent,"import-index-");Connection opened=null;
        try {
            opened=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"));db=opened;
            try(var statement=db.createStatement()){statement.execute("PRAGMA journal_mode=OFF");statement.execute("PRAGMA synchronous=OFF");statement.execute("PRAGMA temp_store=FILE");statement.execute("PRAGMA cache_size=-2048");}
            db.setAutoCommit(false);
            org.sqlite.ProgressHandler.setHandler(db,1000,new org.sqlite.ProgressHandler(){protected int progress(){try{check();return 0;}catch(RuntimeException failure){interrupted=failure;return 1;}}});
            update("CREATE TABLE records(bucket INTEGER,key TEXT,body TEXT,PRIMARY KEY(bucket,key))");
            update("CREATE TABLE sources(id TEXT PRIMARY KEY,className TEXT,candidateCount INTEGER NOT NULL DEFAULT 0,keyless INTEGER NOT NULL DEFAULT 0)");
            update("CREATE TABLE candidates(source TEXT,target TEXT,PRIMARY KEY(source,target))");
            update("CREATE TABLE edges(source TEXT,target TEXT,label TEXT)");update("CREATE INDEX edges_source ON edges(source)");update("CREATE INDEX edges_target ON edges(target)");
            update("CREATE TABLE descriptors(owner TEXT,name TEXT,body TEXT,PRIMARY KEY(owner,name))");
            update("CREATE TABLE matching_rules(source TEXT,body TEXT,PRIMARY KEY(source,body))");
            update("CREATE TABLE assignments(source TEXT PRIMARY KEY,target TEXT)");
            update("CREATE TABLE frames(position INTEGER PRIMARY KEY,lastTarget TEXT,creation INTEGER NOT NULL DEFAULT 0)");
        }catch(SQLException|RuntimeException failure){if(opened!=null)try{opened.close();}catch(SQLException suppressed){failure.addSuppressed(suppressed);}removeDirectory();if(failure instanceof RuntimeException runtime)throw runtime;throw new IOException("Unable to open import indexes",failure);}
    }
    void check(){if(interrupted!=null)throw interrupted;try{checkpoint.run();}catch(RuntimeException failure){interrupted=failure;throw failure;}}
    private RuntimeException failure(SQLException failure){return interrupted!=null?interrupted:new UncheckedIOException(new IOException("Private import index failed",failure));}
    private void bind(PreparedStatement statement,Object... values)throws SQLException{statement.clearParameters();for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);}
    void update(String sql,Object... values){check();try{PreparedStatement statement=statements.get(sql);if(statement==null){statement=db.prepareStatement(sql);statements.put(sql,statement);}bind(statement,values);statement.executeUpdate();}catch(SQLException failure){throw failure(failure);}}
    RowCursor query(String sql,Object... values){check();try{var statement=db.prepareStatement(sql);try{bind(statement,values);var cursor=new RowCursor(statement);cursors.add(cursor);return cursor;}catch(SQLException|RuntimeException failure){statement.close();throw failure;}}catch(SQLException failure){throw failure(failure);}}
    Map<String,Object> one(String sql,Object... values){try(var rows=query(sql,values)){return rows.hasNext()?rows.next():null;}}
    final class RowCursor implements Iterator<Map<String,Object>>,Iterable<Map<String,Object>>,AutoCloseable {
        final PreparedStatement statement;final ResultSet rows;final String[] names;boolean next,closed;
        RowCursor(PreparedStatement statement)throws SQLException{this.statement=statement;rows=statement.executeQuery();var metadata=rows.getMetaData();names=new String[metadata.getColumnCount()];for(int i=0;i<names.length;i++)names[i]=metadata.getColumnLabel(i+1);next=rows.next();}
        public Iterator<Map<String,Object>> iterator(){return this;}public boolean hasNext(){return next;}
        public Map<String,Object> next(){if(!next)throw new NoSuchElementException();check();try{var value=new LinkedHashMap<String,Object>();for(int i=0;i<names.length;i++)value.put(names[i],rows.getObject(i+1));next=rows.next();if(!next)close();return value;}catch(SQLException failure){throw failure(failure);}}
        public void close(){if(closed)return;closed=true;next=false;try{rows.close();statement.close();}catch(SQLException failure){throw failure(failure);}finally{cursors.remove(this);}}
    }
    <T> Map<String,T> map(){int bucket=nextMap++;return new AbstractMap<>(){
        @SuppressWarnings("unchecked") public T get(Object key){if(!(key instanceof String))return null;var row=one("SELECT body FROM records WHERE bucket=? AND key=?",bucket,key);return row==null?null:(T)Json.sourceRecord((String)row.get("body")).get("value");}
        public boolean containsKey(Object key){return key instanceof String&&one("SELECT 1 AS found FROM records WHERE bucket=? AND key=?",bucket,key)!=null;}
        public T put(String key,T value){T previous=get(key);update("INSERT INTO records(bucket,key,body) VALUES(?,?,?) ON CONFLICT(bucket,key) DO UPDATE SET body=excluded.body",bucket,key,Json.write(Json.map("value",value)));return previous;}
        public T remove(Object key){T previous=get(key);update("DELETE FROM records WHERE bucket=? AND key=?",bucket,key);return previous;}
        public void clear(){update("DELETE FROM records WHERE bucket=?",bucket);}
        public int size(){return ((Number)one("SELECT count(*) AS n FROM records WHERE bucket=?",bucket).get("n")).intValue();}
        public Set<Entry<String,T>> entrySet(){return new AbstractSet<>(){
            public int size(){return ((Number)one("SELECT count(*) AS n FROM records WHERE bucket=?",bucket).get("n")).intValue();}
            public Iterator<Entry<String,T>> iterator(){var rows=query("SELECT key,body FROM records WHERE bucket=? ORDER BY rowid",bucket);return new Iterator<>(){public boolean hasNext(){return rows.hasNext();}@SuppressWarnings("unchecked") public Entry<String,T> next(){var row=rows.next();return new SimpleImmutableEntry<>((String)row.get("key"),(T)Json.sourceRecord((String)row.get("body")).get("value"));}};}
        };}
    };}
    Map<String,Object> assignments(){return new AbstractMap<>(){
        public Object get(Object key){var row=one("SELECT target FROM assignments WHERE source=?",key);return row==null?null:row.get("target");}
        public int size(){return ((Number)one("SELECT count(*) AS n FROM assignments").get("n")).intValue();}
        public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return ImportScratch.this.assignments().size();}public Iterator<Entry<String,Object>> iterator(){var rows=query("SELECT source,target FROM assignments ORDER BY source");return new Iterator<>(){public boolean hasNext(){return rows.hasNext();}public Entry<String,Object> next(){var row=rows.next();return new SimpleImmutableEntry<>((String)row.get("source"),row.get("target"));}};}};}
    };}
    private void removeDirectory()throws IOException{if(Files.exists(directory))try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    public void close()throws IOException{try{for(var cursor:new ArrayList<>(cursors))cursor.close();for(var statement:statements.values())statement.close();org.sqlite.ProgressHandler.clearHandler(db);db.close();}catch(SQLException failure){throw new IOException("Unable to close import indexes",failure);}finally{removeDirectory();}}
}
