package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.*;
import java.util.*;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

/** Authorization, action orchestration and shared preview/apply planning. */
public final class HistoryService extends ActionService {
    private final HistoryRepository history;
    private final HistoryReadService readService;
    public HistoryService(RequestContext c){super(c);history=new HistoryRepository(c.tx,repo);exportService=new HistoryExportService(c);readService=new HistoryReadService(c);}
    private final HistoryExportService exportService;
    public Map<String,Object> historyExport(){return exportService.historyExport();}
    public Map<String,Object> historyExport(ExportRecords records){return exportService.historyExport(records);}
    private Node root(){return repo.namespace(c.namespaceId);}
    private void enabled(){if("disabled".equals(root().getProperty("historyMode")))c.fail("HISTORY_UNAVAILABLE","History is disabled");}
    private void read(String role){readContext();scoped(role);}
    private long generation(){return readService.generation();}
    private void stable(long sequence,long generation){readService.stable(sequence,generation);}
    public DataRow revision(Map<String,Object> request){return readService.revision(request);}
    public ValueRow restored(Map<String,Object> request){return readService.restored(request);}
    public Stream<DataRow> records(Map<String,Object> request){return readService.records(request);}
    public DataRow page(Map<String,Object> request){return readService.page(request);}
    private List<Revision> epoch(String id){return history.revisions(c.namespaceId).stream().filter(r->id.equals(r.data().get("historyEpochId"))).toList();}
    private Revision select(String id,boolean redo,boolean inspection){
        enabled();String epochId=(String)root().getProperty("historyEpochId");var revisions=epoch(epochId);Revision selected=null;
        if(id!=null)selected=history.revision(c.namespaceId,id);
        else for(var r:revisions)if(c.actorId.equals(r.data().get("actorId"))&&HistoryPlanner.eligible(r,revisions,false))selected=r;
        if(selected==null)c.fail("NOT_FOUND","No undo candidate");
        if(!epochId.equals(selected.data().get("historyEpochId")))c.fail("HISTORY_UNAVAILABLE","Revision belongs to a closed epoch");
        if(!inspection&&!c.actorId.equals(selected.data().get("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("FORBIDDEN","Only owner may reverse another actor's action");
        if(!HistoryPlanner.eligible(selected,revisions,redo))c.fail("REVISION_CONFLICT","Revision effect is not eligible for reversal");return selected;
    }
    private UndoPlan inverse(String id,boolean redo,boolean inspection){Revision selected=select(id,redo,inspection);var revisions=epoch((String)selected.data().get("historyEpochId"));HistoryPlanner.reconstruct(revisions,selected.id());var plan=HistoryPlanner.inverse(c.revision,history.capture(c.namespaceId),selected,revisions);if(plan.conflicts().isEmpty())history.validateResources(c.namespaceId,plan.result());return plan;}
    public UndoPlan previewUndo(String id){return inverse(id,false,false);}
    public RestorePlan previewRestore(String id){enabled();requireRole(c.namespaceId,"owner");Revision target=history.revision(c.namespaceId,id);var plan=HistoryPlanner.restore(c.revision,history.capture(c.namespaceId),HistoryPlanner.reconstruct(epoch((String)target.data().get("historyEpochId")),id),id);if(plan.plan().conflicts().isEmpty())history.validateResources(c.namespaceId,plan.plan().result());return plan;}
    public DataRow restorePreview(Map<String,Object> request){fields(request,"targetRevisionId");read("owner");String id=RequestContext.uuid(request.get("targetRevisionId"));long generation=generation();var plan=previewRestore(id);stable(c.revision,generation);return new DataRow(plan.data(),c.meta());}
    public EligibilityRow canUndo(Map<String,Object> request){fields(request,"revisionId");read("viewer");String id=request.containsKey("revisionId")?RequestContext.uuid(request.get("revisionId")):null;long sequence=c.revision,generation=generation();
        try{var plan=inverse(id,false,true);stable(sequence,generation);return new EligibilityRow(plan.conflicts().isEmpty(),plan.data(),c.meta());}
        catch(ExceptionHandler.DepositException e){var error=Json.read(e.getMessage().substring("DEPOSIT_ERROR ".length()));String code=(String)error.get("code");if(!Set.of("NOT_FOUND","HISTORY_UNAVAILABLE","REVISION_CONFLICT","RESOURCE_CORRUPT").contains(code))throw e;stable(sequence,generation);return new EligibilityRow(false,Json.map("targetRevisionId",id,"reason",code,"conflicts",List.of(Json.map("reason",code))),c.meta());}
    }
    public Outcome reverse(String kind,Map<String,Object> request){String key=kind.equals("restore")?"targetRevisionId":kind.equals("redo")?"undoRevisionId":"revisionId";fields(request,key);String id=request.containsKey(key)?RequestContext.uuid(request.get(key)):null;if(id==null&&!kind.equals("undo"))c.fail("VALIDATION_FAILED",key+" required");
        return action("deposit.utils."+kind,request,false,kind.equals("restore")?"owner":"editor",()->{
            UndoPlan plan=kind.equals("restore")?previewRestore(id).plan():inverse(id,kind.equals("redo"),false);
            if(!plan.conflicts().isEmpty())throw ExceptionHandler.error("REVISION_CONFLICT","History plan conflicts",c,false,Json.map("conflicts",plan.conflicts()));
            if(kind.equals("restore")&&plan.changes().touches.isEmpty()){
                var data=plan.data();data.put("kind",kind);data.put("changed",false);
                return new Change(new Outcome(null,null,data),false);
            }
            history.apply(c.namespaceId,c.actorId,plan);c.historyKind=kind;c.historyLink=kind.equals("restore")?"restoresRevisionId":kind.equals("redo")?"reappliesRevisionId":"reversesRevisionId";c.historyTarget=plan.targetId();
            if(!kind.equals("restore")){Revision selected=history.revision(c.namespaceId,plan.targetId());selected.touches().forEach((entity,fields)->c.journal.touches.computeIfAbsent(entity,k->new TreeSet<>()).addAll(fields));c.journal.dependencies.addAll(selected.dependencies());}
            var data=plan.data();data.put("kind",kind);data.put("changed",true);return new Change(new Outcome(null,null,data),true);
        });
    }
    public Outcome mode(Map<String,Object> request){fields(request,"mode");String mode=string(request.get("mode"));if(!Set.of("disabled","transient","persistent").contains(mode))c.fail("VALIDATION_FAILED","Invalid history mode");return action("deposit.set.versioningType",request,false,"owner",()->{boolean changed=!mode.equals(root().getProperty("historyMode"));if(changed)history.mode(c.namespaceId,mode,c.actorId,c.revision+1,c.server.runId,c.timestamp);return new Change(new Outcome(null,null,Json.map("historyMode",mode,"historyEpochId",root().getProperty("historyEpochId",null),"revisionId",root().getProperty("headRevisionId",null))),changed);});}
    public Outcome tag(Map<String,Object> request){fields(request,"revisionId","tag");String id=RequestContext.uuid(request.get("revisionId")),tag=string(request.get("tag"));return action("deposit.set.versionTag",request,false,"owner",()->new Change(new Outcome(null,null,Json.map("revisionId",id,"tag",tag)),history.tag(c.namespaceId,id,tag,c.actorId,c.timestamp)));}
}
