package deposit.common;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable staged source records; category lists load one source record at a time. */
public final class ExportRecords implements AutoCloseable {
    public interface StorageBudget {void reserve(long bytes)throws IOException;void materialized(long bytes)throws IOException;}
    private static final StorageBudget NO_BUDGET=new StorageBudget(){public void reserve(long bytes){}public void materialized(long bytes){}};
    private final Runnable checkpoint;
    private final StorageBudget budget;
    private final Path directory;private final RandomAccessFile input;private final FileOutputStream file;private final BufferedOutputStream output;
    private final PackageWorkspace indexes;
    private final Map<String,Category> categories=new HashMap<>();
    public ExportRecords(Path parent)throws IOException{this(parent,()->{},NO_BUDGET);}
    public ExportRecords(Path parent,Runnable checkpoint)throws IOException{this(parent,checkpoint,NO_BUDGET);}
    public ExportRecords(Path parent,Runnable checkpoint,StorageBudget budget)throws IOException{this.checkpoint=checkpoint;this.budget=budget;Files.createDirectories(parent);directory=Files.createTempDirectory(parent,"snapshot-");Path data=directory.resolve("records.json");file=new FileOutputStream(data.toFile());OutputStream accounted=new OutputStream(){public void write(int value)throws IOException{budget.reserve(1);file.write(value);budget.materialized(1);}public void write(byte[] bytes,int offset,int length)throws IOException{if(length==0)return;budget.reserve(length);file.write(bytes,offset,length);budget.materialized(length);}public void flush()throws IOException{file.flush();}public void close()throws IOException{file.close();}};output=new BufferedOutputStream(accounted,65536);input=new RandomAccessFile(data.toFile(),"r");indexes=new PackageWorkspace(directory,checkpoint,budget);}
    public void add(String category,Map<String,Object> row)throws IOException{
        Category list=categories.get(category);if(list==null){list=new Category(category);categories.put(category,list);}output.flush();long start=file.getChannel().position();
        // ObjectMapper closes this view, while the shared record spool remains open.
        var scalar=new LinkedHashMap<>(row);var references=new LinkedHashMap<String,Object>();
        for(var entry:row.entrySet())if(entry.getValue() instanceof StagedRows rows){scalar.remove(entry.getKey());references.put(entry.getKey(),Json.map("category",rows.category,"start",(long)rows.start,"end",(long)rows.end));}
        Json.write(new OutputStream(){public void write(int value)throws IOException{checkpoint.run();output.write(value);}public void write(byte[] bytes,int offset,int length)throws IOException{checkpoint.run();output.write(bytes,offset,length);}public void close()throws IOException{output.flush();}},Json.map("record",scalar,"slices",references));output.flush();long length=file.getChannel().position()-start;
        long indexOffset;try{indexOffset=Math.multiplyExact((long)list.count,16L);}catch(ArithmeticException e){throw new IOException("Source record index is not representable",e);}budget.reserve(16);list.index.seek(indexOffset);list.index.writeLong(start);list.index.writeLong(length);budget.materialized(16);if(list.count==Integer.MAX_VALUE)throw new IOException("Source record count exceeds Java list addressability");list.count++;
    }
    public Map<String,Object> journal(String json)throws IOException{return indexes.parse(new StringReader(json));}
    public void addValue(String category,Object value)throws IOException{add(category,Json.map("value",value));}
    /** Store revision envelopes separately from their potentially Namespace-sized changes. */
    public void addRevision(String category,Map<String,Object> revision)throws IOException{
        var metadata=new LinkedHashMap<>(revision);Object changes=metadata.remove("changes");
        String changeCategory=category+"-rows";int start=list(changeCategory).size();
        for(Object change:(Collection<?>)changes)add(changeCategory,ValueCodec.map(change));
        int touchStart=list(category+"-touches").size(),dependencyStart=list(category+"-dependencies").size();
        Object touches=metadata.remove("touches"),dependencies=metadata.remove("dependencies");
        if(touches instanceof Map<?,?> map)for(var entry:map.entrySet())add(category+"-touches",Json.map("key",entry.getKey(),"value",entry.getValue()));
        if(dependencies instanceof Collection<?> values)for(Object value:values)addValue(category+"-dependencies",value);
        add(category,Json.map("metadata",metadata,"start",(long)start,"end",(long)list(changeCategory).size(),"touchStart",(long)touchStart,"touchEnd",(long)list(category+"-touches").size(),"dependencyStart",(long)dependencyStart,"dependencyEnd",(long)list(category+"-dependencies").size()));
    }
    public List<Map<String,Object>> revisions(String category){return revisionsView(category,list(category));}
    /** Fixed revision range: later epochs may append to the shared spool without changing this envelope. */
    public List<Map<String,Object>> revisions(String category,int start,int end){return revisionsView(category,slice(category,start,end));}
    private List<Map<String,Object>> revisionsView(String category,List<Map<String,Object>> envelopes){return new AbstractList<>(){
        public int size(){return envelopes.size();}
        public Map<String,Object> get(int item){var envelope=envelopes.get(item);var revision=new LinkedHashMap<>(ValueCodec.map(envelope.get("metadata")));revision.put("changes",slice(category+"-rows",((Number)envelope.get("start")).intValue(),((Number)envelope.get("end")).intValue()));
            var touches=slice(category+"-touches",((Number)envelope.get("touchStart")).intValue(),((Number)envelope.get("touchEnd")).intValue());
            revision.put("touches",new AbstractMap<String,Object>(){public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return touches.size();}public Iterator<Entry<String,Object>> iterator(){var iterator=touches.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Object> next(){var row=iterator.next();return new SimpleImmutableEntry<>((String)row.get("key"),row.get("value"));}};}};}});
            revision.put("dependencies",valuesSlice(category+"-dependencies",((Number)envelope.get("dependencyStart")).intValue(),((Number)envelope.get("dependencyEnd")).intValue()));return revision;}
    };}
    public List<Object> valuesSlice(String category,int start,int end){var rows=slice(category,start,end);return new AbstractList<>(){public int size(){return rows.size();}public Object get(int index){return rows.get(index).get("value");}};}
    public int indexMark(){return indexes.mark();}
    public void releaseIndexes(int mark){try{indexes.release(mark);}catch(IOException e){throw new UncheckedIOException(e);}}
    public void validateHistoryState(Map<String,Map<String,Object>> state){int mark=indexes.mark();try{HistoryCodec.validate(state,indexes,false);}finally{try{indexes.release(mark);}catch(IOException e){throw new UncheckedIOException(e);}}}
    public List<Map<String,Object>> slice(String category,int start,int end){Objects.checkFromToIndex(start,end,list(category).size());return new StagedRows(category,start,end);}
    private final class StagedRows extends AbstractList<Map<String,Object>> {
        final String category;final int start,end;
        StagedRows(String category,int start,int end){this.category=category;this.start=start;this.end=end;}
        public int size(){return end-start;}
        public Map<String,Object> get(int item){Objects.checkIndex(item,size());return list(category).get(start+item);}
    }
    public <T> Map<String,T> index(){return indexes.map();}
    public List<Map<String,Object>> list(String category){var list=categories.get(category);return list==null?List.of():list;}
    private final class Category extends AbstractList<Map<String,Object>> {
        final RandomAccessFile index;int count;
        Category(String category)throws IOException{index=new RandomAccessFile(directory.resolve(category+".index").toFile(),"rw");}
        @Override public int size(){return count;}
        @Override public Map<String,Object> get(int item){Objects.checkIndex(item,count);try{index.seek(item*16L);long start=index.readLong(),length=index.readLong();input.seek(start);var envelope=Json.read(new InputStream(){long remaining=length;public int read()throws IOException{if(remaining==0)return -1;remaining--;return input.read();}public int read(byte[] b,int offset,int requested)throws IOException{checkpoint.run();if(remaining==0)return -1;int read=input.read(b,offset,(int)Math.min(requested,remaining));if(read>0)remaining-=read;return read;}});var row=new LinkedHashMap<>(ValueCodec.map(envelope.get("record")));for(var reference:ValueCodec.map(envelope.get("slices")).entrySet()){var range=ValueCodec.map(reference.getValue());row.put(reference.getKey(),slice((String)range.get("category"),((Number)range.get("start")).intValue(),((Number)range.get("end")).intValue()));}return row;}catch(IOException e){throw new UncheckedIOException(e);}}
    }
    @Override public void close()throws IOException{IOException failure=null;try{indexes.close();output.close();input.close();}catch(IOException e){failure=e;}for(var category:categories.values())try{category.index.close();}catch(IOException e){failure=e;}try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}if(failure!=null)throw failure;}
}
