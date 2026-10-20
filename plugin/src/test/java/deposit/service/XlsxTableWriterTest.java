package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XlsxTableWriterTest {
    @TempDir Path temp;

    @Test void rejectsOverLimitResourceHyperlinkLabelBeforeWritingOutput()throws Exception{
        Path table=temp.resolve("table.json"),output=temp.resolve("result.xlsx");
        Files.writeString(table,Json.write(Json.map("columns",List.of(Json.map("name","file","type","DResource")),"rows",List.of(Json.map("rowId","7","values",Json.map("file",Json.map("resourceId","00000000-0000-4000-8000-000000000001")),"presence",Json.map("file",true))))));
        RuntimeException failure=assertThrows(RuntimeException.class,()->XlsxTableWriter.write(output,List.of(Json.map("name","file","type","DResource")),table,1,"Data",()->{},value->new XlsxTableWriter.ResourceLink("x".repeat(32_768),"https://example.test/download")));
        assertTrue(failure.getMessage().contains("LIMIT_EXCEEDED"));assertTrue(failure.getMessage().contains("row 7, column file"));assertFalse(Files.exists(output));
    }
}
