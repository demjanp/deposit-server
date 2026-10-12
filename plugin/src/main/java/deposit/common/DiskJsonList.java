package deposit.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only JSON records backed by a private temporary file. Iteration is bounded by one record. */
public final class DiskJsonList extends AbstractList<Object> implements AutoCloseable {
    private final Path path;
    private final Path offsets;
    private RandomAccessFile writer,index;
    private final Set<BufferedReader> readers=new HashSet<>();
    private int size;

    public DiskJsonList(Path parent, String prefix) throws IOException {
        Files.createDirectories(parent);
        path=Files.createTempFile(parent,prefix,".jsonl");
        offsets=Files.createTempFile(parent,prefix,".offsets");
        writer=new RandomAccessFile(path.toFile(),"rw");index=new RandomAccessFile(offsets.toFile(),"rw");
    }

    @Override public boolean add(Object value) {
        try {index.writeLong(writer.getFilePointer());Json.write(new BufferedOutputStream(new OutputStream(){public void write(int value)throws IOException{writer.write(value);}public void write(byte[] data,int offset,int length)throws IOException{writer.write(data,offset,length);}}),value);writer.write('\n');size++;return true;}
        catch(IOException e){throw new UncheckedIOException(e);}
    }

    @Override public int size(){return size;}

    @Override public Object get(int index) {
        Objects.checkIndex(index,size);
        try(var positions=new RandomAccessFile(offsets.toFile(),"r");var input=new RandomAccessFile(path.toFile(),"r")){
            positions.seek(index*8L);long start=positions.readLong(),end=index+1<size?positions.readLong():input.length();input.seek(start);
            return Json.read(new InputStream(){long remaining=end-start;public int read()throws IOException{if(remaining==0)return -1;remaining--;return input.read();}public int read(byte[] bytes,int offset,int length)throws IOException{if(remaining==0)return -1;int read=input.read(bytes,offset,(int)Math.min(length,remaining));if(read>0)remaining-=read;return read;}});
        }catch(IOException e){throw new UncheckedIOException(e);}
    }

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
        finally{Files.deleteIfExists(path);Files.deleteIfExists(offsets);}
    }
}
