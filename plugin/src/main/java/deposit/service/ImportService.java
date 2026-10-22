package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

/** Fixed-source import plans and atomic application. */
public final class ImportService extends ActionService {
    private final ResourceRepository resources;
    public ImportService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.import.analyze"->analyze(request);
            case "deposit.get.importPlan"->getPlan(request);
            case "deposit.import.apply"->apply(request);
            case "deposit.import.dataByPattern"->dataByPattern(request);
            case "deposit.import.namespaceData"->cloneNamespace(request);
            default->{c.fail("VALIDATION_FAILED","Unsupported import operation");yield Map.of();}
        };
    }

    /** Expand tabular rows into the same immutable package plan used by the atomic import path. */
    public Map<String,Object> importRows(Map<String,Object> request){
        var expander=new ImportRowExpansion(c,repo,resources);
        fields(request,"source","targets","relationships","rules");
        Map<String,Object> source=ValueCodec.map(request.get("source")),targets=ValueCodec.map(request.get("targets"));
        fields(source,"rows","tableId");if(source.containsKey("rows")==source.containsKey("tableId"))c.fail("VALIDATION_FAILED","Exactly one of rows or tableId is required");
        List<?> relationships=request.get("relationships")==null?List.of():request.get("relationships") instanceof List<?> list?list:null;if(relationships==null)c.fail("VALIDATION_FAILED","relationships must be a list");
        Map<String,Object> rules=request.get("rules")==null?Map.of():ValueCodec.map(request.get("rules"));validateRules(rules);
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.rows",request,false,"editor",()->{
                String tableId=null;Iterable<Map<String,Object>> rows;List<Map<String,Object>> columns=null;ImportRowExpansion.RowSource tableRows=null;
                if(source.containsKey("rows")){Object raw=source.get("rows");if(!(raw instanceof List<?> list))c.fail("VALIDATION_FAILED","rows must be a list");rows=expander.inlineRows((List<?>)raw);}
                else {tableId=RequestContext.uuid(source.get("tableId"));Node table=repo.find("DepositTabularTable","id",tableId);if(table!=null&&!c.namespaceId.equals(table.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Table belongs to another Namespace");if(table==null||number(table,"expiresAt",0)<=resources.now()||(!c.actorId.equals(table.getProperty("creatorId"))&& !"owner".equals(effectiveRole(c.namespaceId))))c.fail("NOT_FOUND","Table is unavailable");if(!Boolean.TRUE.equals(table.getProperty("valid")))c.fail("VALIDATION_FAILED","Invalid staged table cannot be imported");table.setProperty("lastAccessAt",resources.now());table.setProperty("expiresAt",resources.now()+ResourceFiles.LEASE);columns=(List<Map<String,Object>>)(List<?>)Json.readList((String)table.getProperty("columnsJson"));try{tableRows=expander.newRowSource(resources.files.path((String)table.getProperty("path")));}catch(IOException e){throw new UncheckedIOException(e);}rows=tableRows;}
                try {
                    try(ImportRowExpansion.TabularExpansion expansion=expander.tabularPackage(rows,columns,targets,relationships,rules)){
                        Map<String,Object> expanded=expansion.data();Map<String,Object> manifest=ValueCodec.map(expanded.get("manifest")),graph=ValueCodec.map(expanded.get("graph"));Map<String,Object> rowIds=ValueCodec.map(expanded.get("rowIds")),clears=ValueCodec.map(expanded.get("clears"));Map<String,Object> effectiveRules=tabularRules(rules,rowIds,ValueCodec.map(expanded.get("blanks")),clears);
                        Path staged=stagedPath("staging/imports/rows-"+UUID.randomUUID()+".zip");retained[0]=staged;ImportRowExpansion.writeTabularPackage(staged,manifest,graph);Map<String,Object> summary=withValidatedPackage(staged,pkg->analyzeSnapshot(pkg,effectiveRules));
                        var advertised=new LinkedHashMap<String,Object>();for(var entry:rowIds.entrySet()){Object target=ValueCodec.map(summary.get("objectMappings")).get(entry.getKey());advertised.put((String)entry.getValue(),target);}
                        summary.put("rowMappings",advertised);String id=UUID.randomUUID().toString(),relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now();String rowIdsJson=encode(rowIds);long targetRevision=repo.sequence(c.namespaceId);String rulesJson=Json.write(effectiveRules),summaryJson=Json.write(summary);Node plan=initializePlan(id,tableId==null?"tabular-inline":tableId,relative,targetRevision,now,now+ResourceFiles.LEASE,rulesJson,summaryJson);if(tableId!=null)plan.setProperty("tableId",tableId);plan.setProperty("rowIdsJson",rowIdsJson);retainStagingUntilCommit(staged);
                        return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"),"rowMappings",advertised)),false);
                    }
                } catch(IOException e){throw new UncheckedIOException(e);} finally {if(tableRows!=null)try{tableRows.close();}catch(IOException e){throw new UncheckedIOException(e);}}
            });
            if(c.replayed){if(retained[0]!=null)Files.deleteIfExists(retained[0]);}else retained[0]=null;return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage tabular import");}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to build tabular import plan");}
        finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }

    /** Form input resolves a stored Pattern and becomes the same immutable analyze/apply plan. */
    private Map<String,Object> dataByPattern(Map<String,Object> request){
        fields(request,"pattern","values","rules");Map<String,Object> rules=request.get("rules")==null?Map.of():ValueCodec.map(request.get("rules"));validateRules(rules,true);
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.dataByPattern",request,false,"editor",()->{
                Object selector=request.get("pattern");Node pattern;
                if(selector instanceof Map<?,?>){var reference=ValueCodec.map(selector);fields(reference,"id");pattern=repo.entity("Pattern",c.namespaceId,RequestContext.uuid(reference.get("id")));}
                else pattern=repo.find("Pattern",Map.of("namespaceId",c.namespaceId,"name",BaseService.name(selector)));
                if(pattern==null)c.fail("NOT_FOUND","Pattern not found");
                ImportFormExpansion.FormSnapshot form;try{form=new ImportFormExpansion(c,repo,resources).expand(pattern,ValueCodec.map(request.get("values")),rules,this::stageFormResource);}catch(IOException failure){throw new UncheckedIOException(failure);}
                Path staged=form.path();retained[0]=staged;Map<String,Object> effective=form.rules();
                Map<String,Object> summary=withValidatedPackage(staged,pkg->analyzeSnapshot(pkg,effective));
                    String id=UUID.randomUUID().toString();long now=resources.now();String relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long targetRevision=repo.sequence(c.namespaceId);String rulesJson=Json.write(effective),summaryJson=Json.write(summary);Node plan=initializePlan(id,"pattern-form",relative,targetRevision,now,now+ResourceFiles.LEASE,rulesJson,summaryJson);retainStagingUntilCommit(staged);return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"))),false);
            });
            if(c.replayed){if(retained[0]!=null)Files.deleteIfExists(retained[0]);}else retained[0]=null;return outcome.data();
        }catch(IOException|UncheckedIOException error){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage Pattern form");}finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }
    private ImportFormExpansion.FormResource stageFormResource(String resourceId)throws IOException{
        resources.lock(resourceId);Node resource=resources.resource(c.namespaceId,resourceId);if(resource==null)c.fail("NOT_FOUND","Form resource not found");if(!resources.accessible(resource,c.actorId,effectiveRole(c.namespaceId),false))c.fail("FORBIDDEN","Form resource access required");var content=resources.verify(resources.location(resourceId));Path copy=stagedPath("staging/imports/form-content-"+UUID.randomUUID());try(InputStream input=resources.storage.open(content,0,content.bytes())){Files.copy(input,copy);}catch(IOException|RuntimeException failure){try{Files.deleteIfExists(copy);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}return new ImportFormExpansion.FormResource(copy,resourceId,(String)resource.getProperty("filename"),(String)resource.getProperty("mediaType"),content.bytes(),content.checksum());
    }

    private Map<String,Object> analyze(Map<String,Object> request){
        fields(request,"source","rules");Map<String,Object> rules=request.get("rules") instanceof Map<?,?>?ValueCodec.map(request.get("rules")):Map.of();
        validateRules(rules);c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};
        try{
            Outcome outcome=action("deposit.import.analyze",request,false,"editor",()->{
                Map<String,Object> src=ValueCodec.map(request.get("source"));fields(src,"packageResourceId");String packageId=RequestContext.uuid(src.get("packageResourceId"));
                Path staged=copyAuthorizedPackage(packageId);retained[0]=staged;
                Map<String,Object> summary=withValidatedPackage(staged,pkg->{if(!"namespace".equals(pkg.manifest().get("scope")))throw ExceptionHandler.fail("VALIDATION_FAILED","Import source must be a complete validated snapshot");return analyzeSnapshot(pkg,rules);});
                String id=UUID.randomUUID().toString(),relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now();long targetRevision=repo.sequence(c.namespaceId);String rulesJson=Json.write(rules),summaryJson=Json.write(summary);
                Node plan=initializePlan(id,packageId,relative,targetRevision,now,now+24L*60*60*1000,rulesJson,summaryJson);retainStagingUntilCommit(staged);
                return new Change(new Outcome(null,null,Json.map("planId",id,"targetRevision",plan.getProperty("targetRevision"),"groups",summary.get("groups"),"summary",summary.get("summary"))),false);
            });
            retained[0]=null;return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage or validate import package");}
        finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}}
    }

    private Map<String,Object> getPlan(Map<String,Object> request){
        fields(request,"planId");scoped("viewer");String id=RequestContext.uuid(request.get("planId"));repo.lockNamespace(c.namespaceId);Node plan=repo.find("DepositImportPlan","id",id);if(plan!=null&&!c.namespaceId.equals(plan.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Import plan belongs to another Namespace");if(plan==null||number(plan,"expiresAt",0)<=resources.now())c.fail("NOT_FOUND","Import plan is unavailable");if(!c.actorId.equals(plan.getProperty("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("NOT_FOUND","Import plan is unavailable");plan.setProperty("lastActivity",resources.now());plan.setProperty("expiresAt",resources.now()+24L*60*60*1000);return planData(plan);
    }

    private Map<String,Object> apply(Map<String,Object> request){
        fields(request,"planId");String id=RequestContext.uuid(request.get("planId"));
        // The staged plan owns its independent target revision; session refresh cannot replace it.
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);c.historyKind="import";
        return action("deposit.import.apply",request,false,"editor",()->{
            repo.lockNamespace(c.namespaceId);Node plan=repo.find("DepositImportPlan","id",id);if(plan!=null&&!c.namespaceId.equals(plan.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Import plan belongs to another Namespace");
            if(plan==null||number(plan,"expiresAt",0)<=resources.now()||!"ready".equals(plan.getProperty("state")))c.fail("NOT_FOUND","Unapplied import plan is unavailable");
            if(!c.actorId.equals(plan.getProperty("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("FORBIDDEN","Import plan belongs to another actor");
            if(repo.sequence(c.namespaceId)!=number(plan,"targetRevision",-1))c.fail("PLAN_STALE","Receiving Namespace changed after import analysis");
            Path path=stagedPath((String)plan.getProperty("stagingPath"));Map<String,Object> rules=Json.read((String)plan.getProperty("rules"));String savedSummary=(String)plan.getProperty("summary");
            Map<String,Object> result=withValidatedPackage(path,pkg->{
                try(var indexes=scratch();var candidates=new DiskJsonList(indexes.directory,"candidates-");var conflictRows=new DiskJsonList(indexes.directory,"conflicts-")){
                Map<String,Object> resolved=ImportPlanSummary.applicationSummary(savedSummary,candidates,conflictRows,indexes::check);
                List<?> conflicts=(List<?>)resolved.get("conflicts");if(!conflicts.isEmpty())c.fail("IMPORT_CONFLICT","Import has unresolved analysis conflicts");
                Map<String,Object> applied=applySnapshot(pkg,rules,resolved,path);
                if(plan.hasProperty("rowIdsJson")){Map<String,Object> objectIds=ValueCodec.map(ValueCodec.map(applied.get("identityMappings")).get("Object"));var rowMappings=new LinkedHashMap<String,Object>();jsonEntries((String)plan.getProperty("rowIdsJson"),(internal,temp)->{c.state.kernel.assertOpen();rowMappings.put((String)temp,objectIds.get(internal));});applied.put("rowMappings",rowMappings);}
                return applied;
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            plan.setProperty("state","applied");plan.setProperty("lastActivity",resources.now());plan.setProperty("expiresAt",resources.now()+24L*60*60*1000);
            return new Change(new Outcome(null,null,result),true);
        }).data();
    }

    private Map<String,Object> cloneNamespace(Map<String,Object> request){
        fields(request,"packageResourceId");String id=RequestContext.uuid(request.get("packageResourceId"));
        return action("deposit.import.namespaceData",request,true,"viewer",()->{
            Path path=copyAuthorizedPackage(id);try{
                ExchangePackageCodec.PackageData pkg=validate(path);Map<String,Object> manifest=pkg.manifest();if(!"namespace".equals(manifest.get("scope")))c.fail("VALIDATION_FAILED","Namespace cloning requires a full-Namespace package");
                if(repo.find("Namespace","name",manifest.get("name"))!=null)c.fail("DUPLICATE","Namespace name already exists");
                Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",manifest.get("name"),"description",manifest.get("description"),"depositVersion",DepositInfo.version(),"historyMode",manifest.get("historyMode"),"sequence",0L));String nsId=CoreRepository.id(ns);repo.setRole(c.actorId,nsId,"owner");
                Map<String,Object> result=new ImportSnapshotMaterializer(c,repo,resources).materialize(pkg,path,nsId,Map.of(),Map.of());
                if("persistent".equals(manifest.get("historyMode"))){var resourceMap=new HashMap<String,String>();ValueCodec.map(result.get("resourceMapping")).forEach((source,target)->{if(target instanceof String mappedId)resourceMap.put(source,mappedId);});new HistoryCloneService(c.tx,repo,nsId,c.actorId,c.server.runId,ValueCodec.map(result.get("identityMappings")),resourceMap).importHistory(pkg.history());ns.setProperty("sequence",((Number)manifest.get("exportedRevision")).longValue());}
                else if("transient".equals(manifest.get("historyMode")))new HistoryRepository(c.tx,repo).baseline(nsId,c.actorId,0,c.server.runId,c.timestamp);
                result.put("namespaceId",nsId);result.put("name",manifest.get("name"));return new Change(new Outcome(ns,null,result),true);
            }finally{try{Files.deleteIfExists(path);}catch(IOException ignored){}}
        }).data();
    }

    private Map<String,Object> analyzeSnapshot(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules){
        return new ImportSnapshotAnalysis(c,repo,resources,stagedPath("staging/imports")).analyze(pkg,rules);
    }

    /** Write only the fields common to every durable import-plan source. */
    private Node initializePlan(String id,String sourcePackageId,String stagingPath,long targetRevision,long now,long expiresAt,String rules,String summary){
        Node plan=c.tx.createNode(Label.label("DepositImportPlan"));plan.setProperty("id",id);plan.setProperty("namespaceId",c.namespaceId);plan.setProperty("actorId",c.actorId);plan.setProperty("sourcePackageId",sourcePackageId);plan.setProperty("stagingPath",stagingPath);plan.setProperty("targetRevision",targetRevision);plan.setProperty("createdAt",now);plan.setProperty("lastActivity",now);plan.setProperty("expiresAt",expiresAt);plan.setProperty("state","ready");plan.setProperty("rules",rules);plan.setProperty("summary",summary);return plan;
    }

    /** Newly-created staging belongs to this transaction until its commit. */
    private void retainStagingUntilCommit(Path staged){
        boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->{if(!committed[0])deleteStaging(staged);});
    }
    private static void deleteStaging(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}

    private Map<String,Object> applySnapshot(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules,Map<String,Object> summary,Path path){
        Map<String,Object> mappings=ValueCodec.map(summary.get("objectMappings"));
        return new ImportSnapshotMaterializer(c,repo,resources).apply(pkg,path,c.namespaceId,ValueCodec.map(summary.get("classMap")),mappings,rules);

    }

    private Map<String,Object> planData(Node plan){Map<String,Object> summary=Json.read((String)plan.getProperty("summary"));var data=Json.map("planId",CoreRepository.id(plan),"state",plan.getProperty("state"),"targetRevision",plan.getProperty("targetRevision"),"expiresAt",plan.getProperty("expiresAt"),"groups",summary.get("groups"),"summary",summary.get("summary"));if(summary.containsKey("rowMappings"))data.put("rowMappings",summary.get("rowMappings"));return data;}
    private Path copyAuthorizedPackage(String packageId){
        Path path=null;try{Node location=resources.location(packageId);if(location==null)c.fail("NOT_FOUND","Package resource not found");String sourceNs=(String)location.getProperty("namespaceId",null);Node resource=sourceNs==null?null:resources.resource(sourceNs,packageId);if(resource==null||!"ready".equals(resource.getProperty("state",null)))c.fail("NOT_FOUND","Package resource not found");resources.lock(packageId);String role=repo.role(c.actorId,sourceNs);if(!resources.accessible(resource,c.actorId,role,false))c.fail("NOT_FOUND","Package resource not found");ResourceStorage.Content content=resources.verify(location);path=resources.files.path("staging/imports/"+UUID.randomUUID()+".zip");Files.createDirectories(path.getParent());try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}return path;}catch(IOException|RuntimeException failure){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(failure instanceof IOException io)throw new UncheckedIOException(io);throw (RuntimeException)failure;}
    }
    private ExchangePackageCodec.PackageData validate(Path path){try{return ExchangePackageCodec.validate(path);}catch(IOException e){throw new UncheckedIOException(e);}}
    private <T> T withValidatedPackage(Path path,java.util.function.Function<ExchangePackageCodec.PackageData,T> action){try{return ExchangePackageCodec.withValidatedPackage(path,()->c.state.kernel.assertOpen(),action);}catch(IOException e){throw new UncheckedIOException(e);}}
    private static String encode(Object value){return ImportSemantics.encode(value);}
    private static void jsonEntries(String source,BiConsumer<String,Object> consumer){
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
        try(var parser=mapper.createParser(source)){if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected object");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){String key=parser.currentName();parser.nextToken();consumer.accept(key,mapper.readValue(parser,Object.class));}}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private Path stagedPath(String relative){try{return resources.files.path(relative);}catch(IOException e){throw new UncheckedIOException(e);}}
    private ImportScratch scratch()throws IOException{return new ImportScratch(stagedPath("staging/imports"),()->c.state.kernel.assertOpen());}
    /** Blank and public row IDs are internal immutable matching metadata. */
    private Map<String,Object> tabularRules(Map<String,Object> rules,Map<String,Object> rowIds,Map<String,Object> blanks,Map<String,Object> clears){
        var effective=new LinkedHashMap<>(rules);effective.put("_tabularBlanks",blanks);effective.put("_tabularIds",rowIds);return effective;
    }
    private void validateRules(Map<String,Object> rules){validateRules(rules,false);}
    private void validateRules(Map<String,Object> rules,boolean form){
        if(form)fields(rules,"alwaysCreate","matchEmpty","rootId");else fields(rules,"alwaysCreate","matchEmpty");
        if(rules.containsKey("alwaysCreate")){if(!(rules.get("alwaysCreate") instanceof List<?>))c.fail("VALIDATION_FAILED","alwaysCreate must be a list");var seen=new HashSet<String>();for(Object raw:(List<?>)rules.get("alwaysCreate"))if(!seen.add(BaseService.name(raw)))c.fail("VALIDATION_FAILED","alwaysCreate requires unique Class names");}
        if(rules.containsKey("matchEmpty")&&!(rules.get("matchEmpty") instanceof Boolean))c.fail("VALIDATION_FAILED","matchEmpty must be Boolean");
        if(rules.containsKey("rootId"))RequestContext.uuid(rules.get("rootId"));
    }
    private long number(Node node,String key,long fallback){return ((Number)node.getProperty(key,fallback)).longValue();}
}
