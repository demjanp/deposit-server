package deposit.service;

import deposit.common.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/** Bounded XLSX parsing; preflight, XML security and temporary storage share one owner. */
final class XlsxTableReader {
    private XlsxTableReader() {}
    static Map<String,Object> parseXlsx(Path file,Path destination,Map<String,Object> options,int headerRow,Map<String,Object> types,Map<String,Object> formats,String decimal,Runnable check)throws IOException{
        preflightXlsx(file,check,ExchangePackageCodec.MAX_ENTRIES,ExchangePackageCodec.MAX_EXPANDED);
        ZipSecureFile.setMaxEntrySize(ExchangePackageCodec.MAX_EXPANDED);ZipSecureFile.setMinInflateRatio(0.01);
        Path workspace=Files.createTempDirectory(destination.getParent(),"xlsx-");
        try(OPCPackage container=OPCPackage.open(file.toFile(),org.apache.poi.openxml4j.opc.PackageAccess.READ);OutputStream output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){
            XSSFReader reader=new XSSFReader(container);StylesTable styles=reader.getStylesTable();boolean date1904=workbookUses1904(reader);String selected=Objects.toString(options.get("sheet"), "");
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
        private final Writer output;private final int headerRow;private final Map<String,Object> typeMap,formats;private final String decimal;private final StylesTable styles;private final SharedStringStore shared;private final boolean date1904;private final Runnable check;private final TabularDiagnostics diagnostics=new TabularDiagnostics();
        private final TreeMap<Integer,XlsxCell> cells=new TreeMap<>();private final StringBuilder text=new StringBuilder();private final Map<String,Object> counts=new HashMap<>();private final List<Map<String,Object>> columns=new ArrayList<>();private String sheetName="";private String cellType="",value="";private int style=-1,currentRow=-1,currentColumn=-1,width=0;private long rowCount=0,lastPhysical=0,dimensionLast=0;private boolean inCell,inValue,inFormula,inInlineText,inPhonetic,formula,hasValue,cellError,valid=true,headerReady,first=true,finished;
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
            if(physical==headerRow){width=Math.max(width,values.isEmpty()?0:Collections.max(values.keySet())+1);if(width<1||width>16_384)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX column limit exceeded");var names=new ArrayList<String>();Set<String> unique=new HashSet<>();for(int col=0;col<width;col++){String name=cellValue(values.get(col),true,null);names.add(name);columns.add(TabularPolicy.projectHeader(name,unique,typeMap));}TabularPolicy.validateOptions(names,typeMap,formats);try{TabularJsonWriter.header(output,columns);headerReady=true;}catch(IOException e){throw new UncheckedIOException(e);}return;}
            if(!headerReady)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");for(long missing=lastPhysical+1;missing<physical;missing++)if(missing>headerRow)writeDataRow(Map.of(),Long.toString(rowCount+1));writeDataRow(values,Long.toString(rowCount+1));
        }
        private void writeDataRow(Map<Integer,XlsxCell> row,String id){check.run();rowCount++;Map<String,Object> outputValues=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int col=0;col<columns.size();col++){String name=(String)columns.get(col).get("name");XlsxCell cell=row.get(col);presence.put(name,cell!=null);String raw="";try{raw=cell!=null&&cell.error()?cell.value():cellValue(cell,false,(String)columns.get(col).get("type"));if(cell!=null&&cell.formula()&&!cell.hasValue())throw new FormulaCacheException();if(cell!=null&&cell.error())throw new IllegalArgumentException("XLSX cell contains an error");Object converted=cell==null||raw.isEmpty()?raw:TabularValueCodec.parseCell((String)columns.get(col).get("type"),raw,decimal,formats.get(name));outputValues.put(name,converted);}catch(RuntimeException ex){outputValues.put(name,raw);valid=false;String code=ex instanceof FormulaCacheException?"FORMULA_CACHED":"CELL_CONVERSION";diagnostics.add(code,id,name,code.equals("FORMULA_CACHED")?"Formula has no usable cached result":"Cell cannot be parsed as "+columns.get(col).get("type"));}
            }try{TabularJsonWriter.row(output,first,id,outputValues,presence);first=false;}catch(IOException e){throw new UncheckedIOException(e);}}
        private String cellValue(XlsxCell cell,boolean header,String targetType){if(cell==null)return "";if(header&&cell.formula()&&!cell.hasValue())throw ExceptionHandler.fail("VALIDATION_FAILED","Formula has no cached result in XLSX header");if(header&&cell.error())throw ExceptionHandler.fail("VALIDATION_FAILED","XLSX cell contains an error");String raw=cell.value();try{return switch(cell.type()){case "s"->shared.get(Integer.parseInt(raw));case "b"->"1".equals(raw)?"true":"false";case "inlineStr","str"->raw;case "e"->raw;default->{if(!cell.hasValue()||raw.isEmpty())yield "";if(cell.style()>=0&&cell.style()<styles.getNumCellStyles()){var xfs=styles.getCellXfAt(cell.style());short formatId=(short)xfs.getNumFmtId();String format=styles.getNumberFormatAt(formatId);if(DateUtil.isADateFormat(formatId,format)){var date=DateUtil.getLocalDateTime(Double.parseDouble(raw),date1904);yield switch(Objects.toString(targetType,"")){case "Date"->date.toLocalDate().toString();case "Time","LocalTime"->date.toLocalTime().toString();default->date.toString();};}}yield new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString();}};}catch(IOException|NumberFormatException error){throw new IllegalArgumentException("Invalid XLSX cell value",error);}}
        Map<String,Object> finish(){if(finished)return Map.of();finished=true;long end=Math.max(lastPhysical,dimensionLast);if(!headerReady)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");for(long missing=lastPhysical+1;missing<=end;missing++)if(missing>headerRow)writeDataRow(Map.of(),Long.toString(rowCount+1));try{TabularJsonWriter.footer(output,rowCount,valid,diagnostics.items(),diagnostics.truncated());output.flush();return Json.map("columns",columns,"rowCount",rowCount,"valid",valid,"diagnostics",diagnostics.items(),"diagnosticsTruncated",diagnostics.truncated());}catch(IOException e){throw new UncheckedIOException(e);}}
        private long rowNumber(String reference){int at=0;while(at<reference.length()&&!Character.isDigit(reference.charAt(at)))at++;return at==reference.length()?0:Long.parseLong(reference.substring(at));}
        private final class FormulaCacheException extends RuntimeException{}
    }

}
