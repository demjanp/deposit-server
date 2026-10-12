package deposit.service;

import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import org.neo4j.graphdb.Node;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/** Operational table staging and tabular procedure entry points. */
public final class TabularService extends BaseService {
    private static final long TABLE_TTL=86_400_000L;
    private final ResourceRepository resources;
    public TabularService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Object execute(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.load.csv","deposit.load.xlsx","deposit.load.gpkg"->load(procedure,request);
            case "deposit.get.table"->table(request);
            case "deposit.get.tablePage"->page(request);
            case "deposit.get.layout"->new LayoutService(c).layout(request);
            case "deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg"->save(procedure,request);
            case "deposit.import.rows"->new ImportService(c).importRows(request);
            default->throw ExceptionHandler.fail("VALIDATION_FAILED","Unsupported tabular operation");
        };
    }

    private void operational(String role){
        scoped(role);
        if(c.operationId==null||c.expectedRevision!=null)c.fail("VALIDATION_FAILED","Operational writes require operationId and no expectedRevision");
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","One mutation per transaction");
        c.state.mutation=true;repo.lockActor(c.actorId);
    }
    private void lockOperationalScope(String role){
        repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,role);c.revision=repo.sequence(c.namespaceId);
    }
    private String operationalHash(String procedure,Map<String,Object> request){
        Node binding=repo.binding(c.actorId,c.operationId);
        if(binding!=null&&!c.namespaceId.equals(binding.getProperty("scope")))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
        return CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request)));
    }
    private Map<String,Object> replayOperational(Node receipt,String hash){
        c.replayed=true;
        if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");
        if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");
        return repo.replay(receipt,c.namespaceId).data();
    }

    private Map<String,Object> load(String procedure,Map<String,Object> request){
        fields(request,"resourceId","formatOptions");operational("editor");lockOperationalScope("editor");
        String hash=operationalHash(procedure,request);Node receipt=repo.receipt(c.namespaceId,c.operationId);
        // A committed result remains recoverable even after its table or input expires.
        // Replaying never reads or renews staging, and never recreates expired content.
        if(receipt!=null)return replayOperational(receipt,hash);
        String id=RequestContext.uuid(request.get("resourceId"));Node source=resources.resource(c.namespaceId,id);
        if(source==null||!resources.accessible(source,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        Node location=resources.location(id);resources.lock(id);var content=resources.verify(location);
        requireContentLimit(content.bytes(),"Source file exceeds the content limit");
        Map<String,Object> options=request.get("formatOptions")==null?Map.of():ValueCodec.map(request.get("formatOptions"));
        boolean csv=procedure.endsWith(".csv"),xlsx=procedure.endsWith(".xlsx"),gpkg=procedure.endsWith(".gpkg");
        if(!csv&&!xlsx&&!gpkg)c.fail("VALIDATION_FAILED","Unsupported table format");
        if(csv)fields(options,"delimiter","headerRow","columnTypes","dateFormats","decimalSeparator");
        else if(xlsx)fields(options,"sheet","headerRow","columnTypes","dateFormats","decimalSeparator");else fields(options,"layer","geometryColumn","columnTypes","dateFormats","decimalSeparator");
        char delimiter=csv?delimiter(options.getOrDefault("delimiter",",")):',';int header=(int)positive(options.getOrDefault("headerRow",1L),"headerRow");
        String decimal=String.valueOf(options.getOrDefault("decimalSeparator","."));if(!Set.of(".",",").contains(decimal))c.fail("VALIDATION_FAILED","decimalSeparator must be '.' or ','");
        Map<String,Object> types=options.containsKey("columnTypes")?ValueCodec.map(options.get("columnTypes")):Map.of();Map<String,Object> dateFormats=options.containsKey("dateFormats")?ValueCodec.map(options.get("dateFormats")):Map.of();
        Path input=null,table=null,rowIndex=null;boolean completed=false;String tableId=UUID.randomUUID().toString();
        try{
            input=resources.files.path("staging/tables/"+tableId+".source");Files.createDirectories(input.getParent());
            try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(input,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}
            if(Files.size(input)!=content.bytes()||!ResourceFiles.checksum(input).equals(content.checksum()))c.fail("RESOURCE_CORRUPT","Source resource changed during table load");
            table=resources.files.path("staging/tables/"+tableId+".json");rowIndex=resources.files.path("staging/tables/"+tableId+".idx");
            var parsed=csv?parseCsv(input,table,delimiter,header,types,dateFormats,decimal):xlsx?parseXlsx(input,table,options,header,types,dateFormats,decimal,c.state.kernel::assertOpen):GeoPackageAdapter.load(input,table,options,types,dateFormats,decimal,c.state.kernel::assertOpen);
            requireContentLimit(Files.size(table),"Generated table staging exceeds the content limit");
            long indexed=Json.indexArray(table,"rows",rowIndex);if(indexed!=((Number)parsed.get("rowCount")).longValue())throw new IOException("Staged row index count differs from parsed table");
            long now=resources.now();resources.record("DepositTabularTable",tableId,Json.map("namespaceId",c.namespaceId,"creatorId",c.actorId,"sourceResourceId",id,"path",resources.files.root.relativize(table).toString().replace('\\','/'),"rowIndexPath",resources.files.root.relativize(rowIndex).toString().replace('\\','/'),"createdAt",now,"lastAccessAt",now,"expiresAt",now+TABLE_TTL,"columnsJson",Json.write(parsed.get("columns")),"rowCount",parsed.get("rowCount"),"valid",parsed.get("valid"),"diagnosticsJson",Json.write(parsed.get("diagnostics")),"diagnosticsTruncated",parsed.get("diagnosticsTruncated")));
            resources.record("DepositResourceLease",UUID.randomUUID().toString(),Json.map("resourceId",id,"namespaceId",c.namespaceId,"type","table","tableId",tableId,"expires",Long.MAX_VALUE));
            Map<String,Object> result=Json.map("tableId",tableId,"columns",parsed.get("columns"),"rowCount",parsed.get("rowCount"),"valid",parsed.get("valid"),"diagnostics",parsed.get("diagnostics"),"diagnosticsTruncated",parsed.get("diagnosticsTruncated"));
            repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));
            // Metadata, protective lease and receipt commit together. Also clean files
            // if the caller later rolls back an otherwise successful load transaction.
            Path completedTable=table,completedIndex=rowIndex;boolean[] committed={false};
            c.state.afterCommit.add(()->committed[0]=true);
            c.state.release.add(()->{if(!committed[0]){discard(completedTable);discard(completedIndex);}});
            Files.deleteIfExists(input);input=null;completed=true;return result;
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage table");}
        finally{if(!completed){if(table!=null)try{Files.deleteIfExists(table);}catch(IOException ignored){}if(rowIndex!=null)try{Files.deleteIfExists(rowIndex);}catch(IOException ignored){}if(input!=null)try{Files.deleteIfExists(input);}catch(IOException ignored){}}}
    }

    static Map<String,Object> parseXlsx(Path file,Path destination,Map<String,Object> options,int headerRow,Map<String,Object> types,Map<String,Object> formats,String decimal,Runnable check)throws IOException{
        preflightXlsx(file,check,ExchangePackageCodec.MAX_ENTRIES,ExchangePackageCodec.MAX_EXPANDED);
        ZipSecureFile.setMaxEntrySize(ExchangePackageCodec.MAX_EXPANDED);ZipSecureFile.setMinInflateRatio(0.01);
        Path workspace=Files.createTempDirectory(destination.getParent(),"xlsx-");
        try(OPCPackage container=OPCPackage.open(file.toFile(),org.apache.poi.openxml4j.opc.PackageAccess.READ);OutputStream output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){
            XSSFReader reader=new XSSFReader(container);StylesTable styles=reader.getStylesTable();boolean date1904=workbookUses1904(reader);String selected=string(options.get("sheet"));
            try(SharedStringStore shared=new SharedStringStore(workspace)){InputStream stringsData=reader.getSharedStringsData();if(stringsData!=null)try(InputStream strings=stringsData){parseSharedStrings(strings,shared,check);}
                XSSFReader.SheetIterator sheets=(XSSFReader.SheetIterator)reader.getSheetIterator();InputStream sheet=null;while(sheets.hasNext()){InputStream candidate=sheets.next();if(Objects.equals(sheets.getSheetName(),selected)){sheet=candidate;break;}candidate.close();}if(sheet==null)throw ExceptionHandler.fail("VALIDATION_FAILED","Selected XLSX sheet does not exist");
                InputStream selectedSheet=sheet;try(selectedSheet){var handler=new XlsxTableHandler(output,headerRow,types,formats,decimal,styles,shared,date1904,check);parseXml(selectedSheet,handler);return handler.finish();}
            }
        }catch(SAXException|org.apache.poi.openxml4j.exceptions.OpenXML4JException e){throw new IOException("Invalid XLSX container",e);}
        finally{try(var paths=Files.walk(workspace)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
    }

    static void preflightXlsx(Path file,Runnable check,int maximumEntries,long maximumExpandedBytes)throws IOException{
        long expanded=0;int entries=0;try(var zip=new java.util.zip.ZipFile(file.toFile())){var all=zip.entries();while(all.hasMoreElements()){var entry=all.nextElement();if(++entries>maximumEntries)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX entry limit exceeded");try(InputStream in=zip.getInputStream(entry)){byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1){check.run();expanded+=n;if(expanded>maximumExpandedBytes)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX expanded size limit exceeded");}}}}
    }

    private static XMLReader xmlReader()throws SAXException,javax.xml.parsers.ParserConfigurationException{
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(false);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);XMLReader reader=factory.newSAXParser().getXMLReader();reader.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));return reader;
    }
    private static void parseXml(InputStream input,DefaultHandler handler)throws IOException,SAXException{try{XMLReader reader=xmlReader();reader.setContentHandler(handler);reader.parse(new InputSource(input));}catch(javax.xml.parsers.ParserConfigurationException e){throw new IOException("Unable to initialize secure XLSX XML parser",e);}}
    private static boolean workbookUses1904(XSSFReader reader)throws IOException,SAXException,org.apache.poi.openxml4j.exceptions.InvalidFormatException{
        final boolean[] result={false};try(InputStream input=reader.getWorkbookData()){parseXml(input,new DefaultHandler(){public void startElement(String uri,String local,String name,Attributes attrs){if("workbookPr".equals(name)){String value=attrs.getValue("date1904");result[0]="1".equals(value)||"true".equalsIgnoreCase(value);}}});}return result[0];
    }
    private static final class SharedStringStore implements AutoCloseable {
        private final RandomAccessFile values,index;private final LinkedHashMap<Integer,String> cache=new LinkedHashMap<>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,String> eldest){return size()>256;}};private int count;
        SharedStringStore(Path directory)throws IOException{values=new RandomAccessFile(directory.resolve("shared-strings.dat").toFile(),"rw");index=new RandomAccessFile(directory.resolve("shared-strings.idx").toFile(),"rw");}
        void add(String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);index.writeLong(values.length());index.writeInt(bytes.length);values.seek(values.length());values.write(bytes);count++;}
        String get(int id)throws IOException{if(id<0||id>=count)throw new IOException("Invalid shared string index");String value=cache.get(id);if(value!=null)return value;index.seek((long)id*12);long offset=index.readLong();int length=index.readInt();if(length<0||length>ExchangePackageCodec.MAX_EXPANDED)throw new IOException("Shared string exceeds XLSX bounds");byte[] bytes=new byte[length];values.seek(offset);values.readFully(bytes);value=new String(bytes,StandardCharsets.UTF_8);cache.put(id,value);return value;}
        public void close()throws IOException{try{index.close();}finally{values.close();}}
    }
    private static void parseSharedStrings(InputStream input,SharedStringStore store,Runnable check)throws IOException,SAXException{
        parseXml(input,new DefaultHandler(){final StringBuilder text=new StringBuilder();boolean inItem,inText,inPhonetic;public void startElement(String uri,String local,String name,Attributes attrs){if("si".equals(name)){check.run();inItem=true;text.setLength(0);}else if(inItem&&"rPh".equals(name))inPhonetic=true;else if(inItem&&!inPhonetic&&"t".equals(name))inText=true;}
            public void characters(char[] ch,int start,int length){if(inText)text.append(ch,start,length);}public void endElement(String uri,String local,String name){if("t".equals(name))inText=false;else if("rPh".equals(name))inPhonetic=false;else if("si".equals(name)){try{store.add(text.toString());}catch(IOException e){throw new UncheckedIOException(e);}inItem=false;}}});
    }
    private static int columnIndex(String reference){int index=0;int i=0;while(i<reference.length()&&Character.isLetter(reference.charAt(i))){char ch=Character.toUpperCase(reference.charAt(i++));index=Math.addExact(Math.multiplyExact(index,26),ch-'A'+1);}if(i==0)return -1;return index-1;}
    private record XlsxCell(String type,int style,String value,boolean hasValue,boolean formula,boolean error){}
    private static final class XlsxTableHandler extends DefaultHandler {
        private final Writer output;private final int headerRow;private final Map<String,Object> typeMap,formats;private final String decimal;private final StylesTable styles;private final SharedStringStore shared;private final boolean date1904;private final Runnable check;private final List<Map<String,Object>> diagnostics=new ArrayList<>();
        private final TreeMap<Integer,XlsxCell> cells=new TreeMap<>();private final StringBuilder text=new StringBuilder();private final Map<String,Object> counts=new HashMap<>();private final List<Map<String,Object>> columns=new ArrayList<>();private String sheetName="";private String cellType="",value="";private int style=-1,currentRow=-1,currentColumn=-1,width=0;private long rowCount=0,errorCount=0,lastPhysical=0,dimensionLast=0;private boolean inCell,inValue,inFormula,inInlineText,inPhonetic,formula,hasValue,cellError,valid=true,headerReady,first=true,finished;
        XlsxTableHandler(OutputStream output,int headerRow,Map<String,Object> types,Map<String,Object> formats,String decimal,StylesTable styles,SharedStringStore shared,boolean date1904,Runnable check){this.output=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));this.headerRow=headerRow;typeMap=types;this.formats=formats;this.decimal=decimal;this.styles=styles;this.shared=shared;this.date1904=date1904;this.check=check;}
        public void startElement(String uri,String local,String name,Attributes attrs){
            if("dimension".equals(name)){String ref=attrs.getValue("ref");if(ref!=null){int colon=ref.indexOf(':');String end=colon>=0?ref.substring(colon+1):ref;dimensionLast=rowNumber(end);}}
            if("row".equals(name)){String raw=attrs.getValue("r");currentRow=raw==null?(int)lastPhysical+1:Integer.parseInt(raw);cells.clear();}
            else if("c".equals(name)){inCell=true;cellType=Objects.toString(attrs.getValue("t"),"");String rawStyle=attrs.getValue("s");style=rawStyle==null?-1:Integer.parseInt(rawStyle);currentColumn=columnIndex(Objects.toString(attrs.getValue("r"),""));if(currentColumn<0)throw new IllegalArgumentException("XLSX cell has no reference");value="";text.setLength(0);hasValue=false;formula=false;cellError="e".equals(cellType);}
            else if(inCell&&"f".equals(name)){formula=true;inFormula=true;text.setLength(0);}
            else if(inCell&&"v".equals(name)){inValue=true;text.setLength(0);}
            else if(inCell&&!inPhonetic&&"t".equals(name)&&"inlineStr".equals(cellType)){inInlineText=true;}
            else if("rPh".equals(name))inPhonetic=true;
        }
        public void characters(char[] ch,int start,int length){if(inValue||inFormula||inInlineText)text.append(ch,start,length);}
        public void endElement(String uri,String local,String name){
            if("v".equals(name)&&inValue){value=text.toString();hasValue=true;inValue=false;}
            else if("f".equals(name)&&inFormula)inFormula=false;
            else if("t".equals(name)&&inInlineText)inInlineText=false;
            else if("rPh".equals(name))inPhonetic=false;
            else if("is".equals(name)&&"inlineStr".equals(cellType)){value=text.toString();hasValue=true;}
            else if("c".equals(name)&&inCell){cells.put(currentColumn,new XlsxCell(cellType,style,value,hasValue,formula,cellError));width=Math.max(width,currentColumn+1);inCell=false;}
            else if("row".equals(name)&&currentRow>0){emitRow(currentRow,cells);lastPhysical=currentRow;currentRow=-1;}
        }
        private void emitRow(int physical,Map<Integer,XlsxCell> values){
            if(physical<headerRow)return;if(physical-headerRow>1_048_575)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX row limit exceeded");
            if(physical==headerRow){width=Math.max(width,values.isEmpty()?0:Collections.max(values.keySet())+1);if(width<1||width>16_384)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX column limit exceeded");var names=new ArrayList<String>();Set<String> unique=new HashSet<>();for(int col=0;col<width;col++){String name=cellValue(values.get(col),true,null);if(name.isEmpty()||!unique.add(java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC)))throw ExceptionHandler.fail("VALIDATION_FAILED","Header names must be nonempty and unique");names.add(name);columns.add(Json.map("name",name,"type",typeMap.containsKey(name)?ValueCodec.type(typeMap.get(name)):"String"));}for(String key:typeMap.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","columnTypes names an unknown column");for(String key:formats.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","dateFormats names an unknown column");try{output.write("{\"columns\":");output.write(Json.write(columns));output.write(",\"rows\":[");headerReady=true;}catch(IOException e){throw new UncheckedIOException(e);}return;}
            if(!headerReady)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");for(long missing=lastPhysical+1;missing<physical;missing++)if(missing>headerRow)writeDataRow(Map.of(),Long.toString(rowCount+1));writeDataRow(values,Long.toString(rowCount+1));
        }
        private void writeDataRow(Map<Integer,XlsxCell> row,String id){check.run();rowCount++;Map<String,Object> outputValues=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int col=0;col<columns.size();col++){String name=(String)columns.get(col).get("name");XlsxCell cell=row.get(col);presence.put(name,cell!=null);String raw="";try{raw=cell!=null&&cell.error()?cell.value():cellValue(cell,false,(String)columns.get(col).get("type"));if(cell!=null&&cell.formula()&&!cell.hasValue())throw new FormulaCacheException();if(cell!=null&&cell.error())throw new IllegalArgumentException("XLSX cell contains an error");Object converted=cell==null||raw.isEmpty()?raw:parseCell((String)columns.get(col).get("type"),raw,decimal,formats.get(name));outputValues.put(name,converted);}catch(RuntimeException ex){outputValues.put(name,raw);valid=false;errorCount++;String code=ex instanceof FormulaCacheException?"FORMULA_CACHED":"CELL_CONVERSION";if(diagnostics.size()<1000)diagnostics.add(Json.map("severity","error","code",code,"rowId",id,"column",name,"message",code.equals("FORMULA_CACHED")?"Formula has no usable cached result":"Cell cannot be parsed as "+columns.get(col).get("type")));}
            }try{if(!first)output.write(',');first=false;output.write(Json.write(Json.map("rowId",id,"values",outputValues,"presence",presence)));}catch(IOException e){throw new UncheckedIOException(e);}}
        private String cellValue(XlsxCell cell,boolean header,String targetType){if(cell==null)return "";if(header&&cell.formula()&&!cell.hasValue())throw ExceptionHandler.fail("VALIDATION_FAILED","Formula has no cached result in XLSX header");if(header&&cell.error())throw ExceptionHandler.fail("VALIDATION_FAILED","XLSX cell contains an error");String raw=cell.value();try{return switch(cell.type()){case "s"->shared.get(Integer.parseInt(raw));case "b"->"1".equals(raw)?"true":"false";case "inlineStr","str"->raw;case "e"->raw;default->{if(!cell.hasValue()||raw.isEmpty())yield "";if(cell.style()>=0&&cell.style()<styles.getNumCellStyles()){var xfs=styles.getCellXfAt(cell.style());short formatId=(short)xfs.getNumFmtId();String format=styles.getNumberFormatAt(formatId);if(DateUtil.isADateFormat(formatId,format)){var date=DateUtil.getLocalDateTime(Double.parseDouble(raw),date1904);yield switch(Objects.toString(targetType,"")){case "Date"->date.toLocalDate().toString();case "Time","LocalTime"->date.toLocalTime().toString();default->date.toString();};}}yield new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString();}};}catch(IOException|NumberFormatException error){throw new IllegalArgumentException("Invalid XLSX cell value",error);}}
        Map<String,Object> finish(){if(finished)return Map.of();finished=true;long end=Math.max(lastPhysical,dimensionLast);if(!headerReady)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");for(long missing=lastPhysical+1;missing<=end;missing++)if(missing>headerRow)writeDataRow(Map.of(),Long.toString(rowCount+1));try{output.write("],\"rowCount\":");output.write(Long.toString(rowCount));output.write(",\"valid\":");output.write(Boolean.toString(valid));output.write(",\"diagnostics\":");output.write(Json.write(diagnostics));output.write(",\"diagnosticsTruncated\":");output.write(Boolean.toString(errorCount>diagnostics.size()));output.write('}');output.flush();return Json.map("columns",columns,"rowCount",rowCount,"valid",valid,"diagnostics",diagnostics,"diagnosticsTruncated",errorCount>diagnostics.size());}catch(IOException e){throw new UncheckedIOException(e);}}
        private long rowNumber(String reference){int at=0;while(at<reference.length()&&!Character.isDigit(reference.charAt(at)))at++;return at==reference.length()?0:Long.parseLong(reference.substring(at));}
        private final class FormulaCacheException extends RuntimeException{}
    }

    private Map<String,Object> parseCsv(Path file,Path destination,char delim,int headerRow,Map<String,Object> typeMap,Map<String,Object> formats,String decimal)throws IOException{
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);try(var input=new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(file),decoder)),1);var output=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            int first=input.read();if(first!=0xfeff&&first!=-1)input.unread(first);List<String> header=null;for(int i=1;i<=headerRow;i++){header=readCsvRecord(input,delim);if(header==null)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");}
            List<String> names=header;Set<String> unique=new HashSet<>();List<Map<String,Object>> columns=new ArrayList<>();for(String name:names){if(name.isEmpty()||!unique.add(java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC)))throw ExceptionHandler.fail("VALIDATION_FAILED","Header names must be nonempty and unique");columns.add(Json.map("name",name,"type",typeMap.containsKey(name)?ValueCodec.type(typeMap.get(name)):"String"));}
            for(String key:typeMap.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","columnTypes names an unknown column");for(String key:formats.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","dateFormats names an unknown column");
            output.write("{\"columns\":");output.write(Json.write(columns));output.write(",\"rows\":[");List<Map<String,Object>> diagnostics=new ArrayList<>();boolean valid=true;long rowId=0,errorCount=0;List<String> record;boolean firstRow=true;
            while((record=readCsvRecord(input,delim))!=null){c.state.kernel.assertOpen();if(record.size()==1&&record.getFirst().isEmpty())record=new ArrayList<>(Collections.nCopies(names.size(),""));if(record.size()!=names.size())throw ExceptionHandler.fail("VALIDATION_FAILED","CSV record field count differs from header");rowId++;Map<String,Object> values=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int j=0;j<names.size();j++){String name=names.get(j),cell=record.get(j),type=(String)columns.get(j).get("type");presence.put(name,true);try{values.put(name,cell.isEmpty()?"":parseCell(type,cell,decimal,formats.get(name)));}catch(RuntimeException ex){values.put(name,cell);valid=false;errorCount++;if(diagnostics.size()<1000)diagnostics.add(Json.map("severity","error","code","CELL_CONVERSION","rowId",Long.toString(rowId),"column",name,"message","Cell cannot be parsed as "+type));}}
                if(!firstRow)output.write(',');firstRow=false;output.write(Json.write(Json.map("rowId",Long.toString(rowId),"values",values,"presence",presence)));
            }
            output.write("],\"rowCount\":");output.write(Long.toString(rowId));output.write(",\"valid\":");output.write(Boolean.toString(valid));output.write(",\"diagnostics\":");output.write(Json.write(diagnostics));output.write(",\"diagnosticsTruncated\":");output.write(Boolean.toString(errorCount>diagnostics.size()));output.write('}');return Json.map("columns",columns,"rowCount",rowId,"valid",valid,"diagnostics",diagnostics,"diagnosticsTruncated",errorCount>diagnostics.size());
        }catch(CharacterCodingException ex){throw new IOException("Invalid UTF-8",ex);}
    }
    private List<String> readCsvRecord(PushbackReader input,char delimiter)throws IOException{
        List<String> fields=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,atFieldStart=true,consumed=false;int raw;
        while((raw=input.read())!=-1){consumed=true;char ch=(char)raw;if(quoted){if(ch=='"'){int next=input.read();if(next=='"')cell.append('"');else{quoted=false;if(next!=-1)input.unread(next);}}else cell.append(ch);continue;}
            if(ch=='"'){if(!atFieldStart)throw ExceptionHandler.fail("VALIDATION_FAILED","Unexpected CSV quote");quoted=true;atFieldStart=false;continue;}if(ch==delimiter){fields.add(cell.toString());cell.setLength(0);atFieldStart=true;continue;}if(ch=='\r'||ch=='\n'){if(ch=='\r'){int next=input.read();if(next!='\n'&&next!=-1)input.unread(next);}fields.add(cell.toString());return fields;}cell.append(ch);atFieldStart=false;
        }
        if(quoted)throw ExceptionHandler.fail("VALIDATION_FAILED","Unterminated CSV quote");if(!consumed&&cell.isEmpty()&&fields.isEmpty())return null;fields.add(cell.toString());return fields;
    }
    private Map<String,Object> parseRecords(List<List<String>> records,int headerRow,Map<String,Object> typeMap,Map<String,Object> formats,String decimal){
        if(records.size()<headerRow)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");
        List<String> names=new ArrayList<>(records.get(headerRow-1));Set<String> unique=new HashSet<>();List<Map<String,Object>> columns=new ArrayList<>();
        for(String name:names){if(name.isEmpty()||!unique.add(java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC)))throw ExceptionHandler.fail("VALIDATION_FAILED","Header names must be nonempty and unique");String type=typeMap.containsKey(name)?ValueCodec.type(typeMap.get(name)):"String";columns.add(Json.map("name",name,"type",type));}
        for(String key:typeMap.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","columnTypes names an unknown column");for(String key:formats.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","dateFormats names an unknown column");
        List<Map<String,Object>> rows=new ArrayList<>(),diagnostics=new ArrayList<>();boolean valid=true;long rowId=0,errorCount=0;
        for(int i=headerRow;i<records.size();i++){
            c.state.kernel.assertOpen();
            List<String> record=records.get(i);if(i==records.size()-1&&record.size()==1&&record.getFirst().isEmpty())continue;
            if(record.size()!=names.size())throw ExceptionHandler.fail("VALIDATION_FAILED","CSV record field count differs from header");rowId++;
            Map<String,Object> values=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int j=0;j<names.size();j++){
                String name=names.get(j),cell=record.get(j),type=(String)columns.get(j).get("type");presence.put(name,true);
                try{values.put(name,cell.isEmpty()?"":parseCell(type,cell,decimal,formats.get(name)));}
                catch(RuntimeException ex){values.put(name,cell);valid=false;errorCount++;if(diagnostics.size()<1000)diagnostics.add(Json.map("severity","error","code","CELL_CONVERSION","rowId",Long.toString(rowId),"column",name,"message","Cell cannot be parsed as "+type));}
            }
            rows.add(Json.map("rowId",Long.toString(rowId),"values",values,"presence",presence));
        }
        return Json.map("columns",columns,"rows",rows,"rowCount",(long)rows.size(),"valid",valid,"diagnostics",diagnostics,"diagnosticsTruncated",errorCount>diagnostics.size());
    }

    static Object parseTableCell(String type,String cell,String decimal,Object format){return parseCell(type,cell,decimal,format);}
    private static Object parseCell(String type,String cell,String decimal,Object format){
        if(type.equals("String"))return cell;
        String value=cell;if(decimal.equals(",")&&(type.equals("Float")))value=value.replace(',','.');
        if(format!=null){DateTimeFormatter formatter=DateTimeFormatter.ofPattern(string(format),Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);return switch(type){case "Date"->LocalDate.parse(cell,formatter).toString();case "Time"->OffsetTime.parse(cell,formatter).toString();case "LocalTime"->LocalTime.parse(cell,formatter).toString();case "DateTime"->OffsetDateTime.parse(cell,formatter).toString();case "LocalDateTime"->LocalDateTime.parse(cell,formatter).toString();default->throw new IllegalArgumentException();};}
        return switch(type){case "Integer"->Long.parseLong(value);case "Float"->{double d=Double.parseDouble(value);if(!Double.isFinite(d))throw new IllegalArgumentException();yield d;}case "Boolean"->{if(!Set.of("true","false").contains(value))throw new IllegalArgumentException();yield Boolean.valueOf(value);}case "Date"->LocalDate.parse(value).toString();case "Time"->OffsetTime.parse(value).toString();case "LocalTime"->LocalTime.parse(value).toString();case "DateTime"->value.contains("[")?ZonedDateTime.parse(value).toString():OffsetDateTime.parse(value).toString();case "LocalDateTime"->LocalDateTime.parse(value).toString();case "List","DDict"->Json.read(value);case "DGeometry"->parseGeometryValue(value,null,null);case "Point"->value;default->throw new IllegalArgumentException("Unsupported CSV type");};
    }

    public static Map<String,Object> parseGeometryValue(String text,Object explicitSrid,Object explicitVertical){Map<String,Object> parsed=parseGeometryText(text,explicitSrid,explicitVertical);return Json.map("geometryType",parsed.get("geometryType"),"coordinates",parsed.get("coordinates"),"wkt",parsed.get("wkt"),"srid",parsed.get("srid"),"verticalSrid",parsed.get("verticalSrid"),"axisOrder",parsed.get("axisOrder"),"dimensions",parsed.get("dimensions"));}
    private static Map<String,Object> parseGeometryText(String text,Object explicitSrid,Object explicitVertical){String value=text;Long srid=explicitSrid instanceof Number n?n.longValue():null,vertical=explicitVertical instanceof Number n?n.longValue():null;java.util.regex.Matcher h=java.util.regex.Pattern.compile("^SRID=(-?\\d+);").matcher(value);if(h.find()){long parsed=Long.parseLong(h.group(1));if(srid!=null&&srid!=parsed)throw new IllegalArgumentException("SRID conflicts with mapping");srid=parsed;value=value.substring(h.end());}java.util.regex.Matcher v=java.util.regex.Pattern.compile("^VERT_SRID=(-?\\d+);").matcher(value);if(v.find()){long parsed=Long.parseLong(v.group(1));if(vertical!=null&&vertical!=parsed)throw new IllegalArgumentException("vertical SRID conflicts with mapping");vertical=parsed;value=value.substring(v.end());}value=value.replaceFirst("(?i)^([A-Z]+)Z(?=\\s*\\()","$1 Z");return GeometryCodec.fromWkt(value,srid,vertical);}

    static List<List<String>> csv(String text,char delimiter){List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=false;}else cell.append(ch);}else if(ch=='"'){if(cell.length()!=0)throw ExceptionHandler.fail("VALIDATION_FAILED","Unexpected CSV quote");quoted=true;}else if(ch==delimiter){row.add(cell.toString());cell.setLength(0);}else if(ch=='\r'||ch=='\n'){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);}else cell.append(ch);}if(quoted)throw ExceptionHandler.fail("VALIDATION_FAILED","Unterminated CSV quote");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}return rows;}
    private static char delimiter(Object raw){String s=string(raw);if(s.length()!=1||"\"\r\n\0".indexOf(s.charAt(0))>=0)throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid CSV delimiter");return s.charAt(0);}
    private long positive(Object value,String field){long n=integer(value);if(n<1||n>1_000_000)c.fail("LIMIT_EXCEEDED",field+" must be positive");return n;}

    private Node tableNode(Object raw){String id=RequestContext.uuid(raw);repo.lockNamespace(c.namespaceId);Node table=repo.find("DepositTabularTable","id",id);if(table!=null&&!c.namespaceId.equals(table.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Table belongs to another Namespace");if(table==null||number(table,"expiresAt",0)<=resources.now())c.fail("NOT_FOUND","Table is unavailable");if(!c.actorId.equals(table.getProperty("creatorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("NOT_FOUND","Table is unavailable");table.setProperty("lastAccessAt",resources.now());table.setProperty("expiresAt",resources.now()+TABLE_TTL);return table;}
    private static long number(Node n,String key,long d){return ((Number)n.getProperty(key,d)).longValue();}
    private Map<String,Object> readData(Node table){try(InputStream in=Files.newInputStream(resources.files.path((String)table.getProperty("path")))){return Json.read(in);}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");return Map.of();}}
    private Stream<Results.QueryRowResult> table(Map<String,Object> request){fields(request,"tableId");readContext();scoped("viewer");Node table=tableNode(request.get("tableId"));List<Map<String,Object>> columns=Json.readList((String)table.getProperty("columnsJson")).stream().map(ValueCodec::map).toList();final Json.ArrayReader reader;try{reader=new Json.ArrayReader(resources.files.path((String)table.getProperty("path")),"rows");}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");return Stream.empty();}Iterator<Results.QueryRowResult> iterator=new Iterator<>(){Results.QueryRowResult next;boolean loaded,done;public boolean hasNext(){if(loaded)return true;if(done)return false;try{c.state.kernel.assertOpen();Map<String,Object> row=reader.next();if(row==null){done=true;return false;}next=new Results.QueryRowResult(Json.map("rowId",row.get("rowId"),"values",row.get("values")),columns,ValueCodec.map(row.get("presence")),c.meta());loaded=true;return true;}catch(IOException e){throw new UncheckedIOException(e);}}public Results.QueryRowResult next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator,Spliterator.ORDERED),false).onClose(()->{try{reader.close();}catch(IOException e){throw new UncheckedIOException(e);}});}
    private DataRow page(Map<String,Object> request){fields(request,"tableId","pageSize","cursor");readContext();scoped("viewer");Node table=tableNode(request.get("tableId"));int size=(int)integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");long count=number(table,"rowCount",0),offset=0;String hash=Json.hash(CoreRepository.id(table));if(request.containsKey("cursor")){Map<String,Object> cur=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cur.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");if(!c.actorId.equals(cur.get("actor"))||!hash.equals(cur.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match table");if(((Number)cur.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Namespace revision changed");offset=((Number)cur.get("offset")).longValue();if(offset<0||offset>count)c.fail("VALIDATION_FAILED","Invalid cursor offset");}var rows=new ArrayList<Map<String,Object>>(Math.min(size,200));try(RandomAccessFile index=new RandomAccessFile(resources.files.path((String)table.getProperty("rowIndexPath")).toFile(),"r")){index.seek(offset*Long.BYTES);Path data=resources.files.path((String)table.getProperty("path"));for(int i=0;i<size&&offset+i<count;i++){c.state.kernel.assertOpen();long byteOffset=index.readLong();rows.add(Json.objectAt(data,byteOffset));}}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");}long end=offset+rows.size();String next=null;if(end<count)next=CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",end,"expires",System.currentTimeMillis()+900000L));Map<String,Object> meta=c.meta();meta.put("nextCursor",next);return new DataRow(Json.map("columns",Json.readList((String)table.getProperty("columnsJson")),"rows",rows),meta);}

    private Map<String,Object> save(String procedure,Map<String,Object> request){
        fields(request,"source","parameters","formatOptions");operational("viewer");
        String hash=operationalHash(procedure,request);Node receipt=repo.receipt(c.namespaceId,c.operationId);
        // Recover the original export before touching its source. A query may have
        // changed and a staged input may have expired since the original commit.
        if(receipt!=null){lockOperationalScope("viewer");return replayOperational(receipt,hash);}
        Map<String,Object> source=ValueCodec.map(request.get("source")),format=request.get("formatOptions")==null?Map.of():ValueCodec.map(request.get("formatOptions"));fields(source,"tableId","query");
        if(source.containsKey("tableId")==source.containsKey("query"))c.fail("VALIDATION_FAILED","Exactly one of tableId or query is required");
        boolean csv=procedure.endsWith(".csv"),xlsx=procedure.endsWith(".xlsx"),gpkg=procedure.endsWith(".gpkg");if(!csv&&!xlsx&&!gpkg)c.fail("VALIDATION_FAILED","Unsupported export format");
        if(csv)fields(format,"filename","delimiter");else if(xlsx)fields(format,"filename","sheet");else fields(format,"filename","layer");char delimiter=csv?delimiter(format.getOrDefault("delimiter",",")):',';String filename=string(format.getOrDefault("filename",gpkg?"export.gpkg":xlsx?"export.xlsx":"export.csv"));String layer=string(format.getOrDefault("layer","data"));
        String extension=gpkg?".gpkg":xlsx?".xlsx":".csv";if(filename.contains("/")||filename.contains("\\")||!filename.toLowerCase(Locale.ROOT).endsWith(extension))c.fail("VALIDATION_FAILED","filename must have the matching format extension");String sheetName=string(format.getOrDefault("sheet","Data"));if(xlsx&&(sheetName.isBlank()||sheetName.length()>31||sheetName.matches(".*[\\[\\]:*?/\\\\].*")||sheetName.startsWith("'")||sheetName.endsWith("'")))c.fail("VALIDATION_FAILED","Invalid XLSX sheet name");
        String tableId=null;List<?> columns;long rowCount;Path tablePath,queryStage=null;Long collectionRevision=null;
        if(source.containsKey("tableId")){tableId=RequestContext.uuid(source.get("tableId"));Node table=tableNode(tableId);if(!Boolean.TRUE.equals(table.getProperty("valid")))c.fail("VALIDATION_FAILED","Invalid staged table cannot be exported");columns=Json.readList((String)table.getProperty("columnsJson"));rowCount=number(table,"rowCount",0);tablePath=stagedFile((String)table.getProperty("path"));}
        else {Map<String,Object> queryRequest=Json.map("query",source.get("query"),"parameters",request.getOrDefault("parameters",Map.of()));collectionRevision=c.revision;Results.DataRow schema=queryPage(queryRequest,collectionRevision);columns=(List<?>)schema.data.get("columns");if(columns.stream().anyMatch(raw->"Object".equals(ValueCodec.map(raw).get("type"))))c.fail("VALIDATION_FAILED","Native Object projections cannot be exported; select scalar Descriptors");try{queryStage=resources.files.path("staging/exports/query-"+UUID.randomUUID()+".json");Files.createDirectories(queryStage.getParent());rowCount=stageQuery(queryRequest,columns,queryStage,collectionRevision);tablePath=queryStage;c.server.storagePoint("query-export-before-lock");if(repo.sequence(c.namespaceId)!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");}catch(IOException e){discard(queryStage);c.fail("RESOURCE_CORRUPT","Unable to stage query export");return Map.of();}catch(RuntimeException e){discard(queryStage);throw e;}}
        // DResource refusal is performed before any output or grant is created.
        if(columns.stream().anyMatch(raw->"DResource".equals(ValueCodec.map(raw).get("type"))))try(var reader=new Json.ArrayReader(tablePath,"rows")){Map<String,Object> row;while((row=reader.next())!=null){Map<String,Object> values=ValueCodec.map(row.get("values"));for(Object raw:columns){Map<String,Object> column=ValueCodec.map(raw);if("DResource".equals(column.get("type"))){Object value=values.get(column.get("name"));if(value!=null&&!(value instanceof String s&&s.isEmpty()))c.fail("VALIDATION_FAILED","DResource export links are unavailable until the Stage 10 download service is installed");}}}}catch(IOException e){discard(queryStage);c.fail("RESOURCE_CORRUPT","Staged table content unavailable");}catch(RuntimeException e){discard(queryStage);throw e;}
        try{lockOperationalScope("viewer");if(collectionRevision!=null&&c.revision!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");if(collectionRevision!=null)c.server.storagePoint("query-export-after-lock");}catch(RuntimeException e){discard(queryStage);throw e;}
        long revision=c.revision;
        Path stage=null;List<GeoPackageAdapter.Output> gpkgFiles=new ArrayList<>();
        try{
            Files.createDirectories(resources.files.path("staging/exports"));
            if(gpkg){Path directory=resources.files.path("staging/exports");gpkgFiles=GeoPackageAdapter.write(tablePath,columns,filename,layer,directory,c.state.kernel::assertOpen);if(gpkgFiles.size()==1)stage=gpkgFiles.getFirst().path();else{stage=resources.files.path("staging/exports/"+UUID.randomUUID()+".zip");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW))){for(var file:gpkgFiles){zip.putNextEntry(new ZipEntry(file.filename()));Files.copy(file.path(),zip);zip.closeEntry();}}}}
            else if(csv)try(BufferedWriter out=Files.newBufferedWriter(stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);var reader=new Json.ArrayReader(tablePath,"rows")){
                for(int i=0;i<columns.size();i++){if(i>0)out.write(delimiter);writeCsv(out,String.valueOf(ValueCodec.map(columns.get(i)).get("name")),delimiter);}out.write("\r\n");
                Map<String,Object> row;while((row=reader.next())!=null){c.state.kernel.assertOpen();Map<String,Object> values=ValueCodec.map(row.get("values")),presence=ValueCodec.map(row.get("presence"));for(int i=0;i<columns.size();i++){if(i>0)out.write(delimiter);String name=(String)ValueCodec.map(columns.get(i)).get("name");Object value=Boolean.TRUE.equals(presence.get(name))?values.get(name):null;writeCsv(out,exportText((String)ValueCodec.map(columns.get(i)).get("type"),value),delimiter);}out.write("\r\n");}
            } else if(xlsx){stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension);writeXlsx(stage,columns,tablePath,rowCount,sheetName);}
            if(repo.sequence(c.namespaceId)!=revision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");long bytes=Files.size(stage);requireContentLimit(bytes,"Generated export exceeds the content limit");String checksum=ResourceFiles.checksum(stage),resourceId=UUID.randomUUID().toString(),resultFilename=gpkg&&gpkgFiles.size()>1?filename.substring(0,filename.length()-5)+".zip":filename,stored=ResourceFiles.filename(resultFilename,checksum);var content=new ResourceStorage.Content(resourceId,stored,bytes,checksum);resources.lock(resourceId);resources.storage.publish(content,stage);resources.storage.verify(content);
            String media=gpkg&&gpkgFiles.size()>1?"application/zip":gpkg?"application/geopackage+sqlite3":xlsx?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8";long now=resources.now();resources.record("DepositResourceLocation",resourceId,Json.map("namespaceId",c.namespaceId,"storedFilename",stored,"bytes",bytes,"checksum",checksum,"leaseUntil",now+ResourceFiles.LEASE,"everAttached",false,"published",true));resources.record("DResource",resourceId,Json.map("namespaceId",c.namespaceId,"filename",resultFilename,"storedFilename",stored,"mediaType",media,"bytes",bytes,"checksum",checksum,"state","ready","createdBy",c.actorId,"updatedBy",c.actorId,"createdAt",c.timestamp,"updatedAt",c.timestamp));resources.record("DepositExportGrant",UUID.randomUUID().toString(),Json.map("resourceId",resourceId,"namespaceId",c.namespaceId,"actorId",c.actorId,"history",false,"expires",now+ResourceFiles.LEASE));
            List<Map<String,Object>> files=gpkg?gpkgFiles.stream().map(file->Json.map("filename",file.filename(),"bytes",file.bytes(),"checksum",file.checksum())).toList():List.of(Json.map("filename",filename,"bytes",bytes,"checksum",checksum));var warningMap=new LinkedHashMap<String,Map<String,Object>>();boolean warningTruncated=false;if(gpkg)for(var file:gpkgFiles){warningTruncated|=file.warningsTruncated();for(var warning:file.warnings())warningMap.putIfAbsent(Json.write(warning),warning);}List<Map<String,Object>> warnings=warningMap.values().stream().limit(1000).toList();warningTruncated|=warningMap.size()>warnings.size();Map<String,Object> result=Json.map("resourceId",resourceId,"filename",resultFilename,"mediaType",media,"bytes",bytes,"checksum",checksum,"files",files,"warnings",warnings,"warningsTruncated",warningTruncated);repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));return result;
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to finalize tabular export");}finally{if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}for(var file:gpkgFiles)try{Files.deleteIfExists(file.path());}catch(IOException ignored){}if(queryStage!=null)try{Files.deleteIfExists(queryStage);}catch(IOException ignored){}}
    }
    static void requireContentLimit(long bytes,String message){if(bytes<0)throw new IllegalArgumentException("Content size cannot be negative");if(bytes>ResourceFiles.MAX_BYTES)throw ExceptionHandler.fail("LIMIT_EXCEEDED",message);}
    static void requireXlsxDimensions(long dataRows,long columns){if(dataRows<0||columns<0)throw new IllegalArgumentException("XLSX dimensions cannot be negative");if(dataRows>1_048_575L||columns>16_384L)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX row or column limit exceeded");}
    private void writeXlsx(Path destination,List<?> columns,Path tablePath,long rowCount,String sheetName)throws IOException{
        requireXlsxDimensions(rowCount,columns.size());
        try(org.apache.poi.xssf.streaming.SXSSFWorkbook workbook=new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)){
            workbook.setCompressTempFiles(true);Sheet sheet=workbook.createSheet(sheetName);Row header=sheet.createRow(0);for(int i=0;i<columns.size();i++){String name=(String)ValueCodec.map(columns.get(i)).get("name");if(name.length()>32_767)c.fail("LIMIT_EXCEEDED","XLSX header exceeds 32,767 UTF-16 code units");header.createCell(i,CellType.STRING).setCellValue(name);}
            int index=1;try(var reader=new Json.ArrayReader(tablePath,"rows")){Map<String,Object> row;while((row=reader.next())!=null){c.state.kernel.assertOpen();Map<String,Object> values=ValueCodec.map(row.get("values")),presence=ValueCodec.map(row.get("presence"));Row out=sheet.createRow(index++);for(int i=0;i<columns.size();i++){Map<String,Object> col=ValueCodec.map(columns.get(i));String name=(String)col.get("name"),type=(String)col.get("type");if(!Boolean.TRUE.equals(presence.get(name)))continue;Object value=values.get(name);if(value==null)continue;Cell cell=out.createCell(i);xlsxCell(cell,type,value,row.get("rowId"),name);}}}
            try(OutputStream out=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){workbook.write(out);}finally{workbook.dispose();}
        }
    }
    private long stageQuery(Map<String,Object> queryRequest,List<?> columns,Path destination,Long pinnedRevision)throws IOException{
        Stream<Results.QueryRowResult> query=queryStream(queryRequest,pinnedRevision);try(BufferedWriter out=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Stream<Results.QueryRowResult> rows=query){
            out.write("{\"columns\":");out.write(Json.write(columns));out.write(",\"rows\":[");final long[] index={0};Iterator<Results.QueryRowResult> it=rows.iterator();while(it.hasNext()){c.state.kernel.assertOpen();Results.QueryRowResult row=it.next();if(index[0]>0)out.write(',');out.write(Json.write(Json.map("rowId",Long.toString(index[0]+1),"values",row.data,"presence",row.presence)));index[0]++;}out.write("],\"rowCount\":");out.write(Long.toString(index[0]));out.write(",\"valid\":true,\"diagnostics\":[],\"diagnosticsTruncated\":false}");return index[0];}
    }
    private Results.DataRow queryPage(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQueryPage(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private Stream<Results.QueryRowResult> queryStream(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQuery(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private void xlsxCell(Cell cell,String type,Object value,Object rowId,String column){
        if(type.equals("Integer")&&value instanceof Number n){long number=n.longValue();String text=Long.toString(number);if(text.replace("-","").length()<=15&&BigDecimal.valueOf((double)number).toBigInteger().equals(java.math.BigInteger.valueOf(number))){cell.setCellValue((double)number);return;}cell.setCellValue(text);return;}
        if(type.equals("Float")&&value instanceof Number n){double number=n.doubleValue();if(Double.isFinite(number)&&!(number==0&&Double.doubleToRawLongBits(number)<0)){String round=String.format(Locale.ROOT,"%.15g",number);try{if(Double.doubleToRawLongBits(Double.parseDouble(round))==Double.doubleToRawLongBits(number)){cell.setCellValue(number);return;}}catch(NumberFormatException ignored){}}cell.setCellValue(Double.toString(number));return;}
        String text=exportText(type,value);if(text.length()>32_767)c.fail("LIMIT_EXCEEDED","XLSX cell exceeds 32,767 UTF-16 code units at row "+rowId+", column "+column);cell.setCellValue(text);
    }
    static String exportText(String type,Object value){if(value==null)return "";return switch(type){case "List","DDict"->ValueCodec.jsonText(type,value instanceof String s?Json.read(s):value);case "Duration"->ValueCodec.storage("Duration",ValueCodec.normalize("Duration",value)).toString();case "Point"->{Map<String,Object> point=ValueCodec.map(value);List<?> coords=(List<?>)point.get("coordinates");yield "POINT("+String.join(" ",coords.stream().map(Object::toString).toList())+")";}case "DGeometry"->{Map<String,Object> geometry=ValueCodec.map(value);String wkt=((String)geometry.get("wkt")).toUpperCase(Locale.ROOT).replaceFirst("^([A-Z]+) Z ","$1Z ");StringBuilder text=new StringBuilder();Object srid=geometry.get("srid"),vertical=geometry.get("verticalSrid");if(srid instanceof Number n&&n.longValue()>=0)text.append("SRID=").append(n.longValue()).append(';');if(vertical instanceof Number n&&n.longValue()>=0)text.append("VERT_SRID=").append(n.longValue()).append(';');yield text.append(wkt).toString();}default->value.toString();};}
    private Path stagedFile(String relative){try{return resources.files.path(relative);}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Staged table content unavailable");}}
    private static void discard(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    private static void writeCsv(Writer out,String value,char delimiter)throws IOException{boolean quote=value.indexOf(delimiter)>=0||value.indexOf('"')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0;if(quote)out.write('"');for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(ch=='"')out.write("\"\"");else out.write(ch);}if(quote)out.write('"');}
}
