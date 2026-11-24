package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvTableFormatTest {
    @TempDir Path temp;

    @Test void productionReaderStreamsQuotedRecordsAndPreservesWhitespaceAndPresence()throws Exception{
        Path source=temp.resolve("input.csv"),staged=temp.resolve("table.json");
        Files.writeString(source,"code,note\r\nA-1,\"line one\r\nline two, and \"\"quoted\"\"\"\r\n  ,\r\n");
        Map<String,Object> result=CsvTableFormat.read(source,staged,',',1,Map.of(),Map.of(),".",()->{});
        assertEquals(2L,result.get("rowCount"));assertTrue(Boolean.TRUE.equals(result.get("valid")));
        try(Json.ArrayReader rows=new Json.ArrayReader(staged,"rows")){
            Map<String,Object> first=rows.next(),second=rows.next();Map<String,Object> values=ValueCodec.map(first.get("values"));
            assertEquals("1",first.get("rowId"));assertEquals("line one\r\nline two, and \"quoted\"",values.get("note"));
            assertEquals("  ",ValueCodec.map(second.get("values")).get("code"));assertEquals(Boolean.TRUE,ValueCodec.map(second.get("presence")).get("note"));assertNull(rows.next());
        }
    }

    @Test void productionWriterEscapesFieldsAndUsesCrLfRecords()throws Exception{
        Path staged=temp.resolve("table.json"),output=temp.resolve("output.csv");
        Files.writeString(staged,Json.write(Json.map("columns",List.of(Json.map("name","code","type","String"),Json.map("name","note","type","String")),"rows",List.of(Json.map("rowId","1","values",Json.map("code","A-1","note","line one\nline two, and \"quoted\""),"presence",Json.map("code",true,"note",true))))));
        CsvTableFormat.write(output,List.of(Json.map("name","code","type","String"),Json.map("name","note","type","String")),staged,',',()->{},value->fail("DResource resolver must not be called"));
        assertEquals("code,note\r\nA-1,\"line one\nline two, and \"\"quoted\"\"\"\r\n",Files.readString(output));
    }

    @Test void headerNamesCompareInNfcButKeepOriginalSpellingAndOptionKeysAreExact()throws Exception{
        Path source=temp.resolve("nfc.csv"),staged=temp.resolve("nfc.json");
        Files.writeString(source,"cafe\u0301, code\r\n42, 7\r\n");
        var parsed=CsvTableFormat.read(source,staged,',',1,Map.of("cafe\u0301","Integer"),Map.of(),".",()->{});
        assertEquals(List.of(Json.map("name","cafe\u0301","type","Integer"),Json.map("name"," code","type","String")),parsed.get("columns"));
        assertEquals(" 7",ValueCodec.map(rows(staged).getFirst().get("values")).get(" code"));
        Path duplicate=temp.resolve("duplicate-nfc.csv");Files.writeString(duplicate,"cafe\u0301,caf\u00e9\r\n");
        var duplicateFailure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(duplicate,temp.resolve("duplicate.json"),',',1,Map.of(),Map.of(),".",()->{}));
        assertTrue(duplicateFailure.getMessage().contains("Header names must be nonempty and unique"));
        var optionFailure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(source,temp.resolve("option.json"),',',1,Map.of("cafe\u00e9","Integer"),Map.of(),".",()->{}));
        assertTrue(optionFailure.getMessage().contains("columnTypes names an unknown column"));
        var dateKeyFailure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(source,temp.resolve("date-key.json"),',',1,Map.of(),Map.of("code","yyyy"),".",()->{}));
        assertTrue(dateKeyFailure.getMessage().contains("dateFormats names an unknown column"));
        assertEquals(1L,CsvTableFormat.read(source,temp.resolve("exact-date-key.json"),',',1,Map.of(),Map.of(" code","yyyy"),".",()->{}).get("rowCount"));
    }

    @Test void unknownTypeOptionPrecedesUnknownDateFormatOptionAndPreservesMessages()throws Exception{
        Path source=temp.resolve("option-order.csv");Files.writeString(source,"a\r\n1\r\n");
        var failure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(source,temp.resolve("option-order.json"),',',1,Map.of("missingType","String"),Map.of("missingDate","yyyy"),".",()->{}));
        assertTrue(failure.getMessage().contains("columnTypes names an unknown column"));
        var dateFailure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(source,temp.resolve("date-order.json"),',',1,Map.of(),Map.of("missingDate","yyyy"),".",()->{}));
        assertTrue(dateFailure.getMessage().contains("dateFormats names an unknown column"));
    }

    @Test void emptyHeaderNameFailsWithValidationError()throws Exception{
        Path source=temp.resolve("empty-header.csv");Files.writeString(source,",value\r\nx,y\r\n");
        var failure=assertThrows(RuntimeException.class,()->CsvTableFormat.read(source,temp.resolve("empty-header.json"),',',1,Map.of("missingType","String"),Map.of(),".",()->{}));
        assertEquals("DEPOSIT_ERROR {\"code\":\"VALIDATION_FAILED\",\"details\":{},\"message\":\"Header names must be nonempty and unique\",\"requestId\":null,\"retryable\":false}",failure.getMessage());
    }

    @Test void streamingReaderStillExpandsBlankRecordsToHeaderWidth()throws Exception{
        Path source=temp.resolve("blank-record.csv"),staged=temp.resolve("blank-record.json");Files.writeString(source,"first,second\r\n\r\n");
        var parsed=CsvTableFormat.read(source,staged,',',1,Map.of(),Map.of(),".",()->{});assertEquals(1L,parsed.get("rowCount"));
        Map<String,Object> row=rows(staged).getFirst();assertEquals(Map.of("first","","second",""),ValueCodec.map(row.get("values")));assertEquals(Map.of("first",true,"second",true),ValueCodec.map(row.get("presence")));
    }

    @Test void emptyMappedNumericFieldStaysBlankWhileWhitespaceRemainsSupplied()throws Exception{
        Path source=temp.resolve("typed-blank.csv"),staged=temp.resolve("typed-blank.json");Files.writeString(source,"amount,note\r\n,  \r\n");
        var parsed=CsvTableFormat.read(source,staged,',',1,Map.of("amount","Integer"),Map.of(),".",()->{});
        assertTrue(Boolean.TRUE.equals(parsed.get("valid")));Map<String,Object> row=rows(staged).getFirst();
        assertEquals(Map.of("amount","","note","  "),ValueCodec.map(row.get("values")));
        assertEquals(Map.of("amount",true,"note",true),ValueCodec.map(row.get("presence")));
    }

    @Test void diagnosticsCapRetainsExactBoundaryAndFirstAndLastPayloads()throws Exception{
        for(int errors:List.of(999,1000,1001)){
            Path source=temp.resolve("errors-"+errors+".csv"),staged=temp.resolve("errors-"+errors+".json");
            StringBuilder csv=new StringBuilder("amount\r\n");for(int i=1;i<=errors;i++)csv.append("bad-").append(i).append("\r\n");Files.writeString(source,csv);
            var parsed=CsvTableFormat.read(source,staged,',',1,Map.of("amount","Integer"),Map.of(),".",()->{});
            List<?> diagnostics=(List<?>)parsed.get("diagnostics");assertEquals(Math.min(errors,1000),diagnostics.size());assertEquals(errors>1000,parsed.get("diagnosticsTruncated"));assertFalse((Boolean)parsed.get("valid"));
            Map<String,Object> first=ValueCodec.map(diagnostics.getFirst()),last=ValueCodec.map(diagnostics.getLast());assertEquals("1",first.get("rowId"));assertEquals("amount",first.get("column"));assertEquals("CELL_CONVERSION",first.get("code"));assertEquals(Integer.toString(Math.min(errors,1000)),last.get("rowId"));assertEquals("amount",last.get("column"));assertEquals("CELL_CONVERSION",last.get("code"));
            if(errors==999){Map<String,Object> row=rows(staged).getFirst();assertEquals("bad-1",ValueCodec.map(row.get("values")).get("amount"));assertEquals(Boolean.TRUE,ValueCodec.map(row.get("presence")).get("amount"));}
        }
    }

    @Test void stagedCsvJsonHasExactIndependentBytesAndFieldOrder()throws Exception{
        Path source=temp.resolve("literal.csv"),staged=temp.resolve("literal.json");Files.writeString(source,"label,note\r\n\u00e9,\"a,\"\"b\"\"\nline\"\r\n");
        CsvTableFormat.read(source,staged,',',1,Map.of(),Map.of(),".",()->{});
        assertEquals("{\"columns\":[{\"name\":\"label\",\"type\":\"String\"},{\"name\":\"note\",\"type\":\"String\"}],\"rows\":[{\"presence\":{\"label\":true,\"note\":true},\"rowId\":\"1\",\"values\":{\"label\":\"\u00e9\",\"note\":\"a,\\\"b\\\"\\nline\"}}],\"rowCount\":1,\"valid\":true}",Files.readString(staged));
    }

    private static List<Map<String,Object>> rows(Path staged)throws Exception{try(Json.ArrayReader reader=new Json.ArrayReader(staged,"rows")){var result=new ArrayList<Map<String,Object>>();Map<String,Object> row;while((row=reader.next())!=null)result.add(row);return result;}}
}
