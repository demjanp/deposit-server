package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.TabularValueCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TabularCsvTest {
    @Test void parsesQuotedSeparatorsNewlinesAndDoubledQuotes(){
        assertEquals(List.of(List.of("name","note"),List.of("sample","line 1,\nline 2 and \"quoted\"")),TabularService.csv("name,note\r\nsample,\"line 1,\nline 2 and \"\"quoted\"\"\"\r\n",','));
    }
    @Test void preservesWhitespaceAndEmptyFields(){
        assertEquals(List.of(List.of("  ","")),TabularService.csv("  ,\r\n",','));
    }
    @Test void inMemoryDecoderDoesNotExpandAnEmptyRecordToHeaderWidth(){
        assertEquals(List.of(List.of("a"),List.of("")),TabularService.csv("a\r\n\r\n",','));
    }
    @Test void honorsNonDefaultDelimiter(){
        assertEquals(List.of(List.of("code","note"),List.of("A-1","left;right")),TabularService.csv("code;note\r\nA-1;\"left;right\"\r\n",';'));
    }
    @Test void rejectsMalformedQuoting(){
        assertThrows(RuntimeException.class,()->TabularService.csv("a,\"unfinished",','));
        assertThrows(RuntimeException.class,()->TabularService.csv("a,b\"c",','));
    }
    @Test void sharedCodecParsesTypedCellsAndGeometryPrefixes(){
        assertEquals(42L,TabularValueCodec.parseCell("Integer","42",".",null));
        assertEquals(1.25d,TabularValueCodec.parseCell("Float","1,25",",",null));
        var geometry=TabularValueCodec.parseGeometryValue("SRID=4326;VERT_SRID=5705;POINTZ(17.1 48.1 250)",null,null);
        assertEquals(4326L,geometry.get("srid"));assertEquals(5705L,geometry.get("verticalSrid"));assertEquals("Point",geometry.get("geometryType"));assertEquals(3L,geometry.get("dimensions"));
    }
    @Test void exportsDurationComponentsAsIsoText(){
        assertEquals("P1Y2M3DT4.000000005S",TabularValueCodec.exportText("Duration",java.util.Map.of("months",14L,"days",3L,"seconds",4L,"nanoseconds",5L)));
        assertEquals("PT-1S",TabularValueCodec.exportText("Duration",java.util.Map.of("months",0L,"days",0L,"seconds",-1L,"nanoseconds",0L)));
        var mixed=Map.of("months",14L,"days",-3L,"seconds",-4L,"nanoseconds",5L);
        var stored=deposit.common.ValueCodec.storage("Duration",deposit.common.ValueCodec.normalize("Duration",mixed));
        var parsed=org.neo4j.values.storable.DurationValue.parse(TabularValueCodec.exportText("Duration",mixed));
        assertEquals(stored,parsed);
        assertEquals(deposit.common.ValueCodec.durationMap((java.time.temporal.TemporalAmount)stored),deposit.common.ValueCodec.durationMap(parsed));
    }
    @Test void exportsNestedIntegerJsonAsExactDecimalStrings(){
        var value=Map.of("itemType","Integer","items",java.util.Arrays.asList(Long.MIN_VALUE,null,Long.MAX_VALUE));
        assertEquals("{\"itemType\":\"Integer\",\"items\":[\"-9223372036854775808\",null,\"9223372036854775807\"]}",TabularValueCodec.exportText("List",value));
    }
}
