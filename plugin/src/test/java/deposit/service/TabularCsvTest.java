package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
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
    @Test void honorsNonDefaultDelimiter(){
        assertEquals(List.of(List.of("code","note"),List.of("A-1","left;right")),TabularService.csv("code;note\r\nA-1;\"left;right\"\r\n",';'));
    }
    @Test void rejectsMalformedQuoting(){
        assertThrows(RuntimeException.class,()->TabularService.csv("a,\"unfinished",','));
        assertThrows(RuntimeException.class,()->TabularService.csv("a,b\"c",','));
    }
    @Test void exportsDurationComponentsAsIsoText(){
        assertEquals("P1Y2M3DT4.000000005S",TabularService.exportText("Duration",java.util.Map.of("months",14L,"days",3L,"seconds",4L,"nanoseconds",5L)));
        assertEquals("PT-1S",TabularService.exportText("Duration",java.util.Map.of("months",0L,"days",0L,"seconds",-1L,"nanoseconds",0L)));
        var mixed=Map.of("months",14L,"days",-3L,"seconds",-4L,"nanoseconds",5L);
        var stored=deposit.common.ValueCodec.storage("Duration",deposit.common.ValueCodec.normalize("Duration",mixed));
        var parsed=org.neo4j.values.storable.DurationValue.parse(TabularService.exportText("Duration",mixed));
        assertEquals(stored,parsed);
        assertEquals(deposit.common.ValueCodec.durationMap((java.time.temporal.TemporalAmount)stored),deposit.common.ValueCodec.durationMap(parsed));
    }
    @Test void exportsNestedIntegerJsonAsExactDecimalStrings(){
        var value=Map.of("itemType","Integer","items",java.util.Arrays.asList(Long.MIN_VALUE,null,Long.MAX_VALUE));
        assertEquals("{\"itemType\":\"Integer\",\"items\":[\"-9223372036854775808\",null,\"9223372036854775807\"]}",TabularService.exportText("List",value));
    }
}
