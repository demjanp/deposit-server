package deposit.service;

import deposit.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** Streaming XLSX writer for staged tabular data. Resource policy is supplied by the caller. */
final class XlsxTableWriter {
    private XlsxTableWriter() {}

    record ResourceLink(String label,String url) {}

    static void write(Path destination,List<?> columns,Path tablePath,long rowCount,String sheetName,Runnable check,Function<Object,ResourceLink> resourceLink)throws IOException{
        requireDimensions(rowCount,columns.size());
        try(SXSSFWorkbook workbook=new SXSSFWorkbook(100)){
            workbook.setCompressTempFiles(true);Sheet sheet=workbook.createSheet(sheetName);Row header=sheet.createRow(0);for(int i=0;i<columns.size();i++){String name=(String)ValueCodec.map(columns.get(i)).get("name");if(name.length()>32_767)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX header exceeds 32,767 UTF-16 code units");header.createCell(i,CellType.STRING).setCellValue(name);}
            int index=1;try(Json.ArrayReader reader=new Json.ArrayReader(tablePath,"rows")){Map<String,Object> row;while((row=reader.next())!=null){check.run();Map<String,Object> values=ValueCodec.map(row.get("values")),presence=ValueCodec.map(row.get("presence"));Row out=sheet.createRow(index++);for(int i=0;i<columns.size();i++){Map<String,Object> col=ValueCodec.map(columns.get(i));String name=(String)col.get("name"),type=(String)col.get("type");if(!Boolean.TRUE.equals(presence.get(name)))continue;Object value=values.get(name);if(value==null)continue;Cell cell=out.createCell(i);writeCell(cell,type,value,row.get("rowId"),name,resourceLink);}}}
            try(OutputStream output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){workbook.write(output);}finally{workbook.dispose();}
        }
    }

    static void requireDimensions(long dataRows,long columns){if(dataRows<0||columns<0)throw new IllegalArgumentException("XLSX dimensions cannot be negative");if(dataRows>1_048_575L||columns>16_384L)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX row or column limit exceeded");}

    private static void writeCell(Cell cell,String type,Object value,Object rowId,String column,Function<Object,ResourceLink> resourceLink){
        if(type.equals("DResource")){ResourceLink link=resourceLink.apply(value);if(link.label().length()>32_767)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX cell exceeds 32,767 UTF-16 code units at row "+rowId+", column "+column);Hyperlink hyperlink=cell.getSheet().getWorkbook().getCreationHelper().createHyperlink(HyperlinkType.URL);hyperlink.setAddress(link.url());cell.setCellValue(link.label());cell.setHyperlink(hyperlink);return;}
        if(type.equals("Integer")&&value instanceof Number n){long number=n.longValue();String text=Long.toString(number);if(text.replace("-","").length()<=15&&java.math.BigDecimal.valueOf((double)number).toBigInteger().equals(java.math.BigInteger.valueOf(number))){cell.setCellValue((double)number);return;}cell.setCellValue(text);return;}
        if(type.equals("Float")&&value instanceof Number n){double number=n.doubleValue();if(Double.isFinite(number)&&!(number==0&&Double.doubleToRawLongBits(number)<0)){String round=String.format(Locale.ROOT,"%.15g",number);try{if(Double.doubleToRawLongBits(Double.parseDouble(round))==Double.doubleToRawLongBits(number)){cell.setCellValue(number);return;}}catch(NumberFormatException ignored){}}cell.setCellValue(Double.toString(number));return;}
        String text=TabularValueCodec.exportText(type,value);if(text.length()>32_767)throw ExceptionHandler.fail("LIMIT_EXCEEDED","XLSX cell exceeds 32,767 UTF-16 code units at row "+rowId+", column "+column);cell.setCellValue(text);
    }
}
