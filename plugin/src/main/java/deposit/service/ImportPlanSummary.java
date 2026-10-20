package deposit.service;

import deposit.common.*;
import java.io.*;
import java.util.*;

/** Reads bounded application fields from the persisted import-plan summary. */
final class ImportPlanSummary {
    private ImportPlanSummary() {}

/** Application needs one selected mapping and conflict metadata; the full advertised set stays on disk. */
static Map<String,Object> applicationSummary(String source,DiskJsonList candidates,DiskJsonList conflicts,Runnable checkpoint){
    var result=new LinkedHashMap<String,Object>();var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
    try(var parser=mapper.createParser(source)){
        if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected import summary object");
        while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){checkpoint.run();String name=parser.currentName();var token=parser.nextToken();
            if(name.equals("candidateMappings")){if(token!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IOException("Expected advertised mappings");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){checkpoint.run();candidates.add(mapper.readValue(parser,Map.class));}}
            else if(name.equals("conflicts")){if(token!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IOException("Expected import conflicts");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){checkpoint.run();if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected import conflict");var metadata=new LinkedHashMap<String,Object>();while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){String field=parser.currentName();parser.nextToken();if(Set.of("kind","conflictId","options").contains(field))metadata.put(field,mapper.readValue(parser,Object.class));else parser.skipChildren();}conflicts.add(metadata);}}
            else if(name.equals("classMap")||name.equals("objectMappings"))result.put(name,mapper.readValue(parser,Map.class));
            else parser.skipChildren();
        }
    }catch(IOException failure){throw new UncheckedIOException(failure);}
    result.put("candidateMappings",candidates);result.put("conflicts",conflicts);return result;
}

}
