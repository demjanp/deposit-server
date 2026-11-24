package deposit.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.*;

/** Single-disk ZIP/ZIP64 with a bounded, streamed central directory. */
final class PackageZip {
    private static final long LIMIT = 0xffff_ffffL;
    private static final int LOCAL = 0x04034b50, CENTRAL = 0x02014b50, END = 0x06054b50,
        ZIP64_END = 0x06064b50, LOCATOR = 0x07064b50, DESCRIPTOR = 0x08074b50;
    private static final int UTF8 = 0x800, HAS_DESCRIPTOR = 8;
    private PackageZip() {}

    static final class Writer implements AutoCloseable {
        private final OutputStream archive, directory;
        private final Path directoryPath;
        private final Runnable checkpoint;
        private final byte[] buffer = new byte[64 * 1024];
        private long position, entries;
        private boolean directoryClosed;

        Writer(Path path, Path sidecar, ExportRecords.StorageBudget budget, Runnable checkpoint) throws IOException {
            this.checkpoint = checkpoint;
            directoryPath = sidecar;
            archive = output(path,budget,checkpoint);
            try { directory = output(sidecar,budget,checkpoint); }
            catch (IOException failure) { archive.close(); throw failure; }
        }

        private static OutputStream output(Path path, ExportRecords.StorageBudget budget, Runnable checkpoint) throws IOException {
            return new BufferedOutputStream(PackageValidationContext.accounted(
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW),budget,checkpoint));
        }

        void add(String name, Path file) throws IOException {
            var nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            long size = Files.size(file), offset = position;
            var crc = new CRC32();
            read(file,(bytes,count)->crc.update(bytes,0,count));
            boolean large = size >= LIMIT;
            int version = large || offset >= LIMIT ? 45 : 20;
            var local = new ByteArrayOutputStream();
            put(local,LOCAL,4); put(local,version,2); put(local,UTF8,2);
            put(local,0,2); put(local,0,2); put(local,33,2); put(local,crc.getValue(),4);
            put(local,Math.min(size,LIMIT),4); put(local,Math.min(size,LIMIT),4);
            put(local,nameBytes.length,2); put(local,large?20:0,2); local.write(nameBytes);
            if (large) extra(local,size,size);
            local.writeTo(archive);
            long written = read(file,(bytes,count)->archive.write(bytes,0,count));
            if (written != size) throw invalid("Package member changed while writing");
            position = Math.addExact(position, Math.addExact(local.size(),size));

            var extra = new ByteArrayOutputStream();
            if (large && offset >= LIMIT) extra(extra,size,size,offset);
            else if (large) extra(extra,size,size);
            else if (offset >= LIMIT) extra(extra,offset);
            put(directory,CENTRAL,4); put(directory,version,2); put(directory,version,2); put(directory,UTF8,2);
            put(directory,0,2); put(directory,0,2); put(directory,33,2); put(directory,crc.getValue(),4);
            put(directory,Math.min(size,LIMIT),4); put(directory,Math.min(size,LIMIT),4);
            put(directory,nameBytes.length,2); put(directory,extra.size(),2);
            put(directory,0,2); put(directory,0,2); put(directory,0,2); put(directory,0,4);
            put(directory,Math.min(offset,LIMIT),4); directory.write(nameBytes); extra.writeTo(directory);
            entries = Math.addExact(entries,1);
        }

        private long read(Path file, Bytes action) throws IOException {
            long size = 0;
            try (var input = Files.newInputStream(file)) {
                for (int count; (count=input.read(buffer)) != -1;) {
                    checkpoint.run();
                    action.accept(buffer,count);
                    size = Math.addExact(size,count);
                }
            }
            return size;
        }

        void finish() throws IOException {
            directory.close();
            directoryClosed = true;
            long size = read(directoryPath,(bytes,count)->archive.write(bytes,0,count));
            if (entries >= 0xffff || size >= LIMIT || position >= LIMIT) {
                long end = Math.addExact(position,size);
                put(archive,ZIP64_END,4); put(archive,44,8); put(archive,45,2); put(archive,45,2);
                put(archive,0,4); put(archive,0,4); put(archive,entries,8); put(archive,entries,8);
                put(archive,size,8); put(archive,position,8);
                put(archive,LOCATOR,4); put(archive,0,4); put(archive,end,8); put(archive,1,4);
            }
            put(archive,END,4); put(archive,0,2); put(archive,0,2);
            put(archive,Math.min(entries,0xffff),2); put(archive,Math.min(entries,0xffff),2);
            put(archive,Math.min(size,LIMIT),4); put(archive,Math.min(position,LIMIT),4); put(archive,0,2);
        }

