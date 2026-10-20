package deposit.service;

import deposit.common.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Path-based, streaming CSV input and output for staged tables. */
final class CsvTableFormat {
    private CsvTableFormat() {}

    static Map<String,Object> read(Path file,Path destination,char delimiter,int headerRow,Map<String,Object> typeMap,Map<String,Object> formats,String decimal,Runnable check)throws IOException{
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var input=new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(file),decoder)),1);var output=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            int first=input.read();if(first!=0xfeff&&first!=-1)input.unread(first);List<String> header=null;for(int i=1;i<=headerRow;i++){header=readRecord(input,delimiter);if(header==null)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");}
            List<String> names=header;Set<String> unique=new HashSet<>();List<Map<String,Object>> columns=new ArrayList<>();for(String name:names){if(name.isEmpty()||!unique.add(java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC)))throw ExceptionHandler.fail("VALIDATION_FAILED","Header names must be nonempty and unique");columns.add(Json.map("name",name,"type",typeMap.containsKey(name)?ValueCodec.type(typeMap.get(name)):"String"));}
            for(String key:typeMap.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","columnTypes names an unknown column");for(String key:formats.keySet())if(!names.contains(key))throw ExceptionHandler.fail("VALIDATION_FAILED","dateFormats names an unknown column");
            output.write("{\"columns\":");output.write(Json.write(columns));output.write(",\"rows\":[");List<Map<String,Object>> diagnostics=new ArrayList<>();boolean valid=true;long rowId=0,errorCount=0;List<String> record;boolean firstRow=true;
            while((record=readRecord(input,delimiter))!=null){check.run();if(record.size()==1&&record.getFirst().isEmpty())record=new ArrayList<>(Collections.nCopies(names.size(),""));if(record.size()!=names.size())throw ExceptionHandler.fail("VALIDATION_FAILED","CSV record field count differs from header");rowId++;Map<String,Object> values=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int j=0;j<names.size();j++){String name=names.get(j),cell=record.get(j),type=(String)columns.get(j).get("type");presence.put(name,true);try{values.put(name,cell.isEmpty()?"":TabularValueCodec.parseCell(type,cell,decimal,formats.get(name)));}catch(RuntimeException ex){values.put(name,cell);valid=false;errorCount++;if(diagnostics.size()<1000)diagnostics.add(Json.map("severity","error","code","CELL_CONVERSION","rowId",Long.toString(rowId),"column",name,"message","Cell cannot be parsed as "+type));}}
                if(!firstRow)output.write(',');firstRow=false;output.write(Json.write(Json.map("rowId",Long.toString(rowId),"values",values,"presence",presence)));
            }
            output.write("],\"rowCount\":");output.write(Long.toString(rowId));output.write(",\"valid\":");output.write(Boolean.toString(valid));output.write(",\"diagnostics\":");output.write(Json.write(diagnostics));output.write(",\"diagnosticsTruncated\":");output.write(Boolean.toString(errorCount>diagnostics.size()));output.write('}');return Json.map("columns",columns,"rowCount",rowId,"valid",valid,"diagnostics",diagnostics,"diagnosticsTruncated",errorCount>diagnostics.size());
        }catch(CharacterCodingException ex){throw new IOException("Invalid UTF-8",ex);}
    }

    static void write(Path destination,List<?> columns,Path tablePath,char delimiter,Runnable check,Function<Object,String> resourceLink)throws IOException{
        try(BufferedWriter output=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Json.ArrayReader reader=new Json.ArrayReader(tablePath,"rows")){
            for(int i=0;i<columns.size();i++){if(i>0)output.write(delimiter);writeValue(output,String.valueOf(ValueCodec.map(columns.get(i)).get("name")),delimiter);}output.write("\r\n");
            Map<String,Object> row;while((row=reader.next())!=null){check.run();Map<String,Object> values=ValueCodec.map(row.get("values")),presence=ValueCodec.map(row.get("presence"));for(int i=0;i<columns.size();i++){if(i>0)output.write(delimiter);Map<String,Object> column=ValueCodec.map(columns.get(i));String name=(String)column.get("name"),type=(String)column.get("type");Object value=Boolean.TRUE.equals(presence.get(name))?values.get(name):null;writeValue(output,type.equals("DResource")&&value!=null?resourceLink.apply(value):TabularValueCodec.exportText(type,value),delimiter);}output.write("\r\n");}
        }
    }

    private static List<String> readRecord(PushbackReader input,char delimiter)throws IOException{
        List<String> fields=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,atFieldStart=true,consumed=false;int raw;
        while((raw=input.read())!=-1){consumed=true;char ch=(char)raw;if(quoted){if(ch=='"'){int next=input.read();if(next=='"')cell.append('"');else{quoted=false;if(next!=-1)input.unread(next);}}else cell.append(ch);continue;}
            if(ch=='"'){if(!atFieldStart)throw ExceptionHandler.fail("VALIDATION_FAILED","Unexpected CSV quote");quoted=true;atFieldStart=false;continue;}if(ch==delimiter){fields.add(cell.toString());cell.setLength(0);atFieldStart=true;continue;}if(ch=='\r'||ch=='\n'){if(ch=='\r'){int next=input.read();if(next!='\n'&&next!=-1)input.unread(next);}fields.add(cell.toString());return fields;}cell.append(ch);atFieldStart=false;
        }
        if(quoted)throw ExceptionHandler.fail("VALIDATION_FAILED","Unterminated CSV quote");if(!consumed&&cell.isEmpty()&&fields.isEmpty())return null;fields.add(cell.toString());return fields;
    }

    private static void writeValue(Writer output,String value,char delimiter)throws IOException{
        boolean quote=value.indexOf(delimiter)>=0||value.indexOf('"')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0;if(quote)output.write('"');for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(ch=='"')output.write("\"\"");else output.write(ch);}if(quote)output.write('"');
    }
}
