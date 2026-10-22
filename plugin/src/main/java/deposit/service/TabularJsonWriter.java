package deposit.service;

import deposit.common.Json;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/** Staged JSON encoding over a caller-owned Writer. */
final class TabularJsonWriter {
    private TabularJsonWriter() {}

    static void header(Writer output,List<?> columns)throws IOException{
        output.write("{\"columns\":");output.write(Json.write(columns));output.write(",\"rows\":[");
    }
    static void row(Writer output,boolean first,String rowId,Map<String,Object> values,Map<String,Object> presence)throws IOException{
        if(!first)output.write(',');
        output.write(Json.write(Json.map("rowId",rowId,"values",values,"presence",presence)));
    }
    static void footer(Writer output,long rowCount,boolean valid,List<?> diagnostics,boolean diagnosticsTruncated)throws IOException{
        output.write("],\"rowCount\":");output.write(Long.toString(rowCount));output.write(",\"valid\":");output.write(Boolean.toString(valid));
        output.write(",\"diagnostics\":");output.write(Json.write(diagnostics));output.write(",\"diagnosticsTruncated\":");output.write(Boolean.toString(diagnosticsTruncated));output.write('}');
    }
}