        @Override public void close() throws IOException {
            try (archive) { if (!directoryClosed) directory.close(); }
        }
    }

    private static void extra(OutputStream out, long... values) throws IOException {
        put(out,1,2); put(out,values.length*8,2);
        for (long value : values) put(out,value,8);
    }

    private static void put(OutputStream out,long value,int bytes) throws IOException {
        for (int index=0; index<bytes; index++) {
            out.write((int)value & 255);
            value >>>= 8;
        }
    }

    @FunctionalInterface private interface Bytes { void accept(byte[] bytes,int count)throws IOException; }
    @FunctionalInterface interface Member { void read(String name, InputStream data, long size)throws IOException; }

    static void read(Path archive, Map<String,Long> spans, Runnable checkpoint, Member member) throws IOException {
        try (var file = new RandomAccessFile(archive.toFile(),"r")) {
            read(file,spans,null,checkpoint,member);
        }
    }

    static void readVerified(RandomAccessFile file, Predicate<String> selected, Runnable checkpoint, Member member) throws IOException {
        read(file,null,selected,checkpoint,member);
    }

    private static void read(RandomAccessFile file,Map<String,Long> spans,Predicate<String> selected,
            Runnable checkpoint,Member member)throws IOException {
        try {
            var end = end(file);
            long nextCentral = end.start();
            for (long index=0; index<end.count(); index++) {
                checkpoint.run();
                file.seek(nextCentral);
                if (number(file,4)!=unsigned(CENTRAL)) throw invalid("Invalid ZIP central directory");
                file.skipBytes(2);
                long version=number(file,2), flags=number(file,2), method=number(file,2);
                validateMethod(version,flags,method);
                file.skipBytes(4);
                long crc=number(file,4), compressed=number(file,4), size=number(file,4);
                int nameLength=(int)number(file,2), extraLength=(int)number(file,2);
                int comment=(int)number(file,2);
                if (number(file,2)!=0) throw invalid("Split ZIP archives are unsupported");
                file.skipBytes(6);
                long offset=number(file,4);
                byte[] nameBytes=bytes(file,nameLength), extras=bytes(file,extraLength);
                boolean wide = compressed==LIMIT || size==LIMIT;
                long[] values=zip64(extras,size,compressed,offset);
                size=values[0]; compressed=values[1]; offset=values[2];
                nextCentral=Math.addExact(file.getFilePointer(),comment);
                if (nextCentral>end.end() || offset>=end.start()) throw invalid("ZIP coordinates cross the central directory");
                String name = new String(nameBytes,StandardCharsets.US_ASCII);
                if(selected!=null&&!selected.test(name))continue;
                long localEnd = readLocal(file,offset,nameBytes,name,flags,method,crc,compressed,size,wide,end.start(),checkpoint,member);
                if (spans!=null&&spans.put(Long.toString(offset),localEnd)!=null) throw invalid("Overlapping ZIP local members");
            }
            if(nextCentral!=end.end()) throw invalid("ZIP central directory length differs");
            if(spans==null)return; // The exact archive already passed the full span check.
            long offset=0;
            for (long index=0; index<end.count(); index++) {
                checkpoint.run();
                Long next=spans.get(Long.toString(offset));
                if(next==null || next<=offset) throw invalid("ZIP local data has a gap or overlapping member");
                offset=next;
            }
            if(offset!=end.start()) throw invalid("ZIP has extra or missing local data");
        } catch(ArithmeticException|EOFException failure) {
            throw new IOException("Invalid ZIP coordinates or truncated archive",failure);
        }
    }

    private static long readLocal(RandomAccessFile file,long offset,byte[] nameBytes,String name,long flags,long method,
            long crc,long compressed,long size,boolean wide,long start,Runnable checkpoint,Member member)throws IOException {
        file.seek(offset);
        if(number(file,4)!=unsigned(LOCAL)) throw invalid("Missing ZIP local header");
        long version=number(file,2);
        if (number(file,2)!=flags || number(file,2)!=method) throw invalid("ZIP local member flags differ");
        validateMethod(version,flags,method);
        file.skipBytes(4);
        long localCrc=number(file,4), localCompressed=number(file,4), localSize=number(file,4);
        int nameLength=(int)number(file,2), extraLength=(int)number(file,2);
        if(!Arrays.equals(nameBytes,bytes(file,nameLength))) throw invalid("ZIP local member name differs");
        byte[] extra=bytes(file,extraLength);
        boolean hasDescriptor=(flags & HAS_DESCRIPTOR)!=0;
        long[] local=zip64(extra,localSize,localCompressed,-1);
        if (!hasDescriptor && (local[0]!=size || local[1]!=compressed || localCrc!=crc))
            throw invalid("ZIP local member length or CRC differs");
        if (hasDescriptor && (localCrc!=0 && localCrc!=crc || local[0]!=0 && local[0]!=size || local[1]!=0 && local[1]!=compressed))
            throw invalid("ZIP local member length or CRC differs");
        long data=file.getFilePointer(), end=Math.addExact(data,compressed);
        if(end>start || method==0 && compressed!=size) throw invalid("ZIP member length differs");

        var range = new InputStream() {
            long remaining=compressed;
            @Override public int read() throws IOException {
                if(remaining==0)return -1;
                remaining--;
                return file.readUnsignedByte();
            }
            @Override public int read(byte[] bytes,int offset,int requested)throws IOException {
                if(requested==0)return 0;
                if(remaining==0)return -1;
                int count=(int)Math.min(remaining,requested);
                file.readFully(bytes,offset,count);
                remaining-=count;
                return count;
            }
        };
        var inflater = method==8?new Inflater(true):null;
        var checksum = new CRC32();
        try (var content=inflater==null?range:new InflaterInputStream(range,inflater,64*1024)) {
            var verified = new FilterInputStream(content) {
                long bytes;
                @Override public int read()throws IOException {
                    var single=new byte[1];return read(single,0,1)==-1?-1:single[0]&255;
                }
                @Override public int read(byte[] buffer,int position,int requested)throws IOException {
                    checkpoint.run();
                    int count=in.read(buffer,position,requested);
                    if(count>0) {
                        bytes=Math.addExact(bytes,count);
                        if(bytes>size)throw invalid("ZIP expanded member length differs");
                        checksum.update(buffer,position,count);
                    } else if(count==-1 && bytes!=size)throw invalid("ZIP expanded member length differs");
                    return count;
                }
            };
            member.read(name,verified,size);
            if(verified.read()!=-1 || checksum.getValue()!=crc || file.getFilePointer()!=end
                    || inflater!=null && (!inflater.finished() || inflater.getBytesRead()!=compressed))
                throw invalid("ZIP member CRC or compressed length differs");
        } finally { if(inflater!=null)inflater.end(); }

        if(hasDescriptor) {
            int width=wide || localCompressed==LIMIT || localSize==LIMIT?8:4;
            boolean signature=number(file,4)==unsigned(DESCRIPTOR);
            long descriptorEnd=Math.addExact(end,4L+2L*width+(signature?4L:0L));
            if(descriptorEnd>start)throw invalid("Truncated ZIP descriptor");
            if(signature && !matchesDescriptor(file,crc,compressed,size,width)) {
                // A signature-free descriptor can have the same four bytes as its CRC.
                file.seek(end+4);
                if(crc!=unsigned(DESCRIPTOR) || number(file,width)!=compressed || number(file,width)!=size)
                    throw invalid("ZIP descriptor differs");
                descriptorEnd-=4;
            } else if(!signature) {
                file.seek(end);
                if(!matchesDescriptor(file,crc,compressed,size,width))throw invalid("ZIP descriptor differs");
            }
            end=descriptorEnd;
        }
        return end;
    }

    private static boolean matchesDescriptor(RandomAccessFile file,long crc,long compressed,long size,int width)throws IOException {
        return number(file,4)==crc && number(file,width)==compressed && number(file,width)==size;
    }

    private record End(long start,long end,long count) {}

    private static End end(RandomAccessFile file)throws IOException {
        long length=file.length(), minimum=Math.max(0,length-22-0xffff), at=length-22;
        for(;at>=minimum;at--) {
            file.seek(at);
            if(number(file,4)!=unsigned(END))continue;
            file.seek(at+20);
            if(at+22+number(file,2)==length)break;
        }
        if(at<minimum)throw invalid("Missing ZIP end record");
        file.seek(at+4);
        if(number(file,2)!=0 || number(file,2)!=0)throw invalid("Split ZIP archives are unsupported");
        long diskCount=number(file,2), count=number(file,2), size=number(file,4), start=number(file,4);
        if(diskCount!=count)throw invalid("Split ZIP archives are unsupported");
        if(at>=20) {
            file.seek(at-20);
            if(number(file,4)==unsigned(LOCATOR)) {
                if(number(file,4)!=0)throw invalid("Split ZIP64 archives are unsupported");
                long zip64=number(file,8);
                if(number(file,4)!=1 || zip64>at-20-56)throw invalid("Invalid ZIP64 locator");
                file.seek(zip64);
                if(number(file,4)!=unsigned(ZIP64_END))throw invalid("Missing ZIP64 end record");
                long record=number(file,8);
                if(record<44 || Math.addExact(zip64,Math.addExact(12L,record))!=at-20)throw invalid("Invalid ZIP64 end record");
                file.skipBytes(4);
                if(number(file,4)!=0 || number(file,4)!=0)throw invalid("Split ZIP64 archives are unsupported");
                long disk=number(file,8), wideCount=number(file,8), wideSize=number(file,8), wideStart=number(file,8);
                if(disk!=wideCount || count!=0xffff && count!=wideCount || size!=LIMIT && size!=wideSize
                        || start!=LIMIT && start!=wideStart)throw invalid("ZIP64 end coordinates differ");
                count=wideCount; size=wideSize; start=wideStart; at=zip64;
            }
        }
        if(Math.addExact(start,size)!=at || count>size/46)throw invalid("Invalid ZIP central directory range");
        return new End(start,at,count);
    }

    private static long[] zip64(byte[] extra,long size,long compressed,long offset)throws IOException {
        var values=new long[]{size,compressed,offset};
        try(var input=new ByteArrayInputStream(extra)) {
            boolean present=false;
            while(input.available()>0) {
                long kind=number(input,2);
                int length=(int)number(input,2);
                var data=input.readNBytes(length);
                if(data.length!=length)throw invalid("Truncated ZIP extra field");
                if(kind!=1)continue;
                if(present)throw invalid("Duplicate ZIP64 extra field");
                present=true;
                var fields=new ByteArrayInputStream(data);
                for(int index=0;index<values.length;index++)if(values[index]==LIMIT)values[index]=number(fields,8);
            }
            if(!present && (size==LIMIT || compressed==LIMIT || offset==LIMIT))throw invalid("Missing ZIP64 coordinates");
        }
        return values;
    }

    private static void validateMethod(long version,long flags,long method)throws IOException {
        long allowed=UTF8|HAS_DESCRIPTOR|(method==8?6:0);
        if(version>45 || (flags & ~allowed)!=0 || method!=0 && method!=8)throw invalid("Unsupported ZIP feature");
    }

    private static long unsigned(int number) { return Integer.toUnsignedLong(number); }
    private static IOException invalid(String message) { return new IOException(message); }
    private static long number(RandomAccessFile file,int count)throws IOException {
        long value=0;
        for(int i=0;i<count;i++)value|=(long)file.readUnsignedByte()<<(i*8);
        if(value<0)throw invalid("ZIP64 value exceeds signed Long range");
        return value;
    }
    private static long number(InputStream input,int count)throws IOException {
        long value=0;
        for(int i=0;i<count;i++){
            int next=input.read();
            if(next<0)throw new EOFException();
            value|=(long)next<<(i*8);
        }
        if(value<0)throw invalid("ZIP64 value exceeds signed Long range");
        return value;
    }
    private static byte[] bytes(RandomAccessFile file,int count)throws IOException {
        byte[] bytes=new byte[count];
        file.readFully(bytes);
        return bytes;
    }
}
