package deposit.service;

import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.neo4j.graphdb.Node;

/** Operational table staging and tabular procedure entry points. */
public final class TabularService extends BaseService {
    private static final long TABLE_TTL=86_400_000L;
    private final ResourceRepository resources;
    public TabularService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Object execute(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.load.csv","deposit.load.xlsx","deposit.load.gpkg"->load(procedure,request);
            case "deposit.get.table"->table(request);
            case "deposit.get.tablePage"->page(request);
            case "deposit.get.layout"->new LayoutService(c).layout(request);
            case "deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg"->save(procedure,request);
            case "deposit.import.rows"->new ImportService(c).importRows(request);
            default->throw ExceptionHandler.fail("VALIDATION_FAILED","Unsupported tabular operation");
        };
    }

    private void operational(String role){
        scoped(role);
        OperationalGuards.operationalContext(c);
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","One mutation per transaction");
        c.state.mutation=true;repo.lockActor(c.actorId);
    }
    private void lockOperationalScope(String role){
        repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,role);c.revision=repo.sequence(c.namespaceId);
    }
    private String operationalHash(String procedure,Map<String,Object> request){
        Node binding=repo.binding(c.actorId,c.operationId);
        if(binding!=null)OperationalGuards.bindingScope(c,c.namespaceId,binding.getProperty("scope"));
        return CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request)));
    }
    private Map<String,Object> replayOperational(Node receipt,String hash){
        c.replayed=true;
        OperationalGuards.receiptActor(c,receipt.getProperty("actorId"));
        OperationalGuards.receiptHash(c,hash,receipt.getProperty("requestHash"));
        return repo.replay(receipt,c.namespaceId).data();
    }

    private Map<String,Object> load(String procedure,Map<String,Object> request){
        fields(request,"resourceId","formatOptions");operational("editor");lockOperationalScope("editor");
        String hash=operationalHash(procedure,request);Node receipt=repo.receipt(c.namespaceId,c.operationId);
        // A committed result remains recoverable even after its table or input expires.
        // Replaying never reads or renews staging, and never recreates expired content.
        if(receipt!=null)return replayOperational(receipt,hash);
        String id=RequestContext.uuid(request.get("resourceId"));Node source=resources.resource(c.namespaceId,id);
        if(source==null||!resources.accessible(source,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        Node location=resources.location(id);resources.lock(id);var content=resources.verify(location);
        requireContentLimit(content.bytes(),"Source file exceeds the content limit");
        Map<String,Object> options=request.get("formatOptions")==null?Map.of():ValueCodec.map(request.get("formatOptions"));
        boolean csv=procedure.endsWith(".csv"),xlsx=procedure.endsWith(".xlsx"),gpkg=procedure.endsWith(".gpkg");
        if(!csv&&!xlsx&&!gpkg)c.fail("VALIDATION_FAILED","Unsupported table format");
        if(csv)fields(options,"delimiter","headerRow","columnTypes","dateFormats","decimalSeparator");
        else if(xlsx)fields(options,"sheet","headerRow","columnTypes","dateFormats","decimalSeparator");else fields(options,"layer","geometryColumn","columnTypes","dateFormats","decimalSeparator");
        char delimiter=csv?delimiter(options.getOrDefault("delimiter",",")):',';int header=(int)positive(options.getOrDefault("headerRow",1L),"headerRow");
        String decimal=String.valueOf(options.getOrDefault("decimalSeparator","."));if(!Set.of(".",",").contains(decimal))c.fail("VALIDATION_FAILED","decimalSeparator must be '.' or ','");
        Map<String,Object> types=options.containsKey("columnTypes")?ValueCodec.map(options.get("columnTypes")):Map.of();Map<String,Object> dateFormats=options.containsKey("dateFormats")?ValueCodec.map(options.get("dateFormats")):Map.of();
        Path input=null,table=null,rowIndex=null;boolean completed=false;String tableId=UUID.randomUUID().toString();
        try{
            input=resources.files.path("staging/tables/"+tableId+".source");Files.createDirectories(input.getParent());
            try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(input,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}
            if(Files.size(input)!=content.bytes()||!ResourceFiles.checksum(input).equals(content.checksum()))c.fail("RESOURCE_CORRUPT","Source resource changed during table load");
            table=resources.files.path("staging/tables/"+tableId+".json");rowIndex=resources.files.path("staging/tables/"+tableId+".idx");
            var parsed=csv?CsvTableFormat.read(input,table,delimiter,header,types,dateFormats,decimal,c.state.kernel::assertOpen):xlsx?XlsxTableReader.parseXlsx(input,table,options,header,types,dateFormats,decimal,c.state.kernel::assertOpen):GeoPackageAdapter.load(input,table,options,types,dateFormats,decimal,c.state.kernel::assertOpen);
            requireContentLimit(Files.size(table),"Generated table staging exceeds the content limit");
            long indexed=Json.indexArray(table,"rows",rowIndex);if(indexed!=((Number)parsed.get("rowCount")).longValue())throw new IOException("Staged row index count differs from parsed table");
            long now=resources.now();resources.record("DepositTabularTable",tableId,Json.map("namespaceId",c.namespaceId,"creatorId",c.actorId,"sourceResourceId",id,"path",resources.files.root.relativize(table).toString().replace('\\','/'),"rowIndexPath",resources.files.root.relativize(rowIndex).toString().replace('\\','/'),"createdAt",now,"lastAccessAt",now,"expiresAt",now+TABLE_TTL,"columnsJson",Json.write(parsed.get("columns")),"rowCount",parsed.get("rowCount"),"valid",parsed.get("valid"),"diagnosticsJson",Json.write(parsed.get("diagnostics")),"diagnosticsTruncated",parsed.get("diagnosticsTruncated")));
            resources.record("DepositResourceLease",UUID.randomUUID().toString(),Json.map("resourceId",id,"namespaceId",c.namespaceId,"type","table","tableId",tableId,"expires",Long.MAX_VALUE));
            Map<String,Object> result=Json.map("tableId",tableId,"columns",parsed.get("columns"),"rowCount",parsed.get("rowCount"),"valid",parsed.get("valid"),"diagnostics",parsed.get("diagnostics"),"diagnosticsTruncated",parsed.get("diagnosticsTruncated"));
            repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));
            // Metadata, protective lease and receipt commit together. Also clean files
            // if the caller later rolls back an otherwise successful load transaction.
            Path completedTable=table,completedIndex=rowIndex;boolean[] committed={false};
            c.state.afterCommit.add(()->committed[0]=true);
            c.state.release.add(()->{if(!committed[0]){discard(completedTable);discard(completedIndex);}});
            Files.deleteIfExists(input);input=null;completed=true;return result;
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage table");}
        finally{if(!completed){if(table!=null)try{Files.deleteIfExists(table);}catch(IOException ignored){}if(rowIndex!=null)try{Files.deleteIfExists(rowIndex);}catch(IOException ignored){}if(input!=null)try{Files.deleteIfExists(input);}catch(IOException ignored){}}}
    }

    private Map<String,Object> parseRecords(List<List<String>> records,int headerRow,Map<String,Object> typeMap,Map<String,Object> formats,String decimal){
        if(records.size()<headerRow)throw ExceptionHandler.fail("VALIDATION_FAILED","Header row is missing");
        List<String> names=new ArrayList<>(records.get(headerRow-1));Set<String> unique=new HashSet<>();List<Map<String,Object>> columns=new ArrayList<>();
        for(String name:names)columns.add(TabularPolicy.projectHeader(name,unique,typeMap));
        TabularPolicy.validateOptions(names,typeMap,formats);
        List<Map<String,Object>> rows=new ArrayList<>();TabularDiagnostics diagnostics=new TabularDiagnostics();boolean valid=true;long rowId=0;
        for(int i=headerRow;i<records.size();i++){
            c.state.kernel.assertOpen();
            List<String> record=records.get(i);if(i==records.size()-1&&record.size()==1&&record.getFirst().isEmpty())continue;
            if(record.size()!=names.size())throw ExceptionHandler.fail("VALIDATION_FAILED","CSV record field count differs from header");rowId++;
            Map<String,Object> values=new LinkedHashMap<>(),presence=new LinkedHashMap<>();for(int j=0;j<names.size();j++){
                String name=names.get(j),cell=record.get(j),type=(String)columns.get(j).get("type");presence.put(name,true);
                try{values.put(name,cell.isEmpty()?"":TabularValueCodec.parseCell(type,cell,decimal,formats.get(name)));}
                catch(RuntimeException ex){values.put(name,cell);valid=false;diagnostics.add("CELL_CONVERSION",Long.toString(rowId),name,"Cell cannot be parsed as "+type);}
            }
            rows.add(Json.map("rowId",Long.toString(rowId),"values",values,"presence",presence));
        }
        return Json.map("columns",columns,"rows",rows,"rowCount",(long)rows.size(),"valid",valid,"diagnostics",diagnostics.items(),"diagnosticsTruncated",diagnostics.truncated());
    }

    static List<List<String>> csv(String text,char delimiter){List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=false;}else cell.append(ch);}else if(ch=='"'){if(cell.length()!=0)throw ExceptionHandler.fail("VALIDATION_FAILED","Unexpected CSV quote");quoted=true;}else if(ch==delimiter){row.add(cell.toString());cell.setLength(0);}else if(ch=='\r'||ch=='\n'){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);}else cell.append(ch);}if(quoted)throw ExceptionHandler.fail("VALIDATION_FAILED","Unterminated CSV quote");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}return rows;}
    private static char delimiter(Object raw){String s=string(raw);if(s.length()!=1||"\"\r\n\0".indexOf(s.charAt(0))>=0)throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid CSV delimiter");return s.charAt(0);}
    private long positive(Object value,String field){long n=integer(value);if(n<1||n>1_000_000)c.fail("LIMIT_EXCEEDED",field+" must be positive");return n;}

    private Node tableNode(Object raw){String id=RequestContext.uuid(raw);repo.lockNamespace(c.namespaceId);Node table=repo.find("DepositTabularTable","id",id);if(table!=null&&!c.namespaceId.equals(table.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Table belongs to another Namespace");if(table==null||number(table,"expiresAt",0)<=resources.now())c.fail("NOT_FOUND","Table is unavailable");if(!c.actorId.equals(table.getProperty("creatorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("NOT_FOUND","Table is unavailable");table.setProperty("lastAccessAt",resources.now());table.setProperty("expiresAt",resources.now()+TABLE_TTL);return table;}
    private static long number(Node n,String key,long d){return ((Number)n.getProperty(key,d)).longValue();}
    private Map<String,Object> readData(Node table){try(InputStream in=Files.newInputStream(resources.files.path((String)table.getProperty("path")))){return Json.read(in);}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");return Map.of();}}
    private Stream<Results.QueryRowResult> table(Map<String,Object> request){fields(request,"tableId");readContext();scoped("viewer");Node table=tableNode(request.get("tableId"));List<Map<String,Object>> columns=Json.readList((String)table.getProperty("columnsJson")).stream().map(ValueCodec::map).toList();final Json.ArrayReader reader;try{reader=new Json.ArrayReader(resources.files.path((String)table.getProperty("path")),"rows");}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");return Stream.empty();}Iterator<Results.QueryRowResult> iterator=new Iterator<>(){Results.QueryRowResult next;boolean loaded,done;public boolean hasNext(){if(loaded)return true;if(done)return false;try{c.state.kernel.assertOpen();Map<String,Object> row=reader.next();if(row==null){done=true;return false;}next=new Results.QueryRowResult(Json.map("rowId",row.get("rowId"),"values",row.get("values")),columns,ValueCodec.map(row.get("presence")),c.meta());loaded=true;return true;}catch(IOException e){throw new UncheckedIOException(e);}}public Results.QueryRowResult next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator,Spliterator.ORDERED),false).onClose(()->{try{reader.close();}catch(IOException e){throw new UncheckedIOException(e);}});}
    private DataRow page(Map<String,Object> request){fields(request,"tableId","pageSize","cursor");readContext();scoped("viewer");Node table=tableNode(request.get("tableId"));int size=(int)integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");long count=number(table,"rowCount",0),offset=0;String hash=Json.hash(CoreRepository.id(table));if(request.containsKey("cursor")){Map<String,Object> cur=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cur.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");if(!c.actorId.equals(cur.get("actor"))||!hash.equals(cur.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match table");if(((Number)cur.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Namespace revision changed");offset=((Number)cur.get("offset")).longValue();if(offset<0||offset>count)c.fail("VALIDATION_FAILED","Invalid cursor offset");}var rows=new ArrayList<Map<String,Object>>(Math.min(size,200));try(RandomAccessFile index=new RandomAccessFile(resources.files.path((String)table.getProperty("rowIndexPath")).toFile(),"r")){index.seek(offset*Long.BYTES);Path data=resources.files.path((String)table.getProperty("path"));for(int i=0;i<size&&offset+i<count;i++){c.state.kernel.assertOpen();long byteOffset=index.readLong();rows.add(Json.objectAt(data,byteOffset));}}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");}long end=offset+rows.size();String next=null;if(end<count)next=CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",end,"expires",System.currentTimeMillis()+900000L));Map<String,Object> meta=c.meta();meta.put("nextCursor",next);return new DataRow(Json.map("columns",Json.readList((String)table.getProperty("columnsJson")),"rows",rows),meta);}

    private Map<String,Object> save(String procedure,Map<String,Object> request){
        fields(request,"source","parameters","formatOptions");operational("viewer");
        String hash=operationalHash(procedure,request);Node receipt=repo.receipt(c.namespaceId,c.operationId);
        // Recover the original export before touching its source. A query may have
        // changed and a staged input may have expired since the original commit.
        if(receipt!=null){lockOperationalScope("viewer");return replayOperational(receipt,hash);}
        Map<String,Object> source=ValueCodec.map(request.get("source")),format=request.get("formatOptions")==null?Map.of():ValueCodec.map(request.get("formatOptions"));fields(source,"tableId","query");
        if(source.containsKey("tableId")==source.containsKey("query"))c.fail("VALIDATION_FAILED","Exactly one of tableId or query is required");
        boolean csv=procedure.endsWith(".csv"),xlsx=procedure.endsWith(".xlsx"),gpkg=procedure.endsWith(".gpkg");if(!csv&&!xlsx&&!gpkg)c.fail("VALIDATION_FAILED","Unsupported export format");
        if(csv)fields(format,"filename","delimiter");else if(xlsx)fields(format,"filename","sheet");else fields(format,"filename","layer");char delimiter=csv?delimiter(format.getOrDefault("delimiter",",")):',';String filename=string(format.getOrDefault("filename",gpkg?"export.gpkg":xlsx?"export.xlsx":"export.csv"));String layer=string(format.getOrDefault("layer","data"));
        String extension=gpkg?".gpkg":xlsx?".xlsx":".csv";if(filename.contains("/")||filename.contains("\\")||!filename.toLowerCase(Locale.ROOT).endsWith(extension))c.fail("VALIDATION_FAILED","filename must have the matching format extension");String sheetName=string(format.getOrDefault("sheet","Data"));if(xlsx&&(sheetName.isBlank()||sheetName.length()>31||sheetName.matches(".*[\\[\\]:*?/\\\\].*")||sheetName.startsWith("'")||sheetName.endsWith("'")))c.fail("VALIDATION_FAILED","Invalid XLSX sheet name");
        String tableId=null;List<?> columns;long rowCount;Path tablePath,queryStage=null;Long collectionRevision=null;
        if(source.containsKey("tableId")){tableId=RequestContext.uuid(source.get("tableId"));Node table=tableNode(tableId);if(!Boolean.TRUE.equals(table.getProperty("valid")))c.fail("VALIDATION_FAILED","Invalid staged table cannot be exported");columns=Json.readList((String)table.getProperty("columnsJson"));rowCount=number(table,"rowCount",0);tablePath=stagedFile((String)table.getProperty("path"));}
        else {Map<String,Object> queryRequest=Json.map("query",source.get("query"),"parameters",request.getOrDefault("parameters",Map.of()));collectionRevision=c.revision;Results.DataRow schema=queryPage(queryRequest,collectionRevision);columns=(List<?>)schema.data.get("columns");if(columns.stream().anyMatch(raw->"Object".equals(ValueCodec.map(raw).get("type"))))c.fail("VALIDATION_FAILED","Native Object projections cannot be exported; select scalar Descriptors");try{queryStage=resources.files.path("staging/exports/query-"+UUID.randomUUID()+".json");Files.createDirectories(queryStage.getParent());rowCount=stageQuery(queryRequest,columns,queryStage,collectionRevision);tablePath=queryStage;c.server.storagePoint("query-export-before-lock");if(repo.sequence(c.namespaceId)!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");}catch(IOException e){discard(queryStage);c.fail("RESOURCE_CORRUPT","Unable to stage query export");return Map.of();}catch(RuntimeException e){discard(queryStage);throw e;}}
        try{lockOperationalScope("viewer");if(collectionRevision!=null&&c.revision!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");if(collectionRevision!=null)c.server.storagePoint("query-export-after-lock");}catch(RuntimeException e){discard(queryStage);throw e;}
        long revision=c.revision;
        Path stage=null;List<GeoPackageAdapter.Output> gpkgFiles=new ArrayList<>();
        try{
            Files.createDirectories(resources.files.path("staging/exports"));
            if(gpkg){Path directory=resources.files.path("staging/exports");gpkgFiles=GeoPackageAdapter.write(tablePath,columns,filename,layer,directory,c.state.kernel::assertOpen,this::dresourceMarkdown);if(gpkgFiles.size()==1)stage=gpkgFiles.getFirst().path();else{stage=resources.files.path("staging/exports/"+UUID.randomUUID()+".zip");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW))){for(var file:gpkgFiles){zip.putNextEntry(new ZipEntry(file.filename()));Files.copy(file.path(),zip);zip.closeEntry();}}}}
            else if(csv){stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension);CsvTableFormat.write(stage,columns,tablePath,delimiter,c.state.kernel::assertOpen,this::dresourceMarkdown);}
            else if(xlsx){stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension);XlsxTableWriter.write(stage,columns,tablePath,rowCount,sheetName,c.state.kernel::assertOpen,value->{ResourceLink link=resourceLink(value);return new XlsxTableWriter.ResourceLink(link.label(),link.url());});}
            if(repo.sequence(c.namespaceId)!=revision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");long bytes=Files.size(stage);requireContentLimit(bytes,"Generated export exceeds the content limit");String checksum=ResourceFiles.checksum(stage),resourceId=UUID.randomUUID().toString(),resultFilename=gpkg&&gpkgFiles.size()>1?filename.substring(0,filename.length()-5)+".zip":filename,stored=ResourceFiles.filename(resultFilename,checksum);var content=new ResourceStorage.Content(resourceId,stored,bytes,checksum);resources.lock(resourceId);resources.storage.publish(content,stage);resources.storage.verify(content);
            String media=gpkg&&gpkgFiles.size()>1?"application/zip":gpkg?"application/geopackage+sqlite3":xlsx?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8";long now=resources.now();resources.record("DepositResourceLocation",resourceId,Json.map("namespaceId",c.namespaceId,"storedFilename",stored,"bytes",bytes,"checksum",checksum,"leaseUntil",now+ResourceFiles.LEASE,"everAttached",false,"published",true));resources.record("DResource",resourceId,Json.map("namespaceId",c.namespaceId,"filename",resultFilename,"storedFilename",stored,"mediaType",media,"bytes",bytes,"checksum",checksum,"state","ready","createdBy",c.actorId,"updatedBy",c.actorId,"createdAt",c.timestamp,"updatedAt",c.timestamp));resources.record("DepositExportGrant",UUID.randomUUID().toString(),Json.map("resourceId",resourceId,"namespaceId",c.namespaceId,"actorId",c.actorId,"history",false,"expires",now+ResourceFiles.LEASE));
            List<Map<String,Object>> files=gpkg?gpkgFiles.stream().map(file->Json.map("filename",file.filename(),"bytes",file.bytes(),"checksum",file.checksum())).toList():List.of(Json.map("filename",filename,"bytes",bytes,"checksum",checksum));var warningMap=new LinkedHashMap<String,Map<String,Object>>();boolean warningTruncated=false;if(gpkg)for(var file:gpkgFiles){warningTruncated|=file.warningsTruncated();for(var warning:file.warnings())warningMap.putIfAbsent(Json.write(warning),warning);}List<Map<String,Object>> warnings=warningMap.values().stream().limit(1000).toList();warningTruncated|=warningMap.size()>warnings.size();Map<String,Object> result=Json.map("resourceId",resourceId,"filename",resultFilename,"mediaType",media,"bytes",bytes,"checksum",checksum,"files",files,"warnings",warnings,"warningsTruncated",warningTruncated);repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));return result;
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to finalize tabular export");}finally{if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}for(var file:gpkgFiles)try{Files.deleteIfExists(file.path());}catch(IOException ignored){}if(queryStage!=null)try{Files.deleteIfExists(queryStage);}catch(IOException ignored){}}
    }
    static void requireContentLimit(long bytes,String message){if(bytes<0)throw new IllegalArgumentException("Content size cannot be negative");if(bytes>ResourceFiles.MAX_BYTES)throw ExceptionHandler.fail("LIMIT_EXCEEDED",message);}
    private long stageQuery(Map<String,Object> queryRequest,List<?> columns,Path destination,Long pinnedRevision)throws IOException{
        Stream<Results.QueryRowResult> query=queryStream(queryRequest,pinnedRevision);try(BufferedWriter out=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Stream<Results.QueryRowResult> rows=query){
            TabularJsonWriter.header(out,(List<?>)columns);final long[] index={0};Iterator<Results.QueryRowResult> it=rows.iterator();while(it.hasNext()){c.state.kernel.assertOpen();Results.QueryRowResult row=it.next();TabularJsonWriter.row(out,index[0]==0,Long.toString(index[0]+1),row.data,row.presence);index[0]++;}TabularJsonWriter.footer(out,index[0],true,List.of(),false);return index[0];}
    }
    private Results.DataRow queryPage(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQueryPage(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private Stream<Results.QueryRowResult> queryStream(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQuery(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private record ResourceLink(String label,String url){}
    private ResourceLink resourceLink(Object value){String id=RequestContext.uuid(ValueCodec.map(value).get("resourceId"));Node resource=resources.resource(c.namespaceId,id);if(resource==null||!"ready".equals(resource.getProperty("state")))c.fail("NOT_FOUND","Resource link target unavailable");String filename=(String)resource.getProperty("filename","download"),url=ResourceLinkEncoder.url(c.server.downloadPublicBaseUrl,c.namespaceId,id);return new ResourceLink(filename,url);}
    private String dresourceMarkdown(Object value){ResourceLink link=resourceLink(value);String id=RequestContext.uuid(ValueCodec.map(value).get("resourceId"));return ResourceLinkEncoder.markdown(c.server.downloadPublicBaseUrl,c.namespaceId,id,link.label());}
    private Path stagedFile(String relative){try{return resources.files.path(relative);}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Staged table content unavailable");}}
    private static void discard(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){}}

}
