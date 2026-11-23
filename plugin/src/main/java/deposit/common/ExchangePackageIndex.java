package deposit.common;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Immutable inspection metadata and positional pages for a verified package. */
public final class ExchangePackageIndex {
    public static final int PAGE_LIMIT=256, PAGE_BYTES=512*1024, ENTRY_BYTES=32*1024, TEXT_BYTES=16*1024;
    public static final String FILE="index.sqlite";
    private ExchangePackageIndex() {}

    public static long build(Path archive,Path resourceDirectory,ExportRecords.StorageBudget budget,Runnable checkpoint)throws IOException {
        Files.createDirectory(resourceDirectory);
        Path path=archive.getParent().resolve(FILE);
        var size=ExchangePackageCodec.index(archive,resourceDirectory,budget,checkpoint,packet->{
            try(var builder=new Builder(path,budget,checkpoint)){
                return builder.build(packet);
            } catch(IOException failure){throw new UncheckedIOException(failure);}
            catch(SQLException failure){throw new UncheckedIOException(new IOException("Unable to build package inspection index",failure));}
        });
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}
        return size;
    }

    public static Map<String,Object> inspect(Path path)throws IOException {
        try(var db=openRead(path);var statement=db.createStatement();var result=statement.executeQuery("SELECT payload FROM inspection")) {
            if(!result.next())throw new IOException("Missing package inspection envelope");
            return Json.read(result.getString(1));
        } catch(SQLException failure){throw new IOException("Unable to read package inspection index",failure);}
    }

    public static Long memberBytes(Path path,String name)throws IOException {
        try(var db=openRead(path);var query=db.prepareStatement("SELECT size FROM member WHERE name=?")) {
            query.setString(1,name);
            try(var result=query.executeQuery()){return result.next()?result.getLong(1):null;}
        } catch(SQLException failure){throw new IOException("Unable to read package member index",failure);}
    }

    public record Page(List<Map<String,Object>> entries,Long nextPosition) {}
    public static Page page(Path path,String id,long position,int limit)throws IOException {
        try(var db=openRead(path);var count=db.prepareStatement("SELECT size FROM collection WHERE id=?");
            var query=db.prepareStatement("SELECT payload FROM entry WHERE collection=? AND position>=? ORDER BY position LIMIT ?")) {
            count.setString(1,id);
            long size;
            try(var result=count.executeQuery()) {
                if(!result.next())return null;
                size=result.getLong(1);
            }
            if(position<0 || position>size)throw new IOException("Package cursor is outside its collection");
            query.setString(1,id); query.setLong(2,position); query.setInt(3,limit);
            var entries=new ArrayList<Map<String,Object>>(limit);
            int bytes=0;
            try(var result=query.executeQuery()) {
                while(result.next()) {
                    byte[] raw=result.getBytes(1);
                    if(raw.length>ENTRY_BYTES)throw new IOException("Package index entry exceeds its bound");
                    if(bytes+raw.length>PAGE_BYTES)break;
                    bytes+=raw.length;
                    entries.add(Json.read(new String(raw,StandardCharsets.UTF_8)));
                }
            }
            long next=Math.addExact(position,entries.size());
            if(next<size && entries.isEmpty())throw new IOException("Package inspection index is incomplete");
            return new Page(entries,next<size?next:null);
        } catch(SQLException failure){throw new IOException("Unable to page package inspection index",failure);}
    }

    private static Connection openRead(Path path)throws SQLException,IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Package inspection index is unavailable");
        var config=new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:"+path,config.toProperties());
    }

    private static long count(Object collection) {
        if(collection instanceof LongSized sized)return sized.longSize();
        if(collection instanceof Map<?,?> map)return map.size();
        return ((Collection<?>)collection).size();
    }

    private static final class Builder implements AutoCloseable {
        private static final long SLACK=2L*1024*1024;
        private final Path path;
        private final ExportRecords.StorageBudget budget;
        private final Runnable checkpoint;
        private final Connection db;
        private final PreparedStatement entries,collections,members;
        private long serial,bytes;
        private int writes;

        Builder(Path path,ExportRecords.StorageBudget budget,Runnable checkpoint)throws SQLException,IOException {
            this.path=path;
            this.budget=budget;
            this.checkpoint=checkpoint;
            if(budget!=null)budget.reserve(SLACK);
            db=DriverManager.getConnection("jdbc:sqlite:"+path);
            try {
                try(var sql=db.createStatement()) {
                    sql.execute("PRAGMA journal_mode=OFF");
                    sql.execute("PRAGMA cache_size=-1024");
                    sql.execute("CREATE TABLE entry (collection TEXT, position INTEGER, payload BLOB NOT NULL, PRIMARY KEY(collection,position)) WITHOUT ROWID");
                    sql.execute("CREATE TABLE collection (id TEXT PRIMARY KEY, size INTEGER NOT NULL) WITHOUT ROWID");
                    sql.execute("CREATE TABLE member (name TEXT PRIMARY KEY, size INTEGER NOT NULL) WITHOUT ROWID");
                    sql.execute("CREATE TABLE inspection (payload TEXT NOT NULL)");
                }
                db.setAutoCommit(false);
                entries=db.prepareStatement("INSERT INTO entry VALUES (?,?,?)");
                collections=db.prepareStatement("INSERT INTO collection VALUES (?,?)");
                members=db.prepareStatement("INSERT INTO member VALUES (?,?)");
            } catch(SQLException failure){db.close();throw failure;}
        }

        long build(ExchangePackageCodec.PackageData packet)throws SQLException,IOException {
            var manifest=new LinkedHashMap<String,Object>();
            var labels=new LinkedHashMap<String,Object>();
            long memberCount=0;
            for(var entry:PackageWorkspace.rawEntries(packet.manifest())) {
                String name=(String)entry.getKey();
                Object value=entry.getValue();
                if(name.equals("files")) {
                    memberCount=files((Map<?,?>)value);
                    manifest.put(name,Json.map("kind","mapping","collection",name,"count",memberCount));
                    labels.put(name,Json.map("kind","mapping","count",memberCount));
                } else {
                    var tagged=value(value,name);
                    if("scalar".equals(tagged.get("type")))manifest.put(name,tagged.get("value"));
                    else {
                        var field=new LinkedHashMap<>(tagged);
                        field.put("kind",field.remove("type"));
                        manifest.put(name,field);
                        labels.put(name,"text".equals(field.get("kind"))
                            ?Json.map("kind","sequence","count",textCount)
                            :Json.map("kind",field.get("kind"),"count",field.get("count")));
                    }
                }
            }
            for(var entry:PackageWorkspace.rawEntries(packet.graph())) {
                String name=(String)entry.getKey();
                var descriptor=value(entry.getValue(),name);
                labels.put(name,Json.map("kind",descriptor.get("type"),"count",descriptor.get("count")));
            }
            try(var envelope=db.prepareStatement("INSERT INTO inspection VALUES (?)")) {
                envelope.setString(1,Json.write(Json.map("manifest",manifest,"collections",labels,"memberCount",memberCount)));
                envelope.executeUpdate();
            }
            db.commit();
            settle(false);
            return memberCount;
        }

        private long files(Map<?,?> files)throws SQLException,IOException {
            long position=0;
            for(var entry:PackageWorkspace.rawEntries(files)) {
                String name=(String)entry.getKey();
                var info=(Map<?,?>)entry.getValue();
                long size=(long)info.get("bytes");
                members.setString(1,name);
                members.setLong(2,size);
                members.executeUpdate();
                put("files",position++,Json.map("member",name,"bytes",size,"sha256",info.get("sha256")));
            }
            collection("files",position);
            return position;
        }

        private long textCount;

        private Map<String,Object> value(Object value,String namedCollection)throws SQLException,IOException {
            if(value instanceof Map<?,?> || value instanceof Collection<?>) {
                String id=collectionId(namedCollection);
                boolean mapping=value instanceof Map<?,?>;
                long position=0;
                if(value instanceof Map<?,?> map) {
                    for(var entry:PackageWorkspace.rawEntries(map))
                        put(id,position++,Json.map("key",value(entry.getKey(),null),"value",value(entry.getValue(),null)));
                } else {
                    for(Object entry:(Collection<?>)value)put(id,position++,value(entry,null));
                }
                if(position!=count(value))throw new IOException("Package collection changed while indexing");
                collection(id,position);
                return Json.map("type",mapping?"mapping":"sequence","collection",id,"count",position);
            }
            if(value instanceof PackageWorkspace.Text text) {
                try(var input=text.reader()){return text(input,collectionId(namedCollection));}
            }
            if(value instanceof String string && string.getBytes(StandardCharsets.UTF_8).length>TEXT_BYTES/8) {
                try(var input=new StringReader(string)){return text(input,collectionId(namedCollection));}
            }
            return Json.map("type","scalar","value",value);
        }

        private String collectionId(String name) {return name!=null?name:"item-"+(++serial);}

        private Map<String,Object> text(Reader input,String id)throws IOException,SQLException {
            var reader=new PushbackReader(new BufferedReader(input),1);
            var chunk=new StringBuilder();
            long position=0,total=0;
            int segment=0,encoded=0;
            for(int first;(first=reader.read())!=-1;) {
                char firstChar=(char)first;
                int second=-1, length, jsonBytes;
                if(Character.isHighSurrogate(firstChar)) {
                    second=reader.read();
                    if(second==-1 || !Character.isLowSurrogate((char)second))throw new IOException("Invalid UTF-16 text in package");
                    length=4;
                    jsonBytes=4;
                } else {
                    if(Character.isLowSurrogate(firstChar))throw new IOException("Invalid UTF-16 text in package");
                    length=first<0x80?1:first<0x800?2:3;
                    jsonBytes=first<32?6:first=='"'||first=='\\'?2:length;
                }
                if(segment+length>TEXT_BYTES || encoded+jsonBytes>ENTRY_BYTES-128) {
                    put(id,position++,Json.map("type","scalar","value",chunk.toString()));
                    chunk.setLength(0);
                    total=Math.addExact(total,segment);
                    segment=encoded=0;
                }
                chunk.append(firstChar);
                if(second!=-1)chunk.append((char)second);
                segment+=length;
                encoded+=jsonBytes;
            }
            if(segment>0)put(id,position++,Json.map("type","scalar","value",chunk.toString()));
            collection(id,position);
            textCount=position;
            return Json.map("type","text","collection",id,"bytes",Math.addExact(total,segment));
        }

        private void collection(String id,long count)throws SQLException,IOException {
            check();
            collections.setString(1,id);
            collections.setLong(2,count);
            collections.executeUpdate();
        }

        private void put(String id,long position,Map<String,Object> value)throws SQLException,IOException {
            check();
            byte[] encoded=Json.write(value).getBytes(StandardCharsets.UTF_8);
            if(encoded.length>ENTRY_BYTES)throw new IOException("Package index entry exceeds its bound");
            entries.setString(1,id);
            entries.setLong(2,position);
            entries.setBytes(3,encoded);
            entries.executeUpdate();
        }

        private void check()throws IOException,SQLException {
            checkpoint.run();
            if(++writes%16==0)settle(true);
        }

        private void settle(boolean replenish)throws IOException,SQLException {
            if(budget==null)return;
            long length;
            // page_count includes dirty pages still in SQLite's bounded cache.
            try(var query=db.createStatement();var result=query.executeQuery("PRAGMA page_count")) {
                if(!result.next())throw new IOException("Cannot measure package index");
                length=Math.multiplyExact(result.getLong(1),4096L);
            }
            long growth=length-bytes;
            if(growth>0) {
                budget.materialized(growth);
                if(replenish)budget.reserve(growth);
            }
            bytes=length;
        }

        @Override public void close()throws SQLException {
            try(entries;collections;members){ }
            finally{db.close();}
        }
    }
}
