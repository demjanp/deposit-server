package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TabularXlsxStreamingTest {
    private static final int ROWS=300_000;
    @TempDir Path temp;

    @Test void meaningfulSharedStringWorkbookParsesWithinConstrainedHeapAndCleansWorkspace()throws Exception {
        Path source=temp.resolve("large-shared-strings.xlsx"),output=temp.resolve("staged.json"),scratch=Files.createDirectory(temp.resolve("scratch"));writeWorkbook(source,ROWS);
        Path arguments=temp.resolve("probe-args.txt"),log=temp.resolve("probe.log");Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"")+"\"\n");String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Process process=new ProcessBuilder(java,"-Xmx96m","-Djava.io.tmpdir="+scratch,"@"+arguments,Probe.class.getName(),source.toString(),output.toString(),Integer.toString(ROWS)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(180,TimeUnit.SECONDS),"Constrained-heap XLSX SAX parse timed out");assertEquals(0,process.exitValue(),Files.readString(log));}finally{if(process.isAlive())process.destroyForcibly().waitFor();}
        assertTrue(Files.size(source)>1_000_000,"Fixture must exercise a meaningful shared-string payload");assertTrue(Files.size(output)>10_000_000,"Output confirms rows were streamed to disk");try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")),"Temporary shared-string workspace leaked");}
    }

    @Test void date1904FormulaCachesRichStringsAndSparsePresenceArePreserved()throws Exception {
        Path source=temp.resolve("edge-cells.xlsx"),output=temp.resolve("edge-cells.json");writeEdgeWorkbook(source);var result=XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of("date","Date","formula","Integer"),Map.of(),".",()->{});
        assertEquals(2L,result.get("rowCount"));assertFalse((Boolean)result.get("valid"));List<Map<String,Object>> rows=new ArrayList<>();try(Json.ArrayReader reader=new Json.ArrayReader(output,"rows")){Map<String,Object> row;while((row=reader.next())!=null)rows.add(row);}
        Map<String,Object> first=rows.getFirst(),values=ValueCodec.map(first.get("values"));Map<String,Object> presence=ValueCodec.map(first.get("presence"));assertEquals("1904-01-01",values.get("date"));assertEquals(2L,values.get("formula"));assertEquals("#VALUE!",values.get("error"));assertEquals("",values.get("cacheless"));assertEquals("rich text",values.get("rich"));assertEquals("inline rich",values.get("inline"));assertTrue(Boolean.TRUE.equals(presence.get("rich")));assertTrue(((List<?>)result.get("diagnostics")).stream().anyMatch(d->"FORMULA_CACHED".equals(ValueCodec.map(d).get("code"))));
        Map<String,Object> last=ValueCodec.map(rows.getLast().get("presence"));assertTrue(last.values().stream().allMatch(value->Boolean.FALSE.equals(value)),"Dimension-only trailing row must retain absent-cell presence");try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")));}
        Path emptySource=temp.resolve("empty.xlsx"),emptyOutput=temp.resolve("empty.json");writeWorkbook(emptySource,0);var empty=XlsxTableReader.parseXlsx(emptySource,emptyOutput,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{});assertEquals(0L,empty.get("rowCount"));
    }

    @Test void cancellationWhileReadingSharedStringsRemovesTemporaryWorkspace()throws Exception {
        Path source=temp.resolve("cancel.xlsx"),output=temp.resolve("cancel.json");writeWorkbook(source,1000);int preflightReads=zipChunkReads(source);java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(Exception.class,()->XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{if(checks.incrementAndGet()==preflightReads+2)throw new IllegalStateException("cancelled");}));assertTrue(checks.get()>preflightReads);try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")),"Cancellation must remove the shared-string workspace");}
    }

    @Test void preflightRejectsZipEntryAndExpandedContentLimits()throws Exception {
        Path source=temp.resolve("bounded.xlsx");writeWorkbook(source,0);
        RuntimeException entries=assertThrows(RuntimeException.class,()->XlsxTableReader.preflightXlsx(source,()->{},4,Long.MAX_VALUE));assertTrue(entries.getMessage().contains("LIMIT_EXCEEDED"),entries.getMessage());assertTrue(entries.getMessage().contains("entry limit"),entries.getMessage());
        RuntimeException expanded=assertThrows(RuntimeException.class,()->XlsxTableReader.preflightXlsx(source,()->{},100,32));assertTrue(expanded.getMessage().contains("LIMIT_EXCEEDED"),expanded.getMessage());assertTrue(expanded.getMessage().contains("expanded size limit"),expanded.getMessage());
    }

    public static class Probe {
        public static void main(String[] args)throws Exception {
            Path input=Path.of(args[0]),output=Path.of(args[1]);int rows=Integer.parseInt(args[2]);Map<String,Object> result=XlsxTableReader.parseXlsx(input,output,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{});
            if(((Number)result.get("rowCount")).longValue()!=rows)throw new AssertionError("Wrong staged row count: "+result);if(!Boolean.TRUE.equals(result.get("valid")))throw new AssertionError("Valid XLSX was marked invalid: "+result);System.out.println("streamed-rows="+rows+" max-heap="+Runtime.getRuntime().maxMemory());
        }
    }

    private static void writeWorkbook(Path path,int rows)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip,"[Content_Types].xml",writer->{writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>");});
            entry(zip,"_rels/.rels",writer->writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
            entry(zip,"xl/workbook.xml",writer->writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Rows\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
            entry(zip,"xl/_rels/workbook.xml.rels",writer->writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
            entry(zip,"xl/sharedStrings.xml",writer->{writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\""+(rows+1)+"\" uniqueCount=\""+(rows+1)+"\"><si><t>code</t></si>");for(int i=1;i<=rows;i++){writer.write("<si><t>value-");writer.write(Integer.toString(i));writer.write("-scientific-measurement-abcdefghijklmnopqrstuvwxyz</t></si>");}writer.write("</sst>");});
            entry(zip,"xl/worksheets/sheet1.xml",writer->{writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:A"+(rows+1)+"\"/><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>");for(int i=1;i<=rows;i++){writer.write("<row r=\"");writer.write(Integer.toString(i+1));writer.write("\"><c r=\"A");writer.write(Integer.toString(i+1));writer.write("\" t=\"s\"><v>");writer.write(Integer.toString(i));writer.write("</v></c></row>");}writer.write("</sheetData></worksheet>");});
        }
    }
    private static void writeEdgeWorkbook(Path path)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip,"[Content_Types].xml",writer->writer.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"));
            entry(zip,"_rels/.rels",writer->writer.write("<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
            entry(zip,"xl/workbook.xml",writer->writer.write("<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><workbookPr date1904=\"1\"/><sheets><sheet name=\"Rows\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
            entry(zip,"xl/_rels/workbook.xml.rels",writer->writer.write("<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"));
            entry(zip,"xl/sharedStrings.xml",writer->writer.write("<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"7\" uniqueCount=\"7\"><si><t>date</t></si><si><t>formula</t></si><si><t>error</t></si><si><t>cacheless</t></si><si><t>rich</t></si><si><t>inline</t></si><si><r><t>rich </t></r><r><t>text</t></r><rPh sb=\"0\" eb=\"1\"><t>phonetic</t></rPh></si></sst>"));
            entry(zip,"xl/styles.xml",writer->writer.write("<?xml version=\"1.0\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/></numFmts><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/></cellXfs></styleSheet>"));
            entry(zip,"xl/worksheets/sheet1.xml",writer->writer.write("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:F3\"/><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c><c r=\"E1\" t=\"s\"><v>4</v></c><c r=\"F1\" t=\"s\"><v>5</v></c></row><row r=\"2\"><c r=\"A2\" s=\"1\"><v>0</v></c><c r=\"B2\"><f>1+1</f><v>2</v></c><c r=\"C2\" t=\"e\"><v>#VALUE!</v></c><c r=\"D2\"><f>1+1</f></c><c r=\"E2\" t=\"s\"><v>6</v></c><c r=\"F2\" t=\"inlineStr\"><is><r><t>inline </t></r><r><t>rich</t></r></is></c></row></sheetData></worksheet>"));
        }
    }
    private static void entry(ZipOutputStream zip,String name,WriterAction write)throws IOException {
        zip.putNextEntry(new ZipEntry(name));OutputStream passthrough=new FilterOutputStream(zip){@Override public void close()throws IOException{flush();}};try(Writer writer=new BufferedWriter(new OutputStreamWriter(passthrough,StandardCharsets.UTF_8))){write.write(writer);}zip.closeEntry();
    }
    private static int zipChunkReads(Path path)throws IOException{int reads=0;try(ZipFile zip=new ZipFile(path.toFile())){var entries=zip.entries();byte[] buffer=new byte[64*1024];while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();try(InputStream input=zip.getInputStream(entry)){while(input.read(buffer)!=-1)reads++;}}}return reads;}
    @FunctionalInterface private interface WriterAction {void write(Writer writer)throws IOException;}
}
