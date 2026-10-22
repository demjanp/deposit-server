package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TabularJsonWriterTest {
    @Test void stagedRowsKeepLiteralSortedMapBytesAndOriginalColumnArrayOrder()throws Exception{
        StringWriter output=new StringWriter();List<?> columns=List.of(Json.map("name","zeta","type","String"),Json.map("name","Alpha","type","Integer"));
        TabularJsonWriter.header(output,columns);
        TabularJsonWriter.row(output,true,"1",Json.map("zeta","\u00e9","Alpha",7L),Json.map("zeta",true,"Alpha",false));
        TabularJsonWriter.row(output,false,"2",Json.map("zeta","a,\"b\"\nline","Alpha",null),Json.map("zeta",true,"Alpha",true));
        TabularJsonWriter.footer(output,2,false,List.of(Json.map("severity","error","code","CELL_CONVERSION","rowId","2","column","Alpha","message","bad \u00e9")),false);
        assertEquals("{\"columns\":[{\"name\":\"zeta\",\"type\":\"String\"},{\"name\":\"Alpha\",\"type\":\"Integer\"}],\"rows\":[{\"presence\":{\"Alpha\":false,\"zeta\":true},\"rowId\":\"1\",\"values\":{\"Alpha\":7,\"zeta\":\"\u00e9\"}},{\"presence\":{\"Alpha\":true,\"zeta\":true},\"rowId\":\"2\",\"values\":{\"Alpha\":null,\"zeta\":\"a,\\\"b\\\"\\nline\"}}],\"rowCount\":2,\"valid\":false,\"diagnostics\":[{\"code\":\"CELL_CONVERSION\",\"column\":\"Alpha\",\"message\":\"bad \u00e9\",\"rowId\":\"2\",\"severity\":\"error\"}],\"diagnosticsTruncated\":false}",output.toString());
    }

    @Test void emptyStagedTableHasExactHeaderAndFooterBytes()throws Exception{
        StringWriter output=new StringWriter();TabularJsonWriter.header(output,List.of(Json.map("name","code","type","String")));TabularJsonWriter.footer(output,0,true,List.of(),false);
        assertEquals("{\"columns\":[{\"name\":\"code\",\"type\":\"String\"}],\"rows\":[],\"rowCount\":0,\"valid\":true,\"diagnostics\":[],\"diagnosticsTruncated\":false}",output.toString());
    }

    @Test void borrowedWriterIsNeverFlushedOrClosedAndWriteFailureIdentityIsPreserved(){
        IOException failure=new IOException("exact writer failure");RecordingWriter output=new RecordingWriter(failure);
        assertSame(failure,assertThrows(IOException.class,()->TabularJsonWriter.row(output,true,"1",Map.of(),Map.of())));
        assertFalse(output.flushed);assertFalse(output.closed);
    }

    private static final class RecordingWriter extends Writer {
        private final IOException failure;boolean flushed,closed;
        RecordingWriter(IOException failure){this.failure=failure;}
        @Override public void write(char[] chars,int offset,int length)throws IOException{throw failure;}
        @Override public void flush(){flushed=true;}
        @Override public void close(){closed=true;}
    }
}
