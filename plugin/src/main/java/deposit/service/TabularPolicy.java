package deposit.service;

import deposit.common.ExceptionHandler;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.text.Normalizer;
import java.util.*;

/** Shared tabular header rules; format-specific extraction remains with each reader. */
final class TabularPolicy {
    private TabularPolicy() {}

    static Map<String,Object> projectHeader(String name,Set<String> normalizedNames,Map<String,Object> types){
        if(name.isEmpty()||!normalizedNames.add(Normalizer.normalize(name,Normalizer.Form.NFC)))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Header names must be nonempty and unique");
        return Json.map("name",name,"type",types.containsKey(name)?ValueCodec.type(types.get(name)):"String");
    }

    static void validateOptions(List<String> names,Map<String,Object> types,Map<String,Object> formats){
        for(String key:types.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","columnTypes names an unknown column");
        for(String key:formats.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","dateFormats names an unknown column");
    }
}
