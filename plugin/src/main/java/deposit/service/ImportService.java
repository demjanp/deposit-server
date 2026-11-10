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
    private final ImportResultRepository importResults;
    public ImportService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);importResults=new ImportResultRepository(c);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.import.analyze"->analyze(request);
            case "deposit.get.importPlan"->getPlan(request);
            case "deposit.get.importResultV1"->importResults.envelope(RequestContext.uuid(request.get("resultId")));
            case "deposit.get.importResultPageV1"->importResults.page(request);
            case "deposit.get.importResultEntryV1"->importResults.entry(request);
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
        c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};final StorageAdmission.Reservation[] retainedAdmission={null};
        try{
            Outcome outcome=action("deposit.import.rows",request,false,"editor",()->{
                String tableId=null;Iterable<Map<String,Object>> rows;List<Map<String,Object>> columns=null;ImportRowExpansion.RowSource tableRows=null;
                if(source.containsKey("rows")){Object raw=source.get("rows");if(!(raw instanceof List<?> list))c.fail("VALIDATION_FAILED","rows must be a list");rows=expander.inlineRows((List<?>)raw);}
                else {tableId=RequestContext.uuid(source.get("tableId"));Node table=repo.find("DepositTabularTable","id",tableId);if(table!=null&&!c.namespaceId.equals(table.getProperty("namespaceId")))c.fail("SCOPE_MISMATCH","Table belongs to another Namespace");if(table==null||number(table,"expiresAt",0)<=resources.now()||(!c.actorId.equals(table.getProperty("creatorId"))&& !"owner".equals(effectiveRole(c.namespaceId))))c.fail("NOT_FOUND","Table is unavailable");if(!Boolean.TRUE.equals(table.getProperty("valid")))c.fail("VALIDATION_FAILED","Invalid staged table cannot be imported");table.setProperty("lastAccessAt",resources.now());table.setProperty("expiresAt",resources.now()+ResourceFiles.LEASE);columns=(List<Map<String,Object>>)(List<?>)Json.readList((String)table.getProperty("columnsJson"));try{tableRows=expander.newRowSource(resources.files.path((String)table.getProperty("path")));}catch(IOException e){throw new UncheckedIOException(e);}rows=tableRows;}
                try {
                    try(ImportRowExpansion.TabularExpansion expansion=expander.tabularPackage(rows,columns,targets,relationships,rules)){
                        Map<String,Object> expanded=expansion.data();Map<String,Object> manifest=ValueCodec.map(expanded.get("manifest")),graph=ValueCodec.map(expanded.get("graph"));Map<String,Object> rowIds=ValueCodec.map(expanded.get("rowIds")),clears=ValueCodec.map(expanded.get("clears"));Map<String,Object> effectiveRules=tabularRules(rules,rowIds,ValueCodec.map(expanded.get("blanks")),clears);
                        String id=UUID.randomUUID().toString();Path staged=stagedPath("staging/imports/rows-"+id+".zip");retained[0]=staged;long expected=estimatePackageBytes(manifest,graph);StorageAdmission.Reservation admission=c.server.storage.admission.acquire("import-plan",id,c.operationId,"tabular-import-plan",Map.of(resources.files.root,expected),Json.map("phase","package-create","stagingPath",c.server.storage.files.root.relativize(staged).toString().replace('\\','/'),"expectedBytes",expected));retainedAdmission[0]=admission;
                        ImportRowExpansion.writeTabularPackage(staged,manifest,graph);long actual=Files.size(staged);if(actual>expected)throw new IOException("Tabular import package exceeded its prepared storage estimate");c.server.storage.admission.materialized(admission,resources.files.root,actual);c.server.storage.admission.release(admission,Map.of(resources.files.root,expected-actual));Map<String,Object> summary=withValidatedPackage(staged,pkg->analyzeSnapshot(pkg,effectiveRules));
                        var advertised=new LinkedHashMap<String,Object>();for(var entry:rowIds.entrySet()){Object target=ValueCodec.map(summary.get("objectMappings")).get(entry.getKey());advertised.put((String)entry.getValue(),target);}
                        summary.put("rowMappings",advertised);summary.put("_rowIds",rowIds);String relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now(),expires=now+ResourceFiles.LEASE;long targetRevision=repo.sequence(c.namespaceId);String rulesJson=Json.write(effectiveRules);Map<String,Object> result=importResults.save(c.operationId,"analysis",id,targetRevision,expires,summary);Node plan=initializePlan(id,tableId==null?"tabular-inline":tableId,relative,targetRevision,now,expires,rulesJson,"{}");plan.setProperty("resultId",result.get("resultId"));plan.setProperty("admissionId",admission.id);if(tableId!=null)plan.setProperty("tableId",tableId);retainStagingUntilCommit(staged,admission);
                        return new Change(new Outcome(null,null,result),false);
                    }
                } catch(IOException e){throw new UncheckedIOException(e);} finally {if(tableRows!=null)try{tableRows.close();}catch(IOException e){throw new UncheckedIOException(e);}}
            });
            if(c.replayed){if(retained[0]!=null)Files.deleteIfExists(retained[0]);if(retainedAdmission[0]!=null)closeAdmission(retainedAdmission[0]);}else{retained[0]=null;retainedAdmission[0]=null;}return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage tabular import");}
        catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to build tabular import plan");}
        finally{if(retained[0]!=null)try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}if(retainedAdmission[0]!=null)closeAdmission(retainedAdmission[0]);}
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
        validateRules(rules);c.operational=false;c.expectedRevision=repo.sequence(c.namespaceId);final Path[] retained={null};final StorageAdmission.Reservation[] retainedAdmission={null};
        try{
            Outcome outcome=action("deposit.import.analyze",request,false,"editor",()->{
                Map<String,Object> src=ValueCodec.map(request.get("source"));fields(src,"packageResourceId");String packageId=RequestContext.uuid(src.get("packageResourceId"));
                String id=UUID.randomUUID().toString();AuthorizedPackage copied=copyAuthorizedPackage(packageId,"import-plan",id);Path staged=copied.path();retained[0]=staged;retainedAdmission[0]=copied.reservation();
                Map<String,Object> summary=withValidatedPackage(staged,pkg->{Object scope=pkg.manifest().get("scope");if(!"namespace".equals(scope)&&!"subgraph".equals(scope))throw ExceptionHandler.fail("VALIDATION_FAILED","Import source must be a complete Namespace or subgraph snapshot");return analyzeSnapshot(pkg,rules);});
                String relative=c.server.storage.files.root.relativize(staged).toString().replace('\\','/');long now=resources.now();long targetRevision=repo.sequence(c.namespaceId),expires=now+24L*60*60*1000;String rulesJson=Json.write(rules);
                Map<String,Object> result=importResults.save(c.operationId,"analysis",id,targetRevision,expires,summary);Node plan=initializePlan(id,packageId,relative,targetRevision,now,expires,rulesJson,"{}");plan.setProperty("resultId",result.get("resultId"));plan.setProperty("admissionId",copied.reservation().id);retainStagingUntilCommit(staged,copied.reservation());
                return new Change(new Outcome(null,null,result),false);
            });
            retained[0]=null;retainedAdmission[0]=null;return outcome.data();
        }catch(UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage or validate import package");}
        finally{if(retained[0]!=null){try{Files.deleteIfExists(retained[0]);}catch(IOException ignored){}if(retainedAdmission[0]!=null)closeAdmission(retainedAdmission[0]);}}
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
                Map<String,Object> resolved=plan.hasProperty("resultId")?Json.map("classMap",importResults.mappingView((String)plan.getProperty("resultId"),"classMap"),"objectMappings",importResults.mappingView((String)plan.getProperty("resultId"),"objectMappings"),"conflicts",List.of()):ImportPlanSummary.applicationSummary(savedSummary,candidates,conflictRows,indexes::check);
                List<?> conflicts=(List<?>)resolved.get("conflicts");if(!conflicts.isEmpty())c.fail("IMPORT_CONFLICT","Import has unresolved analysis conflicts");
                Map<String,Object> applied=applySnapshot(pkg,rules,resolved,path);
                if(plan.hasProperty("resultId")){Map<String,Object> rowIds=importResults.mappingView((String)plan.getProperty("resultId"),"_rowIds");if(!rowIds.isEmpty()){Map<String,Object> objectIds=ValueCodec.map(ValueCodec.map(applied.get("identityMappings")).get("Object"));var rowMappings=new LinkedHashMap<String,Object>();for(var entry:rowIds.entrySet()){c.state.kernel.assertOpen();rowMappings.put((String)entry.getValue(),objectIds.get(entry.getKey()));}applied.put("rowMappings",rowMappings);}}else if(plan.hasProperty("rowIdsJson")){Map<String,Object> objectIds=ValueCodec.map(ValueCodec.map(applied.get("identityMappings")).get("Object"));var rowMappings=new LinkedHashMap<String,Object>();jsonEntries((String)plan.getProperty("rowIdsJson"),(internal,temp)->{c.state.kernel.assertOpen();rowMappings.put((String)temp,objectIds.get(internal));});applied.put("rowMappings",rowMappings);}
                return applied;
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            long expiry=resources.now()+30L*24*60*60*1000;Map<String,Object> applied=importResults.save(c.operationId,"apply",id,Math.addExact(c.revision,1),expiry,result);result=applied;
            plan.setProperty("state","applied");plan.setProperty("lastActivity",resources.now());plan.setProperty("expiresAt",expiry);
            releasePlanStagingAfterCommit(plan,path);
            return new Change(new Outcome(null,null,result),true);
        }).data();
    }

    private Map<String,Object> cloneNamespace(Map<String,Object> request){
        fields(request,"packageResourceId");String id=RequestContext.uuid(request.get("packageResourceId"));
        return action("deposit.import.namespaceData",request,true,"viewer",()->{
            AuthorizedPackage copied=copyAuthorizedPackage(id,"namespace-clone",UUID.randomUUID().toString());Path path=copied.path();try{
                ExchangePackageCodec.PackageData pkg=validate(path);Map<String,Object> manifest=pkg.manifest();if(!"namespace".equals(manifest.get("scope")))c.fail("VALIDATION_FAILED","Namespace cloning requires a full-Namespace package");
                if(repo.find("Namespace","name",manifest.get("name"))!=null)c.fail("DUPLICATE","Namespace name already exists");
                Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",manifest.get("name"),"description",manifest.get("description"),"depositVersion",DepositInfo.version(),"historyMode",manifest.get("historyMode"),"sequence",0L));String nsId=CoreRepository.id(ns);repo.setRole(c.actorId,nsId,"owner");
                Map<String,Object> result=new ImportSnapshotMaterializer(c,repo,resources).materialize(pkg,path,nsId,Map.of(),Map.of());
                if("persistent".equals(manifest.get("historyMode"))){var resourceMap=new HashMap<String,String>();ValueCodec.map(result.get("resourceMapping")).forEach((source,target)->{if(target instanceof String mappedId)resourceMap.put(source,mappedId);});new HistoryCloneService(c.tx,repo,nsId,c.actorId,c.server.runId,ValueCodec.map(result.get("identityMappings")),resourceMap).importHistory(pkg.history());ns.setProperty("sequence",((Number)manifest.get("exportedRevision")).longValue());}
                else if("transient".equals(manifest.get("historyMode")))new HistoryRepository(c.tx,repo).baseline(nsId,c.actorId,0,c.server.runId,c.timestamp);
                result.put("namespaceId",nsId);result.put("name",manifest.get("name"));registerTemporaryPackageCleanup(path,copied.reservation());return new Change(new Outcome(ns,null,result),true);
            }catch(RuntimeException|Error failure){try{c.server.storage.admission.close(copied.reservation());}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            finally{try{Files.deleteIfExists(path);}catch(IOException ignored){}}
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
    private void retainStagingUntilCommit(Path staged,StorageAdmission.Reservation reservation){
        boolean[] committed={false};c.state.afterCommit.add(()->committed[0]=true);c.state.release.add(()->{if(!committed[0]){deleteStaging(staged);closeAdmission(reservation);}});
    }
    private void releasePlanStagingAfterCommit(Node plan,Path staged){
        String admissionId=(String)plan.getProperty("admissionId",null);if(admissionId==null)return;
        StorageAdmission.Reservation reservation;try{reservation=c.server.storage.admission.resume(admissionId);}catch(IOException failure){throw new UncheckedIOException(failure);}
        boolean[] committed={false};c.state.afterCommit.add(()->{committed[0]=true;deleteStaging(staged);closeAdmission(reservation);});
        c.state.release.add(()->{if(!committed[0])c.server.storage.diagnostic="Applied import staging cleanup pending";});
        plan.removeProperty("admissionId");
    }
    private void registerTemporaryPackageCleanup(Path path,StorageAdmission.Reservation reservation){
        boolean[] committed={false};c.state.afterCommit.add(()->{committed[0]=true;deleteStaging(path);closeAdmission(reservation);});
        c.state.release.add(()->{if(!committed[0]){deleteStaging(path);closeAdmission(reservation);}});
    }
    private void closeAdmission(StorageAdmission.Reservation reservation){try{c.server.storage.admission.close(reservation);}catch(IOException failure){c.server.storage.diagnostic="Import storage admission cleanup pending";}}
    private static void deleteStaging(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}

    private Map<String,Object> applySnapshot(ExchangePackageCodec.PackageData pkg,Map<String,Object> rules,Map<String,Object> summary,Path path){
        Map<String,Object> mappings=ValueCodec.map(summary.get("objectMappings"));
        return new ImportSnapshotMaterializer(c,repo,resources).apply(pkg,path,c.namespaceId,ValueCodec.map(summary.get("classMap")),mappings,rules);

    }

    Map<String,Object> planData(Node plan){
        if(plan.hasProperty("resultId")){Map<String,Object> analysis=importResults.envelopeForPlan((String)plan.getProperty("resultId"),CoreRepository.id(plan),(String)plan.getProperty("namespaceId"));return planData(plan,analysis);}
        Map<String,Object> summary=Json.read((String)plan.getProperty("summary"));var data=Json.map("planId",CoreRepository.id(plan),"state",plan.getProperty("state"),"targetRevision",plan.getProperty("targetRevision"),"expiresAt",plan.getProperty("expiresAt"),"groups",summary.get("groups"),"summary",summary.get("summary"));if(summary.containsKey("rowMappings"))data.put("rowMappings",summary.get("rowMappings"));return data;
    }
    static Map<String,Object> planData(Node plan,Map<String,Object> analysis){Map<String,Object> lifecycle=Json.map("planId",CoreRepository.id(plan),"namespaceId",plan.getProperty("namespaceId"),"state",plan.getProperty("state"),"targetRevision",plan.getProperty("targetRevision"),"expiresAt",plan.getProperty("expiresAt"));return Json.map("format","deposit.import-plan/1","plan",lifecycle,"analysisResult",analysis);}
    private AuthorizedPackage copyAuthorizedPackage(String packageId,String ownerKind,String ownerId){
        Path path=null;StorageAdmission.Reservation reservation=null;try{Node location=resources.location(packageId);if(location==null)c.fail("NOT_FOUND","Package resource not found");String sourceNs=(String)location.getProperty("namespaceId",null);Node resource=sourceNs==null?null:resources.resource(sourceNs,packageId);if(resource==null||!"ready".equals(resource.getProperty("state",null)))c.fail("NOT_FOUND","Package resource not found");resources.lock(packageId);String role=repo.role(c.actorId,sourceNs);if(!resources.accessible(resource,c.actorId,role,false))c.fail("NOT_FOUND","Package resource not found");ResourceStorage.Content content=resources.verify(location);path=resources.files.path("staging/imports/"+UUID.randomUUID()+".zip");String receiptScope="namespace-clone".equals(ownerKind)?"server":c.namespaceId;reservation=c.server.storage.admission.acquire(ownerKind,ownerId,c.operationId,"import-package-copy",Map.of(resources.files.root,content.bytes()),Json.map("phase","package-copy","packageId",packageId,"receiptScope",receiptScope,"stagingPath",c.server.storage.files.root.relativize(path).toString().replace('\\','/'),"expectedBytes",content.bytes()));Files.createDirectories(path.getParent());try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}if(Files.size(path)!=content.bytes())throw new IOException("Incomplete package staging copy");c.server.storage.admission.materialized(reservation,resources.files.root,content.bytes());c.server.storage.admission.clearWriteIntent(reservation);return new AuthorizedPackage(path,reservation);}catch(IOException|RuntimeException failure){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(reservation!=null)try{c.server.storage.admission.close(reservation);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(failure instanceof IOException io)throw new UncheckedIOException(io);throw (RuntimeException)failure;}
    }
    private record AuthorizedPackage(Path path,StorageAdmission.Reservation reservation){}
    private ExchangePackageCodec.PackageData validate(Path path){try{return ExchangePackageCodec.validate(path);}catch(IOException e){throw new UncheckedIOException(e);}}
    private <T> T withValidatedPackage(Path path,java.util.function.Function<ExchangePackageCodec.PackageData,T> action){try{return ExchangePackageCodec.withValidatedPackage(path,()->c.state.kernel.assertOpen(),action);}catch(IOException e){throw new UncheckedIOException(e);}}
    private static String encode(Object value){return ImportSemantics.encode(value);}
    private static void jsonEntries(String source,BiConsumer<String,Object> consumer){
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
        try(var parser=mapper.createParser(source)){if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Expected object");while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){String key=parser.currentName();parser.nextToken();consumer.accept(key,mapper.readValue(parser,Object.class));}}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private Path stagedPath(String relative){try{return resources.files.path(relative);}catch(IOException e){throw new UncheckedIOException(e);}}
    private static long estimatePackageBytes(Map<String,Object> manifest,Map<String,Object> graph)throws IOException{
        final long[] length={0};OutputStream counter=new OutputStream(){public void write(int value)throws IOException{length[0]=Math.addExact(length[0],1L);}public void write(byte[] bytes,int offset,int count)throws IOException{length[0]=Math.addExact(length[0],(long)count);}};
        try{Json.write(counter,manifest);Json.write(counter,graph);long estimate=Math.addExact(Math.multiplyExact(length[0],2L),64L*1024);return estimate;}catch(ArithmeticException overflow){throw new IOException("Tabular package estimate is not representable",overflow);}
    }
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
