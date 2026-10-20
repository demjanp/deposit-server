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
}
