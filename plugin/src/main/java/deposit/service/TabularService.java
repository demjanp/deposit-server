package deposit.service;

import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import deposit.repository.StagedTableRepository.Table;
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
    private boolean resourceLinksUsed;
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
        return c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request)));
    }
    private Map<String,Object> load(String procedure,Map<String,Object> request){
        fields(request,"resourceId","formatOptions");operational("editor");lockOperationalScope("editor");
        String hash=operationalHash(procedure,request);
        String id=RequestContext.uuid(request.get("resourceId"));Node source=resources.resource(c.namespaceId,id);
        if(source==null||!resources.accessible(source,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        resources.lock(id);var content=resources.verifyCompleted(source);
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
            input=resources.files.path(Table.sourcePath(tableId));
            table=resources.files.path(Table.path(tableId));
            rowIndex=resources.files.path(Table.rowIndexPath(tableId));
            Files.createDirectories(input.getParent());
            var admission=c.server.storage.admission;
            var staging=admission.acquire("tabular-load",tableId,c.operationId,"table-staging",Map.of(input,content.bytes()),
                Json.map("phase","table-load","actorId",c.actorId,"receiptScope",c.namespaceId,"sourceResourceId",id));
            Path retainedSource=input,retainedTable=table,retainedIndex=rowIndex;
            boolean[] committed={false};
            c.state.afterCommit.add(()->committed[0]=true);
            c.state.release.add(()->{
                boolean scratchAbsent=!Files.exists(retainedSource,LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(retainedTable.resolveSibling(retainedTable.getFileName()+".work"),LinkOption.NOFOLLOW_LINKS);
                if(scratchAbsent&&(committed[0]||!Files.exists(retainedTable,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(retainedIndex,LinkOption.NOFOLLOW_LINKS)))
                    try{admission.closeIfPresent(staging.id);}catch(IOException|RuntimeException e){c.server.storage.diagnostic="Table staging ownership cleanup pending";}
            });
            try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(input,StandardOpenOption.CREATE_NEW)){
                byte[] buffer=new byte[64*1024];int count;
                while((count=in.read(buffer))!=-1){c.state.kernel.assertOpen();out.write(buffer,0,count);}
            }
            if(Files.size(input)!=content.bytes()||!ResourceFiles.checksum(input).equals(content.checksum()))c.fail("RESOURCE_CORRUPT","Source resource changed during table load");
            admission.materialized(staging,input,content.bytes());
            TabularJsonWriter.Target target=path->admission.openStagingOutput(staging,path,c.state.kernel::assertOpen);
            var parsed=csv?CsvTableFormat.read(input,table,delimiter,header,types,dateFormats,decimal,c.state.kernel::assertOpen,target):xlsx?XlsxTableReader.parseXlsx(input,table,options,header,types,dateFormats,decimal,c.state.kernel::assertOpen,target):GeoPackageAdapter.load(input,table,options,types,dateFormats,decimal,c.state.kernel::assertOpen,target);
            requireContentLimit(Files.size(table),"Generated table staging exceeds the content limit");
            long indexBytes=Math.multiplyExact(((Number)parsed.get("rowCount")).longValue(),Long.BYTES);
            admission.grow(staging,Map.of(rowIndex,indexBytes));
            long indexed=Json.indexArray(table,"rows",rowIndex);if(indexed!=((Number)parsed.get("rowCount")).longValue())throw new IOException("Staged row index count differs from parsed table");
            admission.materialized(staging,rowIndex,indexBytes);
            long now=resources.now();
            var metadata=new Table(tableId,c.namespaceId,c.actorId,id,
                now,now+TABLE_TTL,Json.write(parsed.get("columns")),indexed,Boolean.TRUE.equals(parsed.get("valid")));
            String unit=c.server.prepareWorkflowCommit(c,c.namespaceId,repo.namespace(c.namespaceId));
            c.server.storage.tables.create(metadata,unit);
            Map<String,Object> result=Json.map("tableId",tableId,"columns",parsed.get("columns"),"rowCount",parsed.get("rowCount"),"valid",parsed.get("valid"),"diagnostics",parsed.get("diagnostics"),"diagnosticsTruncated",parsed.get("diagnosticsTruncated"));
            repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));
            // Table metadata and receipt commit together. Also clean files
            // if the caller later rolls back an otherwise successful load transaction.
            Path completedTable=table,completedIndex=rowIndex;
            c.state.release.add(()->{if(!committed[0]){discard(completedTable);discard(completedIndex);}});
            Files.deleteIfExists(input);input=null;completed=true;return result;
        }catch(StorageAdmission.StoragePressureException|ArithmeticException e){throw ExceptionHandler.fail("LIMIT_EXCEEDED","Insufficient or unrepresentable table staging capacity");}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage table");}
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

    private Table tableNode(Object raw){return new StagedTable(c).acquire(raw);}
    private Stream<Results.QueryRowResult> table(Map<String,Object> request){fields(request,"tableId");readContext();scoped("viewer");Table table=tableNode(request.get("tableId"));List<Map<String,Object>> columns=Json.readList(table.columns()).stream().map(ValueCodec::map).toList();final Json.ArrayReader reader;try{reader=new Json.ArrayReader(resources.files.path(table.path()),"rows");}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");return Stream.empty();}Iterator<Results.QueryRowResult> iterator=new Iterator<>(){Results.QueryRowResult next;boolean loaded,done;public boolean hasNext(){if(loaded)return true;if(done)return false;try{StagedTable.checkpoint(c);Map<String,Object> row=reader.next();if(row==null){done=true;return false;}next=new Results.QueryRowResult(Json.map("rowId",row.get("rowId"),"values",row.get("values")),columns,ValueCodec.map(row.get("presence")),c.meta());loaded=true;return true;}catch(IOException e){throw new UncheckedIOException(e);}}public Results.QueryRowResult next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator,Spliterator.ORDERED),false).onClose(()->{try{reader.close();}catch(IOException e){throw new UncheckedIOException(e);}});}
    private DataRow page(Map<String,Object> request){fields(request,"tableId","pageSize","cursor");readContext();scoped("viewer");Table table=tableNode(request.get("tableId"));int size=(int)integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");long count=table.rows(),offset=0;String hash=Json.hash(table.id());if(request.containsKey("cursor")){Map<String,Object> cur=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cur.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");if(!c.server.runId.equals(cur.get("incarnation"))||!c.actorId.equals(cur.get("actor"))||!hash.equals(cur.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match table");if(((Number)cur.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Namespace revision changed");offset=((Number)cur.get("offset")).longValue();if(offset<0||offset>count)c.fail("VALIDATION_FAILED","Invalid cursor offset");}var rows=new ArrayList<Map<String,Object>>(Math.min(size,200));try(RandomAccessFile index=new RandomAccessFile(resources.files.path(table.rowIndexPath()).toFile(),"r")){index.seek(offset*Long.BYTES);Path data=resources.files.path(table.path());for(int i=0;i<size&&offset+i<count;i++){StagedTable.checkpoint(c);long byteOffset=index.readLong();rows.add(Json.objectAt(data,byteOffset));}}catch(IOException e){c.fail("RESOURCE_CORRUPT","Staged table content unavailable");}long end=offset+rows.size();String next=null;if(end<count)next=CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,"incarnation",c.server.runId,"revision",c.revision,"offset",end,"expires",System.currentTimeMillis()+900000L));Map<String,Object> meta=c.meta();meta.put("nextCursor",next);return new DataRow(Json.map("columns",Json.readList(table.columns()),"rows",rows),meta);}

    private Map<String,Object> save(String procedure,Map<String,Object> request){
        fields(request,"source","parameters","formatOptions");operational("viewer");
        String hash=operationalHash(procedure,request);
        Map<String,Object> source=ValueCodec.map(request.get("source")),format=request.get("formatOptions")==null?Map.of():ValueCodec.map(request.get("formatOptions"));fields(source,"tableId","query");
        if(source.containsKey("tableId")==source.containsKey("query"))c.fail("VALIDATION_FAILED","Exactly one of tableId or query is required");
        boolean csv=procedure.endsWith(".csv"),xlsx=procedure.endsWith(".xlsx"),gpkg=procedure.endsWith(".gpkg");if(!csv&&!xlsx&&!gpkg)c.fail("VALIDATION_FAILED","Unsupported export format");
        if(csv)fields(format,"filename","delimiter");else if(xlsx)fields(format,"filename","sheet");else fields(format,"filename","layer");char delimiter=csv?delimiter(format.getOrDefault("delimiter",",")):',';String filename=string(format.getOrDefault("filename",gpkg?"export.gpkg":xlsx?"export.xlsx":"export.csv"));String layer=string(format.getOrDefault("layer","data"));
        String extension=gpkg?".gpkg":xlsx?".xlsx":".csv";if(filename.contains("/")||filename.contains("\\")||!filename.toLowerCase(Locale.ROOT).endsWith(extension))c.fail("VALIDATION_FAILED","filename must have the matching format extension");String sheetName=string(format.getOrDefault("sheet","Data"));if(xlsx&&(sheetName.isBlank()||sheetName.length()>31||sheetName.matches(".*[\\[\\]:*?/\\\\].*")||sheetName.startsWith("'")||sheetName.endsWith("'")))c.fail("VALIDATION_FAILED","Invalid XLSX sheet name");
        String tableId=null;List<?> columns;long rowCount;Path tablePath,queryStage=null;Long collectionRevision=null;
        if(source.containsKey("tableId")){tableId=RequestContext.uuid(source.get("tableId"));Table table=tableNode(tableId);if(!table.valid())c.fail("VALIDATION_FAILED","Invalid staged table cannot be exported");columns=Json.readList(table.columns());rowCount=table.rows();tablePath=stagedFile(table.path());}
        else {Map<String,Object> queryRequest=Json.map("query",source.get("query"),"parameters",request.getOrDefault("parameters",Map.of()));collectionRevision=c.revision;Results.DataRow schema=queryPage(queryRequest,collectionRevision);columns=(List<?>)schema.data.get("columns");if(columns.stream().anyMatch(raw->"Object".equals(ValueCodec.map(raw).get("type"))))c.fail("VALIDATION_FAILED","Native Object projections cannot be exported; select scalar Descriptors");try{queryStage=resources.files.path("staging/exports/query-"+UUID.randomUUID()+".json");Files.createDirectories(queryStage.getParent());rowCount=stageQuery(queryRequest,columns,queryStage,collectionRevision);tablePath=queryStage;c.server.storagePoint("query-export-before-lock");if(repo.sequence(c.namespaceId)!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");}catch(IOException e){discard(queryStage);c.fail("RESOURCE_CORRUPT","Unable to stage query export");return Map.of();}catch(RuntimeException e){discard(queryStage);throw e;}}
        try{lockOperationalScope("viewer");if(collectionRevision!=null&&c.revision!=collectionRevision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");if(collectionRevision!=null)c.server.storagePoint("query-export-after-lock");}catch(RuntimeException e){discard(queryStage);throw e;}
        long revision=c.revision;
        Path stage=null;List<GeoPackageAdapter.Output> gpkgFiles=new ArrayList<>();
        try{
            Files.createDirectories(resources.files.path("staging/exports"));
            if(gpkg){Path directory=resources.files.path("staging/exports");gpkgFiles=GeoPackageAdapter.write(tablePath,columns,filename,layer,directory,()->StagedTable.checkpoint(c),this::dresourceMarkdown);if(gpkgFiles.size()==1)stage=gpkgFiles.getFirst().path();else{stage=resources.files.path("staging/exports/"+UUID.randomUUID()+".zip");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW))){for(var file:gpkgFiles){zip.putNextEntry(new ZipEntry(file.filename()));Files.copy(file.path(),zip);zip.closeEntry();}}}}
            else if(csv){stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension);CsvTableFormat.write(stage,columns,tablePath,delimiter,()->StagedTable.checkpoint(c),this::dresourceMarkdown);}
            else if(xlsx){stage=resources.files.path("staging/exports/"+UUID.randomUUID()+extension);XlsxTableWriter.write(stage,columns,tablePath,rowCount,sheetName,()->StagedTable.checkpoint(c),value->{ResourceLink link=resourceLink(value);return new XlsxTableWriter.ResourceLink(link.label(),link.url());});}
            c.state.tableReadDeadline=Long.MAX_VALUE;
            if(repo.sequence(c.namespaceId)!=revision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");long bytes=Files.size(stage);requireContentLimit(bytes,"Generated export exceeds the content limit");String checksum=ResourceFiles.checksum(stage),resourceId=UUID.randomUUID().toString(),resultFilename=gpkg&&gpkgFiles.size()>1?filename.substring(0,filename.length()-5)+".zip":filename,stored=ResourceFiles.filename(resultFilename,checksum);var content=new ResourceStorage.Content(resourceId,stored,bytes,checksum);resources.lock(resourceId);
            c.server.storagePoint("tabular-export-before-publication");
            StorageAdmission.Reservation publicationAdmission;
            try{long commitment=Math.addExact(bytes,64L*1024);publicationAdmission=c.server.storage.admission.acquire("tabular-export",resourceId,c.operationId,"tabular-publication",Map.of(resources.files.root,commitment),Json.map("phase","ocfl-publication","resourceId",resourceId,"receiptScope",c.namespaceId,"actorId",c.actorId,"expectedBytes",bytes));}
            catch(StorageAdmission.StoragePressureException pressure){c.fail("LIMIT_EXCEEDED",pressure.getMessage());return Map.of();}
            c.state.release.add(()->{try{c.server.storage.admission.close(publicationAdmission);}catch(IOException failure){c.server.storage.diagnostic="Tabular publication admission cleanup pending";}});
            try(var publication=c.server.resourceLinkPublication(resourceLinksUsed)) {
            resources.publish(content,stage,publicationAdmission);
            String media=gpkg&&gpkgFiles.size()>1?"application/zip":gpkg?"application/geopackage+sqlite3":xlsx?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8";
            resources.complete(c.namespaceId,resultFilename,media,content,c.actorId,c.timestamp);
            String unit=c.server.prepareWorkflowCommit(c,c.namespaceId,repo.namespace(c.namespaceId));
            c.server.storage.exportGrants.create(UUID.randomUUID().toString(),resourceId,c.namespaceId,c.actorId,
                false,resources.now()+ResourceFiles.LEASE,unit);
            List<Map<String,Object>> files=gpkg?gpkgFiles.stream().map(file->Json.map("filename",file.filename(),"bytes",file.bytes(),"checksum",file.checksum())).toList():List.of(Json.map("filename",filename,"bytes",bytes,"checksum",checksum));var warningMap=new LinkedHashMap<String,Map<String,Object>>();boolean warningTruncated=false;if(gpkg)for(var file:gpkgFiles){warningTruncated|=file.warningsTruncated();for(var warning:file.warnings())warningMap.putIfAbsent(Json.write(warning),warning);}List<Map<String,Object>> warnings=warningMap.values().stream().limit(1000).toList();warningTruncated|=warningMap.size()>warnings.size();Map<String,Object> result=Json.map("resourceId",resourceId,"filename",resultFilename,"mediaType",media,"bytes",bytes,"checksum",checksum,"files",files,"warnings",warnings,"warningsTruncated",warningTruncated);repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));return result;
            }
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to finalize tabular export");}finally{if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}for(var file:gpkgFiles)try{Files.deleteIfExists(file.path());}catch(IOException ignored){}if(queryStage!=null)try{Files.deleteIfExists(queryStage);}catch(IOException ignored){}}
    }
    static void requireContentLimit(long bytes,String message){if(bytes<0)throw new IllegalArgumentException("Content size cannot be negative");}
    private long stageQuery(Map<String,Object> queryRequest,List<?> columns,Path destination,Long pinnedRevision)throws IOException{
        Stream<Results.QueryRowResult> query=queryStream(queryRequest,pinnedRevision);try(BufferedWriter out=Files.newBufferedWriter(destination,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Stream<Results.QueryRowResult> rows=query){
            TabularJsonWriter.header(out,(List<?>)columns);final long[] index={0};Iterator<Results.QueryRowResult> it=rows.iterator();while(it.hasNext()){c.state.kernel.assertOpen();Results.QueryRowResult row=it.next();TabularJsonWriter.row(out,index[0]==0,Long.toString(index[0]+1),row.data,row.presence);index[0]++;}TabularJsonWriter.footer(out,index[0],true);return index[0];}
    }
    private Results.DataRow queryPage(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQueryPage(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private Stream<Results.QueryRowResult> queryStream(Map<String,Object> queryRequest,Long pinnedRevision){String operation=c.operationId;Long expected=c.expectedRevision;c.operationId=null;c.expectedRevision=null;try{return new GetQueryService(c).getQuery(queryRequest,pinnedRevision);}finally{c.operationId=operation;c.expectedRevision=expected;}}
    private record ResourceLink(String label,String url){}
    private ResourceLink resourceLink(Object value){if(!resourceLinksUsed){c.server.requireResourceLinks();resourceLinksUsed=true;}String id=RequestContext.uuid(ValueCodec.map(value).get("resourceId"));Node resource=resources.resource(c.namespaceId,id);if(resource==null||!"ready".equals(resource.getProperty("state")))c.fail("NOT_FOUND","Resource link target unavailable");String filename=(String)resource.getProperty("storedFilename","download"),url=ResourceLinkEncoder.url(c.server.downloadPublicBaseUrl,c.namespaceId,id);return new ResourceLink(filename,url);}
    private String dresourceMarkdown(Object value){ResourceLink link=resourceLink(value);String id=RequestContext.uuid(ValueCodec.map(value).get("resourceId"));return ResourceLinkEncoder.markdown(c.server.downloadPublicBaseUrl,c.namespaceId,id,link.label());}
    private Path stagedFile(String relative){try{return resources.files.path(relative);}catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Staged table content unavailable");}}
    private static void discard(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){}}

}
