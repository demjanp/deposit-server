package deposit.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only JSON records backed by a private temporary file. Iteration is bounded by one record. */
public final class DiskJsonList extends AbstractList<Object> implements LongSized, AutoCloseable {
    private final Path path;
    private final Path offsets;
    private final ImportWriteBudget.ScratchLease scratchBudget;
    private RandomAccessFile writer,index;
    private final Set<BufferedReader> readers=new HashSet<>();
    private long size;

    public DiskJsonList(Path parent, String prefix) throws IOException {
        this(parent,prefix,null);
    }
    public DiskJsonList(Path parent,String prefix,ImportWriteBudget budget)throws IOException {
        scratchBudget=budget==null?null:budget.scratchLease();
        Path createdPath=null,createdOffsets=null;
        try{if(scratchBudget!=null)scratchBudget.reserve(4096);Files.createDirectories(parent);createdPath=Files.createTempFile(parent,prefix,".jsonl");createdOffsets=Files.createTempFile(parent,prefix,".offsets");path=createdPath;offsets=createdOffsets;writer=new RandomAccessFile(path.toFile(),"rw");index=new RandomAccessFile(offsets.toFile(),"rw");}
        catch(IOException|RuntimeException failure){if(createdPath!=null)try{Files.deleteIfExists(createdPath);}catch(IOException suppressed){failure.addSuppressed(suppressed);}if(createdOffsets!=null)try{Files.deleteIfExists(createdOffsets);}catch(IOException suppressed){failure.addSuppressed(suppressed);}if(scratchBudget!=null)scratchBudget.close();throw failure;}
    }

    @Override public boolean add(Object value) {
        try {long next=Math.addExact(size,1L);if(scratchBudget!=null)scratchBudget.reserve(checkedEstimate(value));index.writeLong(writer.getFilePointer());Json.write(new BufferedOutputStream(new OutputStream(){public void write(int value)throws IOException{writer.write(value);}public void write(byte[] data,int offset,int length)throws IOException{writer.write(data,offset,length);}}),value);writer.write('\n');size=next;return true;}
        catch(IOException e){throw new UncheckedIOException(e);}
    }

    @Override public int size(){return intSize(size);}
    public long longSize(){return size;}

    private static long checkedEstimate(Object value)throws IOException {
        final long[] bytes={0};OutputStream count=new OutputStream(){public void write(int b)throws IOException{bytes[0]=Math.addExact(bytes[0],1L);}public void write(byte[] b,int off,int len)throws IOException{bytes[0]=Math.addExact(bytes[0],(long)len);}};
        try{Json.write(count,value);return Math.addExact(bytes[0],8L+512L);}catch(ArithmeticException overflow){throw new IOException("Import scratch estimate is not representable",overflow);}
    }

    @Override public Object get(int index) {
        Objects.checkIndex(index,size());
        try(var positions=new RandomAccessFile(offsets.toFile(),"r");var input=new RandomAccessFile(path.toFile(),"r")){
            positions.seek(index*8L);long start=positions.readLong(),end=hasNextRecord(index,size)?positions.readLong():input.length();input.seek(start);
            return Json.read(new InputStream(){long remaining=end-start;public int read()throws IOException{if(remaining==0)return -1;remaining--;return input.read();}public int read(byte[] bytes,int offset,int length)throws IOException{if(remaining==0)return -1;int read=input.read(bytes,offset,(int)Math.min(length,remaining));if(read>0)remaining-=read;return read;}});
        }catch(IOException e){throw new UncheckedIOException(e);}
    }

    static int intSize(long count){return (int)Math.min(Integer.MAX_VALUE,count);}
    static boolean hasNextRecord(long index,long count){return index>=0&&count>0&&index<count-1L;}

    @Override public Iterator<Object> iterator(){
        try {
            BufferedReader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8);readers.add(reader);
            return new Iterator<>(){
                String next=readLine();
                private String readLine(){try{String value=reader.readLine();if(value==null){reader.close();readers.remove(reader);}return value;}catch(IOException e){throw new UncheckedIOException(e);}}
                public boolean hasNext(){return next!=null;}
                public Object next(){if(next==null)throw new NoSuchElementException();String current=next;next=readLine();return Json.read(current);}
            };
        }catch(IOException e){throw new UncheckedIOException(e);}
    }

    @Override public void close()throws IOException{
        try{for(var reader:readers)reader.close();readers.clear();if(writer!=null){writer.close();writer=null;}if(index!=null){index.close();index=null;}}
        finally{try{Files.deleteIfExists(path);Files.deleteIfExists(offsets);}finally{if(scratchBudget!=null)scratchBudget.close();}}
    }
}
