package deposit.common;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private disk JSON tree and spill indexes. No package payload or identity table is retained in heap. */
final class PackageWorkspace implements AutoCloseable {
    private static final int HEADER=37;
    private final Path directory;
    private final byte[] textBuffer=new byte[8192];
    private final javax.crypto.Mac placement;private long probes;
    long probeCount(){return probes;}
    private final RandomAccessFile nodes;private final Runnable checkpoint;private int reads;
    private final List<DiskMap<?>> indexes=new ArrayList<>();
    private final DiskMap<Long> fields;
    PackageWorkspace(Path parent)throws IOException{this(parent,()->{});}
    PackageWorkspace(Path parent,Runnable checkpoint)throws IOException{this.checkpoint=checkpoint;directory=Files.createTempDirectory(parent,"semantic-");nodes=new RandomAccessFile(directory.resolve("nodes").toFile(),"rw");nodes.writeByte(0);try{placement=javax.crypto.Mac.getInstance("HmacSHA256");byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);placement.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));}catch(GeneralSecurityException e){throw new IOException("Unable to initialize private spill index",e);}fields=map();}
    private void cancelled()throws InterruptedIOException{checkpoint.run();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Package validation cancelled");}
    private long allocate(int type)throws IOException{cancelled();long at=nodes.length();nodes.seek(at);nodes.write(new byte[HEADER]);nodes.seek(at);nodes.writeByte(type);return at;}
    private void number(long at,int field,long value)throws IOException{nodes.seek(at+field);nodes.writeLong(value);}
    private long number(long at,int field)throws IOException{nodes.seek(at+field);return nodes.readLong();}
    private int count(long at)throws IOException{nodes.seek(at+1);return nodes.readInt();}
    private void count(long at,int value)throws IOException{nodes.seek(at+1);nodes.writeInt(value);}
    private int type(long at)throws IOException{nodes.seek(at);return nodes.readUnsignedByte();}
    private long next(long at)throws IOException{return number(at,13);}
    private static UncheckedIOException unchecked(IOException e){return new UncheckedIOException(e);}
    <T> DiskMap<T> map(){try{var map=new DiskMap<T>();indexes.add(map);return map;}catch(IOException e){throw unchecked(e);}}
    int mark(){return indexes.size();}
    void release(int mark)throws IOException{while(indexes.size()>mark)indexes.getLast().dispose();}
    Set<String> set(){return Collections.newSetFromMap(this.<Boolean>map());}
    Map<String,Object> parse(Path file)throws IOException{
        try(var reader=new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(file),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))),2)){
            var parser=new Parser(reader);long root=parser.value(0);if(parser.nonspace()!=-1)throw new IOException("Trailing JSON content");Object value=read(root);if(!(value instanceof Map<?,?>))throw new IOException("Expected JSON object");return ValueCodec.map(value);
        }
    }
    Map<String,Object> parse(Reader source)throws IOException{
        try(var reader=new PushbackReader(new BufferedReader(source),2)){
            var parser=new Parser(reader);long root=parser.value(0);if(parser.nonspace()!=-1)throw new IOException("Trailing JSON content");return ValueCodec.map(read(root));
        }
    }
    private long write(Object value)throws IOException{
        if(value instanceof TreeMapView view&&view.owner()==this)return view.at;
        if(value instanceof TreeListView view&&view.owner()==this)return view.at;
        if(value instanceof Text text&&text.owner()==this)return text.at;
        if(value==null)return allocate('n');
        if(value instanceof Boolean b)return allocate(b?'t':'f');
        if(value instanceof Map<?,?> map){long at=allocate('o'),last=0;int size=0;for(var entry:map.entrySet()){long key=string((String)entry.getKey()),child=write(entry.getValue());number(key,5,child);fields.put(at+":"+fingerprint((String)entry.getKey()),key);if(last==0)number(at,5,key);else number(last,13,key);last=key;size++;}count(at,size);return at;}
        if(value instanceof Collection<?> list){long at=allocate('a'),last=0;int size=0;for(Object item:list){long child=detach(write(item));if(last==0)number(at,5,child);else number(last,13,child);last=child;size++;}count(at,size);return at;}
        if(value instanceof String s)return string(s);
        if(value instanceof Number n){long at=string(n.toString());nodes.seek(at);nodes.writeByte('d');return at;}
        throw new IOException("Unsupported staged value");
    }
    private long detach(long at)throws IOException{byte[] header=new byte[HEADER];nodes.seek(at);nodes.readFully(header);long copy=nodes.length();nodes.seek(copy);nodes.write(header);number(copy,13,0);if(type(at)=='o')number(copy,21,number(at,21)==0?at:number(at,21));return copy;}
    private long string(String text)throws IOException{long at=allocate('s');number(at,29,nodes.length());nodes.seek(nodes.length());for(int start=0;start<text.length();){int count=Math.min(text.length()-start,textBuffer.length/2);for(int i=0;i<count;i++){char value=text.charAt(start+i);textBuffer[i*2]=(byte)(value>>>8);textBuffer[i*2+1]=(byte)value;}nodes.write(textBuffer,0,count*2);start+=count;}number(at,21,text.length());return at;}
    private Object read(long at)throws IOException{return read(at,false);}
    private Object read(long at,boolean payload)throws IOException{if((++reads&1023)==0)cancelled();return switch(type(at)){case 'n'->null;case 't'->true;case 'f'->false;case 'o'->new TreeMapView(at,payload);case 'a'->new TreeListView(at,payload);case 's'->payload||number(at,21)<=32768?text(at):new Text(at);case 'd'->{String s=text(at);try{yield s.indexOf('.')>=0||s.indexOf('e')>=0||s.indexOf('E')>=0?Double.valueOf(s):(Object)Long.valueOf(s);}catch(NumberFormatException e){throw new IOException("Invalid JSON number",e);}}default->throw new IOException("Invalid staged token");};}
    /** Lazy application view: only each requested scalar is materialized, never the full JSON tree. */
    Map<String,Object> payload(Map<String,Object> source){return source instanceof TreeMapView view?new TreeMapView(view.at,true):source;}
    private String text(long at)throws IOException{long size=number(at,21);if(size>Integer.MAX_VALUE)throw new IOException("String exceeds package bounds");char[] chars=new char[(int)size];nodes.seek(number(at,29));for(int start=0;start<chars.length;){int count=Math.min(chars.length-start,textBuffer.length/2);nodes.readFully(textBuffer,0,count*2);for(int i=0;i<count;i++)chars[start+i]=(char)((textBuffer[i*2]&255)<<8|(textBuffer[i*2+1]&255));start+=count;}return new String(chars);}
    private String key(long at)throws IOException{Object value=read(at);return value instanceof Text text?text.fingerprint(): (String)value;}
    private Object fieldValue(long keyAt,long mapAt)throws IOException{return fieldValue(keyAt,mapAt,false);}
    private Object fieldValue(long keyAt,long mapAt,boolean payload)throws IOException{
        if(payload)return read(number(keyAt,5),true);
        String key=key(keyAt);long valueAt=number(keyAt,5);
        if(Set.of("comment","data","description","summary").contains(key)&&type(valueAt)=='s')return new Text(valueAt).fingerprint();
        long owner=number(mapAt,21)==0?mapAt:number(mapAt,21);
        boolean journal=false;
        if(key.equals("touches")||key.equals("dependencies")){Long revision=fields.get(owner+":"+fingerprint("revisionId")),changes=fields.get(owner+":"+fingerprint("changes"));journal=revision!=null&&changes!=null&&type(number(revision,5))=='s'&&type(number(changes,5))=='a';}
        return PackageIdentity.field(key,key.equals("wkt")?materializeText(read(valueAt)):read(valueAt),journal);
    }
    final class Text {
        final long at;Text(long at){this.at=at;}PackageWorkspace owner(){return PackageWorkspace.this;}
        long length(){try{return number(at,21);}catch(IOException e){throw unchecked(e);}}
        String materialize(){try{return text(at);}catch(IOException e){throw unchecked(e);}}
        @Override public boolean equals(Object other){return other==this||other instanceof PackageWorkspace.Text text&&length()==text.length()&&fingerprint().equals(text.fingerprint());}
        @Override public int hashCode(){return fingerprint().hashCode();}
        String fingerprint(){try{var digest=MessageDigest.getInstance("SHA-256");long remaining=number(at,21)*2,position=number(at,29);byte[] buffer=new byte[8192];while(remaining>0){nodes.seek(position);int length=(int)Math.min(buffer.length,remaining);nodes.readFully(buffer,0,length);digest.update(buffer,0,length);position+=length;remaining-=length;}return "sha256-utf16:"+HexFormat.of().formatHex(digest.digest());}catch(IOException e){throw unchecked(e);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    }
    static String fingerprint(Object value){if(value instanceof PackageWorkspace.Text text)return text.fingerprint();if(!(value instanceof String s))throw new IllegalArgumentException("Expected String");try{var digest=MessageDigest.getInstance("SHA-256");for(int i=0;i<s.length();i++){char c=s.charAt(i);digest.update((byte)(c>>>8));digest.update((byte)c);}return "sha256-utf16:"+HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static Object materializeText(Object value){return value instanceof PackageWorkspace.Text text?text.materialize():value;}
    static void requireNameKeys(Map<String,Object> map){if(map instanceof PackageWorkspace.TreeMapView view)try{for(long child=view.owner().number(view.at,5);child!=0;child=view.owner().next(child))if(view.owner().number(child,21)>510)throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Pattern name");}catch(IOException e){throw unchecked(e);}}
    static Map<String,Object> dictionary(Map<String,Object> map){
        if(!(map instanceof PackageWorkspace.TreeMapView view))return map;
        return view.dictionary();
    }
    private final class TreeMapView extends AbstractMap<String,Object>{
        final long at;final boolean payload;TreeMapView(long at,boolean payload){this.at=at;this.payload=payload;}PackageWorkspace owner(){return PackageWorkspace.this;}
        Map<String,Object> dictionary(){return new AbstractMap<>(){
            public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){
                public int size(){return TreeMapView.this.size();}
                public Iterator<Entry<String,Object>> iterator(){try{return new Iterator<>(){long child=number(at,5);public boolean hasNext(){return child!=0;}public Entry<String,Object> next(){if(child==0)throw new NoSuchElementException();try{long current=child;child=PackageWorkspace.this.next(child);return new SimpleImmutableEntry<>(new Text(current).fingerprint(),fieldValue(current,at));}catch(IOException e){throw unchecked(e);}}};}catch(IOException e){throw unchecked(e);}}
            };}
        };}
        @Override public int size(){try{return count(at);}catch(IOException e){throw unchecked(e);}}
        @Override public Object get(Object key){if(!(key instanceof String s))return null;Long child;try{child=fields.get((number(at,21)==0?at:number(at,21))+":"+fingerprint(s));}catch(IOException e){throw unchecked(e);}try{return child==null?null:fieldValue(child,at,payload);}catch(IOException e){throw unchecked(e);}}
        @Override public boolean containsKey(Object key){try{return key instanceof String s&&fields.containsKey((number(at,21)==0?at:number(at,21))+":"+fingerprint(s));}catch(IOException e){throw unchecked(e);}}
        @Override public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){
            public int size(){return TreeMapView.this.size();}
            public Iterator<Entry<String,Object>> iterator(){try{return new Iterator<>(){long child=number(at,5);public boolean hasNext(){return child!=0;}public Entry<String,Object> next(){if(child==0)throw new NoSuchElementException();try{long current=child;child=PackageWorkspace.this.next(child);return new SimpleImmutableEntry<>(payload?text(current):key(current),fieldValue(current,at,payload));}catch(IOException e){throw unchecked(e);}}};}catch(IOException e){throw unchecked(e);}}
        };}
    }
    private final class TreeListView extends AbstractList<Object>{
        final long at;final boolean payload;TreeListView(long at,boolean payload){this.at=at;this.payload=payload;}PackageWorkspace owner(){return PackageWorkspace.this;}
        @Override public int size(){try{return count(at);}catch(IOException e){throw unchecked(e);}}
        @Override public Object get(int index){Objects.checkIndex(index,size());try{long child=number(at,5);for(int i=0;i<index;i++)child=next(child);return read(child,payload);}catch(IOException e){throw unchecked(e);}}
        @Override public boolean equals(Object other){if(other==this)return true;if(!(other instanceof List<?> list)||size()!=list.size())return false;var left=iterator();var right=list.iterator();while(left.hasNext())if(!Objects.equals(left.next(),right.next()))return false;return true;}
        @Override public int hashCode(){int hash=1;for(Object value:this)hash=31*hash+Objects.hashCode(value);return hash;}
        @Override public Iterator<Object> iterator(){try{return new Iterator<>(){long child=number(at,5);public boolean hasNext(){return child!=0;}public Object next(){if(child==0)throw new NoSuchElementException();try{long current=child;child=PackageWorkspace.this.next(child);return read(current,payload);}catch(IOException e){throw unchecked(e);}}};}catch(IOException e){throw unchecked(e);}}
    }
    private long placementHash(String key)throws IOException{
        for(int start=0;start<key.length();){cancelled();int count=Math.min(key.length()-start,textBuffer.length/2);for(int i=0;i<count;i++){char value=key.charAt(start+i);textBuffer[i*2]=(byte)(value>>>8);textBuffer[i*2+1]=(byte)value;}placement.update(textBuffer,0,count*2);start+=count;}
        byte[] digest=placement.doFinal();long hash=0;for(int i=0;i<8;i++)hash=hash<<8|(digest[i]&255L);return hash;
    }
    final class DiskMap<T> extends AbstractMap<String,T> implements AutoCloseable {
        private static final int SLOT=24;
        private RandomAccessFile table;private Path path;private int capacity=1024,size=0,used=0;
        private final byte[] slotBytes=new byte[SLOT];
        DiskMap()throws IOException{path=Files.createTempFile(directory,"index-",".bin");table=new RandomAccessFile(path.toFile(),"rw");table.setLength(capacity*(long)SLOT);}
        private long field(int offset){long value=0;for(int i=offset;i<offset+8;i++)value=value<<8|(slotBytes[i]&255L);return value;}
        private void field(int offset,long value){for(int i=offset+7;i>=offset;i--){slotBytes[i]=(byte)value;value>>>=8;}}
        private void readSlot(RandomAccessFile file,int index)throws IOException{file.seek(index*(long)SLOT);file.readFully(slotBytes);}
        private void writeSlot(int index,long hash,long key,long value)throws IOException{field(0,hash);field(8,key);field(16,value);table.seek(index*(long)SLOT);table.write(slotBytes);}
        private int slot(String key,long hash)throws IOException{int index=(int)hash&(capacity-1),deleted=-1;for(int i=0;i<capacity;i++){if((++probes&1023)==0)cancelled();readSlot(table,index);long k=field(8);if(k==0)return deleted>=0?deleted:index;if(k==-1){if(deleted<0)deleted=index;}else if(field(0)==hash&&key.equals(PackageWorkspace.this.key(k)))return index;index=(index+1)&(capacity-1);}if(deleted>=0)return deleted;throw new IOException("Spill index is full");}
        @SuppressWarnings("unchecked") public T get(Object key){if(!(key instanceof String s))return null;try{int index=slot(s,placementHash(s));readSlot(table,index);return field(8)<=0?null:(T)read(field(16));}catch(IOException e){throw unchecked(e);}}
        public boolean containsKey(Object key){if(!(key instanceof String s))return false;try{int index=slot(s,placementHash(s));readSlot(table,index);return field(8)>0;}catch(IOException e){throw unchecked(e);}}
        public int size(){return size;}
        @SuppressWarnings("unchecked") public T put(String key,T value){try{long hash=placementHash(key);int index=slot(key,hash);readSlot(table,index);long existing=field(8),prior=field(16);T old=existing>0?(T)read(prior):null;if(used*2>=capacity){grow();index=slot(key,hash);readSlot(table,index);existing=field(8);}long k=existing>0?existing:string(key),v=write(value);writeSlot(index,hash,k,v);if(existing<=0){size++;if(existing==0)used++;}return old;}catch(IOException e){throw unchecked(e);}}
        @SuppressWarnings("unchecked") public T remove(Object key){if(!(key instanceof String s))return null;try{long hash=placementHash(s);int index=slot(s,hash);readSlot(table,index);if(field(8)<=0)return null;T old=(T)read(field(16));writeSlot(index,hash,-1,0);size--;return old;}catch(IOException e){throw unchecked(e);}}
        private void grow()throws IOException{var previous=table;Path oldPath=path;int oldCapacity=capacity;capacity=Math.multiplyExact(capacity,2);path=Files.createTempFile(directory,"index-",".bin");table=new RandomAccessFile(path.toFile(),"rw");table.setLength(capacity*(long)SLOT);used=size;
            try{for(int i=0;i<oldCapacity;i++){if((i&1023)==0)cancelled();readSlot(previous,i);long hash=field(0),key=field(8),value=field(16);if(key>0){int index=(int)hash&(capacity-1);while(true){if((++probes&1023)==0)cancelled();readSlot(table,index);if(field(8)==0)break;index=(index+1)&(capacity-1);}writeSlot(index,hash,key,value);}}}finally{previous.close();Files.delete(oldPath);}}
        public Set<Entry<String,T>> entrySet(){return new AbstractSet<>(){public int size(){return size;}public Iterator<Entry<String,T>> iterator(){return new Iterator<>(){int index=0;Entry<String,T> pending;@SuppressWarnings("unchecked") private void advance(){try{while(pending==null&&index<capacity){if((index&1023)==0)cancelled();readSlot(table,index++);long key=field(8),value=field(16);if(key>0)pending=new SimpleImmutableEntry<>(PackageWorkspace.this.key(key),(T)read(value));}}catch(IOException e){throw unchecked(e);}}public boolean hasNext(){advance();return pending!=null;}public Entry<String,T> next(){advance();if(pending==null)throw new NoSuchElementException();var row=pending;pending=null;return row;}};}};}
        public void clear(){try{table.setLength(0);capacity=1024;size=used=0;table.setLength(capacity*(long)SLOT);}catch(IOException e){throw unchecked(e);}}
        void dispose()throws IOException{close();Files.deleteIfExists(path);indexes.removeIf(index->index==this);}
        public void close()throws IOException{table.close();}
    }
    private final class Parser {
        final PushbackReader reader;Parser(PushbackReader reader){this.reader=reader;}
        int nonspace()throws IOException{int ch;while((ch=reader.read())!=-1){if(ch!=' '&&ch!='\t'&&ch!='\r'&&ch!='\n')return ch;}return -1;}
        void expect(int expected)throws IOException{if(nonspace()!=expected)throw new IOException("Invalid JSON delimiter");}
        long value(int depth)throws IOException{
            if(depth>1000)throw new IOException("JSON nesting exceeds parser safety bound");int ch=nonspace();
            if(ch=='"')return quoted();
            if(ch=='{'||ch=='['){long at=allocate(ch=='{'?'o':'a'),last=0;int count=0,close=ch=='{'?'}':']';int first=nonspace();if(first==close)return at;reader.unread(first);
                while(true){long child;if(ch=='{'){expect('"');child=quoted();String fingerprint=new Text(child).fingerprint();if(fields.put(at+":"+fingerprint,child)!=null)throw new IOException("Duplicate JSON property");expect(':');number(child,5,value(depth+1));}else child=value(depth+1);
                    if(last==0)number(at,5,child);else number(last,13,child);last=child;count++;int delimiter=nonspace();if(delimiter==close)break;if(delimiter!=',')throw new IOException("Invalid JSON container");}
                count(at,count);return at;
            }
            if(ch=='n'){literal("ull");return allocate('n');}if(ch=='t'){literal("rue");return allocate('t');}if(ch=='f'){literal("alse");return allocate('f');}
            if(ch=='-'||ch>='0'&&ch<='9'){var number=new StringBuilder();number.append((char)ch);while((ch=reader.read())!=-1&&(ch=='-'||ch=='+'||ch=='.'||ch=='e'||ch=='E'||ch>='0'&&ch<='9')){if(number.length()>1024)throw new IOException("Invalid scientific number");number.append((char)ch);}if(ch!=-1)reader.unread(ch);String s=number.toString();if(!s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IOException("Invalid JSON number");long at=string(s);nodes.seek(at);nodes.writeByte('d');return at;}
            throw new IOException("Invalid JSON token");
        }
        void literal(String tail)throws IOException{for(int i=0;i<tail.length();i++)if(reader.read()!=tail.charAt(i))throw new IOException("Invalid JSON literal");}
        long quoted()throws IOException{long at=allocate('s'),position=nodes.length();number(at,29,position);nodes.seek(position);int length=0;byte[] buffer=new byte[8192];int used=0;
            while(true){int ch=reader.read();if(ch==-1)throw new IOException("Unterminated JSON String");if(ch=='"')break;if(ch<32)throw new IOException("Unescaped JSON control character");if(ch=='\\'){ch=reader.read();ch=switch(ch){case '"','\\','/'->ch;case 'b'->8;case 'f'->12;case 'n'->10;case 'r'->13;case 't'->9;case 'u'->{int decoded=0;for(int i=0;i<4;i++){int digit=Character.digit(reader.read(),16);if(digit<0)throw new IOException("Invalid unicode escape");decoded=decoded*16+digit;}yield decoded;}default->throw new IOException("Invalid JSON escape");};}
                buffer[used++]=(byte)(ch>>>8);buffer[used++]=(byte)ch;length++;if(used==buffer.length){cancelled();nodes.write(buffer);used=0;}}
            if(used>0)nodes.write(buffer,0,used);number(at,21,length);return at;
        }
    }
    @Override public void close()throws IOException{IOException failure=null;for(var index:indexes)try{index.close();}catch(IOException e){failure=e;}try{nodes.close();}catch(IOException e){failure=e;}try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}if(failure!=null)throw failure;}
}
