package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TabularTypedIntegerExportTest {
    @TempDir Path temp;

    @Test void csvExportReloadPreservesNestedIntegerBoundaries() throws Exception {
        var table=table();Path staged=writeTable(table),export=temp.resolve("table.csv"),reloaded=temp.resolve("reloaded.json");
        CsvTableFormat.write(export,columns(),staged,',',()->{},value->{throw new AssertionError("No resource links expected");});
        var metadata=CsvTableFormat.read(export,reloaded,',',1,types(),Map.of(),".",()->{});
        assertTypedValues(metadata,reloaded,table);
    }

    @Test void xlsxExportReloadPreservesNestedIntegerBoundaries() throws Exception {
        var table=table();Path staged=writeTable(table),export=temp.resolve("table.xlsx"),reloaded=temp.resolve("reloaded.json");
        XlsxTableWriter.write(export,columns(),staged,1,"Data",()->{},value->{throw new AssertionError("No resource links expected");});
        var metadata=XlsxTableReader.parseXlsx(export,reloaded,Map.of("sheet","Data"),1,types(),Map.of(),".",()->{});
        assertTypedValues(metadata,reloaded,table);
    }

    @Test void geoPackageExportReloadPreservesNestedIntegerBoundaries() throws Exception {
        var table=table();Path staged=writeTable(table),reloaded=temp.resolve("reloaded.json");
        var outputs=GeoPackageAdapter.write(staged,columns(),"table.gpkg","data",temp,()->{});
        assertEquals(1,outputs.size());
        var metadata=GeoPackageAdapter.load(outputs.getFirst().path(),reloaded,Map.of("layer","data"),types(),Map.of(),".",()->{});
        assertTypedValues(metadata,reloaded,table);
    }

    private Path writeTable(Map<String,Object> table)throws Exception{Path path=temp.resolve("table.json");Files.writeString(path,Json.write(table));return path;}

    private static List<Map<String,Object>> columns(){return List.of(Json.map("name","listValue","type","List"),Json.map("name","dictValue","type","DDict"));}
    private static Map<String,Object> types(){return Map.of("listValue","List","dictValue","DDict");}

    private static Map<String,Object> table(){
        var list=Json.map("itemType","DDict","items",List.of(
            Json.map("list",Json.map("type","List","value",Json.map("itemType","Integer","items",Arrays.asList(Long.MIN_VALUE,0L,Long.MAX_VALUE))),
                     "nested",Json.map("type","DDict","value",Json.map("max",Json.map("type","Integer","value",Long.MAX_VALUE)))),
            Json.map("inner",Json.map("type","List","value",Json.map("itemType","DDict","items",List.of(Json.map("min",Json.map("type","Integer","value",Long.MIN_VALUE))))))));
        var dict=Json.map("min",Json.map("type","Integer","value",Long.MIN_VALUE),
                          "nested",Json.map("type","List","value",Json.map("itemType","Integer","items",Arrays.asList(Long.MIN_VALUE,Long.MAX_VALUE))),
                          "max",Json.map("type","Integer","value",Long.MAX_VALUE));
        String storedList=(String)ValueCodec.storage("List",ValueCodec.normalize("List",list));
        String storedDict=(String)ValueCodec.storage("DDict",ValueCodec.normalize("DDict",dict));
        return Json.map("columns",columns(),"rows",List.of(Json.map("rowId","1","values",Json.map("listValue",storedList,"dictValue",storedDict),"presence",Json.map("listValue",true,"dictValue",true))),"rowCount",1L,"valid",true,"diagnostics",List.of(),"diagnosticsTruncated",false);
    }

    private static void assertTypedValues(Map<String,Object> metadata,Path reloaded,Map<String,Object> original)throws Exception{
        assertEquals(1L,metadata.get("rowCount"));assertTrue(Boolean.TRUE.equals(metadata.get("valid")));
        Map<String,Object> sourceRow=ValueCodec.map(((List<?>)original.get("rows")).getFirst());
        Map<String,Object> expected=ValueCodec.map(sourceRow.get("values"));
        try(Json.ArrayReader rows=new Json.ArrayReader(reloaded,"rows")){
            Map<String,Object> row=rows.next();assertNotNull(row);Map<String,Object> actual=ValueCodec.map(row.get("values"));
            assertEquals(ValueCodec.normalizeJson("List",Json.read((String)expected.get("listValue"))),ValueCodec.normalizeJson("List",actual.get("listValue")));
            assertEquals(ValueCodec.normalizeJson("DDict",Json.read((String)expected.get("dictValue"))),ValueCodec.normalizeJson("DDict",actual.get("dictValue")));
            assertNull(rows.next());
        }
    }
}
