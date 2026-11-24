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
        Map<String,Object> first=rows.getFirst(),values=ValueCodec.map(first.get("values"));Map<String,Object> presence=ValueCodec.map(first.get("presence"));assertEquals("1904-01-01",values.get("date"));assertEquals(2L,values.get("formula"));assertEquals("#VALUE!",values.get("error"));assertEquals("",values.get("cacheless"));assertEquals("rich text",values.get("rich"));assertEquals("inline rich",values.get("inline"));assertTrue(Boolean.TRUE.equals(presence.get("rich")));assertEquals("#VALUE!",values.get("error"));
        List<Map<String,Object>> diagnostics=((List<?>)result.get("diagnostics")).stream().map(ValueCodec::map).toList();Map<String,Object> formulaDiagnostic=diagnostics.stream().filter(d->"FORMULA_CACHED".equals(d.get("code"))).findFirst().orElseThrow();assertEquals("1",formulaDiagnostic.get("rowId"));assertEquals("cacheless",formulaDiagnostic.get("column"));assertEquals("Formula has no usable cached result",formulaDiagnostic.get("message"));Map<String,Object> errorDiagnostic=diagnostics.stream().filter(d->"error".equals(d.get("column"))).findFirst().orElseThrow();assertEquals("CELL_CONVERSION",errorDiagnostic.get("code"));assertEquals("#VALUE!",values.get("error"));
        Map<String,Object> last=ValueCodec.map(rows.getLast().get("presence"));assertTrue(last.values().stream().allMatch(value->Boolean.FALSE.equals(value)),"Dimension-only trailing row must retain absent-cell presence");try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")));}
        Path emptySource=temp.resolve("empty.xlsx"),emptyOutput=temp.resolve("empty.json");writeWorkbook(emptySource,0);var empty=XlsxTableReader.parseXlsx(emptySource,emptyOutput,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{});assertEquals(0L,empty.get("rowCount"));
    }

    @Test void cancellationWhileReadingSharedStringsRemovesTemporaryWorkspace()throws Exception {
        Path source=temp.resolve("cancel.xlsx"),output=temp.resolve("cancel.json");writeWorkbook(source,1000);int preflightReads=zipChunkReads(source);java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        var injected=new IllegalStateException("cancelled");assertSame(injected,assertThrows(IllegalStateException.class,()->XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{if(checks.incrementAndGet()==preflightReads+2)throw injected;})));assertEquals("cancelled",injected.getMessage());assertTrue(checks.get()>preflightReads);try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")),"Cancellation must remove the shared-string workspace");}
    }

    @Test void preflightRejectsZipEntryAndExpandedContentLimits()throws Exception {
        Path source=temp.resolve("bounded.xlsx");writeWorkbook(source,0);
        RuntimeException entries=assertThrows(RuntimeException.class,()->XlsxTableReader.preflightXlsx(source,()->{},4,Long.MAX_VALUE));assertTrue(entries.getMessage().contains("LIMIT_EXCEEDED"),entries.getMessage());assertTrue(entries.getMessage().contains("entry limit"),entries.getMessage());
        RuntimeException expanded=assertThrows(RuntimeException.class,()->XlsxTableReader.preflightXlsx(source,()->{},100,32));assertTrue(expanded.getMessage().contains("LIMIT_EXCEEDED"),expanded.getMessage());assertTrue(expanded.getMessage().contains("expanded size limit"),expanded.getMessage());
    }

    @Test void xlsxHeaderPolicyKeepsOriginalNfcSpellingAndExactOptionMembership()throws Exception{
        Path source=temp.resolve("nfc-header.xlsx"),output=temp.resolve("nfc-header.json");writeSmallWorkbook(source,"cafe\u0301", " code", "42", "7");
        var parsed=XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of("cafe\u0301","Integer"),Map.of(),".",()->{});
        assertEquals(List.of(Json.map("name","cafe\u0301","type","Integer"),Json.map("name"," code","type","String")),parsed.get("columns"));
        Path duplicate=temp.resolve("nfc-duplicate.xlsx");writeSmallWorkbook(duplicate,"cafe\u0301","caf\u00e9","1","2");
        var duplicateFailure=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(duplicate,temp.resolve("duplicate.json"),Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{}));
        assertTrue(duplicateFailure.getMessage().contains("Header names must be nonempty and unique"));
        var optionFailure=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(source,temp.resolve("wrong-option.json"),Map.of("sheet","Rows"),1,Map.of("caf\u00e9","Integer"),Map.of(),".",()->{}));
        assertTrue(optionFailure.getMessage().contains("columnTypes names an unknown column"));
        var precedence=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(source,temp.resolve("both-options.json"),Map.of("sheet","Rows"),1,Map.of("missingType","String"),Map.of("missingDate","yyyy"),".",()->{}));
        assertTrue(precedence.getMessage().contains("columnTypes names an unknown column"));
        var date=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(source,temp.resolve("date-option.json"),Map.of("sheet","Rows"),1,Map.of(),Map.of("missingDate","yyyy"),".",()->{}));
        assertTrue(date.getMessage().contains("dateFormats names an unknown column"));
    }

    @Test void emptyXlsxHeaderNameFailsWithValidationError()throws Exception{
        Path source=temp.resolve("empty-header.xlsx");writeSmallWorkbook(source,"","value","x","y");
        var failure=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(source,temp.resolve("empty-header.json"),Map.of("sheet","Rows"),1,Map.of("missingType","String"),Map.of(),".",()->{}));
        assertEquals("DEPOSIT_ERROR {\"code\":\"VALIDATION_FAILED\",\"details\":{},\"message\":\"Header names must be nonempty and unique\",\"requestId\":null,\"retryable\":false}",failure.getMessage());
    }

    @Test void emptyMappedNumericCellStaysBlankWhileWhitespaceRemainsSupplied()throws Exception{
        Path source=temp.resolve("typed-blank.xlsx"),staged=temp.resolve("typed-blank.json");writeSmallWorkbook(source,"amount","note","","  ");
        var parsed=XlsxTableReader.parseXlsx(source,staged,Map.of("sheet","Rows"),1,Map.of("amount","Integer"),Map.of(),".",()->{});
        assertTrue(Boolean.TRUE.equals(parsed.get("valid")));
        try(Json.ArrayReader rows=new Json.ArrayReader(staged,"rows")){
            Map<String,Object> row=rows.next();assertEquals(Map.of("amount","","note","  "),ValueCodec.map(row.get("values")));
            assertEquals(Map.of("amount",true,"note",true),ValueCodec.map(row.get("presence")));assertNull(rows.next());
        }
    }

    @Test void xlsxProjectsTypeBeforeExtractingTheNextHeaderCell()throws Exception{
        Path source=temp.resolve("invalid-type-before-next-cell.xlsx");writeMalformedSecondHeaderWorkbook(source);
        var failure=assertThrows(RuntimeException.class,()->XlsxTableReader.parseXlsx(source,temp.resolve("type-first.json"),Map.of("sheet","Rows"),1,Map.of("amount","not-a-descriptor-type"),Map.of(),".",()->{}));
        assertTrue(failure.getMessage().contains("Unsupported descriptor type"),failure.getMessage());
        assertFalse(failure.getMessage().contains("Invalid shared string index"),failure.getMessage());
    }

    @Test void xlsxDiagnosticCapCountsEveryFailureAndKeepsFirstThousandPayloads()throws Exception{
        for(int errors:List.of(999,1000,1001)){
            Path source=temp.resolve("bad-cells-"+errors+".xlsx"),output=temp.resolve("bad-cells-"+errors+".json");writeDiagnosticWorkbook(source,errors);
            var parsed=XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of("amount","Integer"),Map.of(),".",()->{});
            List<?> diagnostics=(List<?>)parsed.get("diagnostics");assertEquals(Math.min(errors,1000),diagnostics.size());assertEquals(errors>1000,parsed.get("diagnosticsTruncated"));assertFalse((Boolean)parsed.get("valid"));
            Map<String,Object> first=ValueCodec.map(diagnostics.getFirst()),last=ValueCodec.map(diagnostics.getLast());assertEquals("1",first.get("rowId"));assertEquals("amount",first.get("column"));assertEquals("CELL_CONVERSION",first.get("code"));assertEquals(Integer.toString(Math.min(errors,1000)),last.get("rowId"));assertEquals("amount",last.get("column"));assertEquals("CELL_CONVERSION",last.get("code"));
            if(errors==999){try(Json.ArrayReader rows=new Json.ArrayReader(output,"rows")){Map<String,Object> row=rows.next();assertEquals("bad-1",ValueCodec.map(row.get("values")).get("amount"));assertEquals(Boolean.TRUE,ValueCodec.map(row.get("presence")).get("amount"));}}
        }
    }

    @Test void stagedXlsxJsonUsesExactIndependentBytesAndCancellationRemainsCallerOwned()throws Exception{
        Path source=temp.resolve("literal-staging.xlsx"),output=temp.resolve("literal-staging.json");writeSmallWorkbook(source,"label","note","\u00e9","a,\"b\"\nline");
        XlsxTableReader.parseXlsx(source,output,Map.of("sheet","Rows"),1,Map.of(),Map.of(),".",()->{});
        assertEquals("{\"columns\":[{\"name\":\"label\",\"type\":\"String\"},{\"name\":\"note\",\"type\":\"String\"}],\"rows\":[{\"presence\":{\"label\":true,\"note\":true},\"rowId\":\"1\",\"values\":{\"label\":\"\u00e9\",\"note\":\"a,\\\"b\\\"\\nline\"}}],\"rowCount\":1,\"valid\":true}",Files.readString(output));
        Path cancelled=temp.resolve("cancel-row.xlsx"),cancelledOutput=temp.resolve("cancel-row.json");writeDiagnosticWorkbook(cancelled,3);int preflight=zipChunkReads(cancelled);var stop=new IllegalStateException("stop row");java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertSame(stop,assertThrows(IllegalStateException.class,()->XlsxTableReader.parseXlsx(cancelled,cancelledOutput,Map.of("sheet","Rows"),1,Map.of("amount","Integer"),Map.of(),".",()->{if(checks.incrementAndGet()==preflight+5)throw stop;})));
        assertEquals(preflight+5,checks.get(),"Cancellation should be observed in the first staged data row after preflight and shared strings");
        try(var children=Files.list(temp)){assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("xlsx-")),"Row cancellation must clean XLSX workspace");}
    }

    private static void writeSmallWorkbook(Path path,String firstHeader,String secondHeader,String firstValue,String secondValue)throws IOException{
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            entry(zip,"[Content_Types].xml",w->w.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>"));
            entry(zip,"_rels/.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
            entry(zip,"xl/workbook.xml",w->w.write("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Rows\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
            entry(zip,"xl/_rels/workbook.xml.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
            entry(zip,"xl/sharedStrings.xml",w->{w.write("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");for(String value:List.of(firstHeader,secondHeader,firstValue,secondValue))w.write("<si><t>"+xml(value)+"</t></si>");w.write("</sst>");});
            entry(zip,"xl/worksheets/sheet1.xml",w->w.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:B2\"/><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" t=\"s\"><v>3</v></c></row></sheetData></worksheet>"));
        }
    }
    private static void writeDiagnosticWorkbook(Path path,int errors)throws IOException{
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            entry(zip,"[Content_Types].xml",w->w.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>"));
            entry(zip,"_rels/.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
            entry(zip,"xl/workbook.xml",w->w.write("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Rows\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
            entry(zip,"xl/_rels/workbook.xml.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
            entry(zip,"xl/sharedStrings.xml",w->{w.write("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>amount</t></si>");for(int i=1;i<=errors;i++)w.write("<si><t>bad-"+i+"</t></si>");w.write("</sst>");});
            entry(zip,"xl/worksheets/sheet1.xml",w->{w.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:A"+(errors+1)+"\"/><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>");for(int i=1;i<=errors;i++)w.write("<row r=\""+(i+1)+"\"><c r=\"A"+(i+1)+"\" t=\"s\"><v>"+i+"</v></c></row>");w.write("</sheetData></worksheet>");});
        }
    }
    private static void writeMalformedSecondHeaderWorkbook(Path path)throws IOException{
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            entry(zip,"[Content_Types].xml",w->w.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>"));
            entry(zip,"_rels/.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));
            entry(zip,"xl/workbook.xml",w->w.write("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Rows\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
            entry(zip,"xl/_rels/workbook.xml.rels",w->w.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
            entry(zip,"xl/sharedStrings.xml",w->w.write("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>amount</t></si></sst>"));
            entry(zip,"xl/worksheets/sheet1.xml",w->w.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:B1\"/><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>99</v></c></row></sheetData></worksheet>"));
        }
    }
    private static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

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
