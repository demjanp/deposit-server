package deposit.repository;

import deposit.common.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.Consumer;

/** Transaction-owned SQLite images and journal indexes. No image collection is retained in heap. */
public final class HistoryWorkspace implements AutoCloseable {
    private static final String OWNER_FILE=".deposit-history-workspace",OWNER="deposit-history-workspace";
    private final Path directory;
    private final Connection db;
    private final Runnable checkpoint,release;
    private final LongConsumer reserve;
    private int nextSide;
    private boolean closed;

    public HistoryWorkspace(Path parent,Runnable checkpoint,LongConsumer reserve,Runnable release)throws IOException {
        this(parent,checkpoint,reserve,release,ignored->{});
    }
    public HistoryWorkspace(Path parent,Runnable checkpoint,LongConsumer reserve,Runnable release,Consumer<Path> beforeFirstWrite)throws IOException {
        this.checkpoint=checkpoint;this.reserve=reserve;this.release=release;
        Path created=null;Connection opened=null;
        try {
            reserve.accept(16L*1024);Files.createDirectories(parent);created=Files.createTempDirectory(parent,"history-index-");Files.writeString(created.resolve(OWNER_FILE),OWNER,StandardOpenOption.CREATE_NEW);
            beforeFirstWrite.accept(created);
            opened=DriverManager.getConnection("jdbc:sqlite:"+created.resolve("history.sqlite"));
            try(var s=opened.createStatement()){
                s.execute("PRAGMA journal_mode=OFF");s.execute("PRAGMA synchronous=OFF");s.execute("PRAGMA temp_store=FILE");s.execute("PRAGMA cache_size=-2048");
                s.execute("CREATE TABLE images(side INTEGER NOT NULL,entityKey TEXT NOT NULL,kind TEXT NOT NULL,entityId TEXT NOT NULL,classId TEXT,objectId TEXT,name TEXT,type TEXT,body TEXT NOT NULL,PRIMARY KEY(side,entityKey))");
                s.execute("CREATE INDEX image_membership ON images(side,kind,objectId)");s.execute("CREATE INDEX image_declaration ON images(side,kind,classId,name)");s.execute("CREATE INDEX image_name ON images(side,kind,name)");
                s.execute("CREATE TABLE refs(side INTEGER NOT NULL,owner TEXT NOT NULL,dependency TEXT NOT NULL,PRIMARY KEY(side,owner,dependency))");s.execute("CREATE INDEX refs_dependency ON refs(side,dependency,owner)");
                s.execute("CREATE TABLE pattern_refs(side INTEGER NOT NULL,owner TEXT NOT NULL,className TEXT NOT NULL,field TEXT NOT NULL,PRIMARY KEY(side,owner,className,field))");s.execute("CREATE INDEX pattern_schema ON pattern_refs(side,className,field,owner)");
                s.execute("CREATE TABLE touches(entityKey TEXT PRIMARY KEY,kind TEXT NOT NULL,fields TEXT NOT NULL)");s.execute("CREATE TABLE dependencies(entityKey TEXT PRIMARY KEY)");
            }
            opened.setAutoCommit(false);directory=created;db=opened;
        } catch(SQLException|IOException|RuntimeException failure){
            if(opened!=null)try{opened.close();}catch(SQLException e){failure.addSuppressed(e);}
            boolean cleaned=true;if(created!=null)try{remove(created);}catch(IOException e){cleaned=false;failure.addSuppressed(e);}if(cleaned)release.run();
            if(failure instanceof RuntimeException runtime)throw runtime;
            throw new IOException("Unable to create private history indexes",failure);
        }
    }
    private RuntimeException failed(SQLException failure){return new UncheckedIOException(new IOException("Private history index failed",failure));}
    private void check(){if(closed)throw new IllegalStateException("History index is closed");checkpoint.run();}
    private void bind(PreparedStatement statement,Object... values)throws SQLException{for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);}
    private void update(String sql,Object... values){check();long bytes=512;for(Object value:values)if(value!=null)bytes=Math.addExact(bytes,value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);reserve.accept(bytes);try(var s=db.prepareStatement(sql)){bind(s,values);s.executeUpdate();}catch(SQLException e){throw failed(e);}}
    private Map<String,Object> one(String sql,Object... values){check();try(var s=db.prepareStatement(sql)){bind(s,values);try(var rows=s.executeQuery()){if(!rows.next())return null;var out=new LinkedHashMap<String,Object>();var columns=rows.getMetaData();for(int i=1;i<=columns.getColumnCount();i++)out.put(columns.getColumnLabel(i),rows.getObject(i));return out;}}catch(SQLException e){throw failed(e);}}
    private long count(String table,String condition,Object... values){return ((Number)one("SELECT count(*) AS n FROM "+table+" WHERE "+condition,values).get("n")).longValue();}
    /** Keyset iteration closes each bounded query before its caller does nested lookups or writes. */
    private Iterable<String> keys(String table,String column,String condition,Object... values){return ()->new Iterator<>(){
        String last="";String next=load();
        private String load(){var args=Arrays.copyOf(values,values.length+1);args[values.length]=last;var row=one("SELECT "+column+" AS entityKey FROM "+table+" WHERE "+condition+" AND "+column+">? GROUP BY "+column+" ORDER BY "+column+" LIMIT 1",args);return row==null?null:(String)row.get("entityKey");}
        public boolean hasNext(){return next!=null;}
        public String next(){if(next==null)throw new NoSuchElementException();String value=next;last=value;next=load();return value;}
    };}
    public Map<String,Map<String,Object>> newState(){return new State(nextSide++);}
    private State state(Map<String,Map<String,Object>> value){if(!(value instanceof HistoryWorkspace.State view)||view.owner()!=this)throw new IllegalArgumentException("History state belongs to another workspace");return view;}
    private final class State extends AbstractMap<String,Map<String,Object>> implements HistoryCodec.DeclarationState {
        final int side;State(int side){this.side=side;}HistoryWorkspace owner(){return HistoryWorkspace.this;}
        @Override public Map<String,Object> get(Object key){if(!(key instanceof String))return null;var row=one("SELECT body FROM images WHERE side=? AND entityKey=?",side,key);return row==null?null:Json.sourceRecord((String)row.get("body"));}
        @Override public boolean containsKey(Object key){return key instanceof String&&one("SELECT 1 AS found FROM images WHERE side=? AND entityKey=?",side,key)!=null;}
        @Override public Map<String,Object> put(String key,Map<String,Object> image){
            if(!key.equals(HistoryCodec.key(image)))throw new IllegalArgumentException("History image identity differs");
            var previous=get(key);String kind=(String)image.get("kind");
            update("INSERT INTO images(side,entityKey,kind,entityId,classId,objectId,name,type,body) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(side,entityKey) DO UPDATE SET kind=excluded.kind,entityId=excluded.entityId,classId=excluded.classId,objectId=excluded.objectId,name=excluded.name,type=excluded.type,body=excluded.body",side,key,kind,image.get("id"),image.get("classId"),image.get("objectId"),image.get("name"),image.get("type"),Json.write(image));
            update("DELETE FROM refs WHERE side=? AND owner=?",side,key);for(String reference:HistoryCodec.references(image))update("INSERT INTO refs(side,owner,dependency) VALUES(?,?,?)",side,key,reference);
            update("DELETE FROM pattern_refs WHERE side=? AND owner=?",side,key);
            if("Pattern".equals(kind))for(Object raw:ValueCodec.map(ValueCodec.map(image.get("definition")).get("objects")).values()){
                var role=ValueCodec.map(raw);String className=(String)role.get("className");update("INSERT OR IGNORE INTO pattern_refs(side,owner,className,field) VALUES(?,?,?,?)",side,key,className,"");
                for(String field:PatternCodec.referencedDescriptors(role))update("INSERT OR IGNORE INTO pattern_refs(side,owner,className,field) VALUES(?,?,?,?)",side,key,className,field);
            }
            return previous;
        }
        @Override public int size(){return (int)Math.min(Integer.MAX_VALUE,count("images","side=?",side));}
        @Override public Set<Entry<String,Map<String,Object>>> entrySet(){return new AbstractSet<>(){
            public int size(){return State.this.size();}
            public Iterator<Entry<String,Map<String,Object>>> iterator(){var iterator=keys("images","entityKey","side=?",side).iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Map<String,Object>> next(){String key=iterator.next();return new SimpleImmutableEntry<>(key,get(key));}};}
        };}
        public String descriptorType(Map<String,Object> descriptor){
            var row=one("SELECT d.type AS type FROM images m JOIN images d ON d.side=m.side AND d.kind='ClassDescriptor' AND d.classId=m.classId AND d.name=? WHERE m.side=? AND m.kind='Membership' AND m.objectId=?",descriptor.get("name"),side,descriptor.get("objectId"));
            if(row==null)HistoryCodec.invalid("Missing historical declaration");return ValueCodec.type(row.get("type"));
        }
    }
    public ChangeSet journal(Map<String,Map<String,Object>> before){return new ChangeSet(new Journal(state(before)));}
    private final class Journal implements ChangeSet.Storage {
        final State before;State after;
        final Map<String,Set<String>> touchView=new AbstractMap<>(){
            public Set<String> get(Object key){var row=one("SELECT fields FROM touches WHERE entityKey=?",key);if(row==null)return null;var fields=new TreeSet<String>();for(Object field:Json.readList((String)row.get("fields")))fields.add((String)field);return fields;}
            public boolean containsKey(Object key){return one("SELECT 1 AS found FROM touches WHERE entityKey=?",key)!=null;}
            public int size(){return (int)Math.min(Integer.MAX_VALUE,count("touches","1=1"));}
            public Set<Entry<String,Set<String>>> entrySet(){return new AbstractSet<>(){public int size(){return touchView.size();}public Iterator<Entry<String,Set<String>>> iterator(){var iterator=keys("touches","entityKey","1=1").iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Set<String>> next(){String key=iterator.next();return new SimpleImmutableEntry<>(key,get(key));}};}};}
        };
        final Set<String> dependencyView=new AbstractSet<>(){
            public int size(){return (int)Math.min(Integer.MAX_VALUE,count("dependencies","1=1"));}
            public boolean contains(Object key){return one("SELECT 1 AS found FROM dependencies WHERE entityKey=?",key)!=null;}
            public Iterator<String> iterator(){return keys("dependencies","entityKey","1=1").iterator();}
        };
        final Map<String,String> kindView=new AbstractMap<>(){
            public String get(Object key){var row=one("SELECT kind FROM touches WHERE entityKey=?",key);return row==null?null:(String)row.get("kind");}
            public Set<Entry<String,String>> entrySet(){return new AbstractSet<>(){public int size(){return touchView.size();}public Iterator<Entry<String,String>> iterator(){var iterator=touchView.keySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,String> next(){String key=iterator.next();return new SimpleImmutableEntry<>(key,get(key));}};}};}
        };
        Journal(State before){this.before=before;after=before;}
        public Map<String,Map<String,Object>> before(){return before;}public Map<String,Map<String,Object>> after(){return after;}
        public Map<String,Set<String>> touches(){return touchView;}public Set<String> dependencies(){return dependencyView;}public Map<String,String> kinds(){return kindView;}
        private void dependency(String key){update("INSERT OR IGNORE INTO dependencies(entityKey) VALUES(?)",key);}
        private void dependencies(State state,Map<String,Object> image){
            if(image==null)return;for(String key:HistoryCodec.references(image))dependency(key);
            String kind=(String)image.get("kind");
            if("Descriptor".equals(kind)){
                for(String membership:keys("images","entityKey","side=? AND kind='Membership' AND objectId=?",state.side,image.get("objectId"))){
                    var row=state.get(membership);dependency(membership);String clazz=(String)row.get("classId");dependency(HistoryCodec.key("Class",clazz));
                    for(String declaration:keys("images","entityKey","side=? AND kind='ClassDescriptor' AND classId=? AND name=?",state.side,clazz,image.get("name")))dependency(declaration);
                }
            }else if("Pattern".equals(kind)){
                for(Object raw:ValueCodec.map(ValueCodec.map(image.get("definition")).get("objects")).values()){
                    var role=ValueCodec.map(raw);for(String clazz:keys("images","entityKey","side=? AND kind='Class' AND name=?",state.side,role.get("className"))){dependency(clazz);String classId=HistoryCodec.idFromKey(clazz);for(String field:PatternCodec.referencedDescriptors(role))for(String declaration:keys("images","entityKey","side=? AND kind='ClassDescriptor' AND classId=? AND name=?",state.side,classId,field))dependency(declaration);}
                }
            }else if("Class".equals(kind)){
                for(String pattern:keys("pattern_refs","owner","side=? AND className=?",state.side,image.get("name")))dependency(pattern);
            }else if("ClassDescriptor".equals(kind)){
                var clazz=state.get(HistoryCodec.key("Class",(String)image.get("classId")));if(clazz!=null)for(String pattern:keys("pattern_refs","owner","side=? AND className=? AND field=?",state.side,clazz.get("name"),image.get("name")))dependency(pattern);
            }
        }
        public void checkpoint(Map<String,Map<String,Object>> next){
            State target=state(next);var left=keys("images","entityKey","side=?",after.side).iterator();var right=keys("images","entityKey","side=?",target.side).iterator();
            String a=left.hasNext()?left.next():null,b=right.hasNext()?right.next():null;
            while(a!=null||b!=null){check();String key=b==null||a!=null&&a.compareTo(b)<0?a:b;var old=after.get(key);var value=target.get(key);var fields=HistoryCodec.fields(old,value);
                if(!fields.isEmpty()){
                    var accumulated=touchView.get(key);if(accumulated==null)accumulated=new TreeSet<>();accumulated.addAll(fields);String kind=(String)(value==null?old:value).get("kind");
                    update("INSERT INTO touches(entityKey,kind,fields) VALUES(?,?,?) ON CONFLICT(entityKey) DO UPDATE SET kind=excluded.kind,fields=excluded.fields",key,kind,Json.write(accumulated));
                    dependencies(after,old);dependencies(target,value);
                    if(fields.contains("*")){dependency(key);for(State state:List.of(after,target))for(String owner:keys("refs","owner","side=? AND dependency=?",state.side,key))dependency(owner);}
                }
                if(key.equals(a))a=left.hasNext()?left.next():null;if(key.equals(b))b=right.hasNext()?right.next():null;
            }
            after=target;
        }
    }
    private static void remove(Path path)throws IOException{if(Files.exists(path))try(var files=Files.walk(path)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    /** Before Server readiness, scratch from a previous process has no live transaction owner. */
    public static void recover(Path parent)throws IOException{
        Path root=parent.toAbsolutePath().normalize();if(!Files.exists(root))return;
        try(var directories=Files.newDirectoryStream(root,"history-index-*")){
            for(Path directory:directories){Path owned=directory.toAbsolutePath().normalize(),marker=owned.resolve(OWNER_FILE);
                if(!owned.startsWith(root)||owned.equals(root)||Files.isSymbolicLink(owned)||!Files.isDirectory(owned,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&Files.size(marker)==OWNER.length()&&OWNER.equals(Files.readString(marker)))remove(owned);
            }
        }
    }
    public void close()throws IOException{if(closed)return;closed=true;IOException failure=null;try{db.close();}catch(SQLException e){failure=new IOException("Unable to close history index",e);}boolean cleaned=true;try{remove(directory);}catch(IOException e){cleaned=false;if(failure==null)failure=e;else failure.addSuppressed(e);}if(cleaned)release.run();if(failure!=null)throw failure;}
}
