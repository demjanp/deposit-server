package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import org.neo4j.graphdb.Node;

public abstract class ActionService extends BaseService {
    protected ActionService(RequestContext c) {super(c);}
    protected record Change(Outcome outcome,boolean changed) {}
    protected Outcome action(String procedure,Map<String,Object> request,boolean serverScope,String role,Supplier<Change> work) {
        Node namespace;
        if(serverScope) {authenticate();namespace=null;}else namespace=scope(role);
        mutation();
        // Serialize durable actor/operation scope reservation across Namespaces.
        // Dedicated lock nodes avoid cycles with role edges. Always actor before scope.
        repo.lockActor(c.actorId);
        if(serverScope)c.server.lockServer(c.state);
        if(!serverScope)repo.lockNamespace(c.namespaceId);
        String scope=serverScope?"server":c.namespaceId;
        String hash=c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request,"expectedRevision",(Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern","deposit.import.apply").contains(procedure)||procedure.equals("deposit.import.cloneNamespace"))?null:c.expectedRevision)));
        long lockedRevision=serverScope?c.server.revision(c.state):CoreRepository.sequence(namespace);
        c.revision=lockedRevision;if(c.expectedRevision!=null)checkRevision();
        boolean importApply=procedure.equals("deposit.import.apply");
        if(importApply)ImportWriteBudget.forRequest(c);
        boolean domain=!serverScope&&(DomainService.COMMANDS.contains(procedure)||procedure.equals("deposit.apply.batch"));
        boolean reversal=!serverScope&&Set.of("deposit.utils.undo","deposit.utils.redo","deposit.utils.restore").contains(procedure);
        boolean scientific=domain||importApply||reversal;
        boolean historyEnabled=scientific&&!"disabled".equals(namespace.getProperty("historyMode"));
        var history=new HistoryRepository(c.tx,repo);
        HistoryWorkspace workspace=importApply&&historyEnabled?historyWorkspace():null;
        if(domain||procedure.equals("deposit.set.classDescriptorType")) {
            c.patternChanges=new PatternChanges(repo,scope,()->c.state.kernel.assertOpen());
            if(historyEnabled) {
                c.journal=new ChangeSet();
                c.historyMutation=new HistoryMutation(repo,history,c.patternChanges,scope,()->c.state.kernel.assertOpen(),c.journal);
            }
        } else if(workspace!=null)c.journal=workspace.journal(history.capture(scope,workspace.newState()));
        Change change=work.get();
        Map<String,Map<String,Object>> resulting=null;
        boolean affectedPatterns=c.patternChanges!=null&&c.patternChanges.affected();
        if(affectedPatterns||importApply||reversal) {
            var patterns=new PatternCompatibilityService(c);
            boolean canonicalized=affectedPatterns?patterns.validateAffected(c.patternChanges):patterns.validateAndCanonicalizeNamespace();
            if(canonicalized&&!change.changed())change=new Change(change.outcome(),true);
            if(!domain&&c.journal!=null&&(change.changed()||reversal)) {
                resulting=workspace==null?history.capture(scope):history.capture(scope,workspace.newState());
                if(reversal)patterns.validateDeclarationDeletion(c.journal.before,resulting);
            }
        }
        if(change.changed()) {
            if(serverScope) {
                c.state.serverChanged=true;
                c.revision=c.server.revision(c.state);
            }
            else c.revision=repo.advanceNamespace(namespace,lockedRevision,c.actorId);
            if(c.journal!=null) {
                if(domain)c.historyMutation.checkpoint();
                else {
                    history.refreshNamespaceImage(namespace,resulting);
                    c.journal.checkpoint(resulting);
                }
                String revisionId=history.appendId(scope,c.actorId,c.operationId,c.revision,c.historyKind,procedure,
                    c.journal,c.historyLink,c.historyTarget,c.timestamp,importApply?ImportWriteBudget.forRequest(c)::reserveHistoryRecord:null);
                if(!isImportResult(change.outcome().data()))change.outcome().data().put("revisionId",revisionId);
            }
        } else if(!isImportResult(change.outcome().data()))change.outcome().data().put("changed",false);
        if(scientific){c.server.storagePoint("history-before-receipt");c.state.afterCommit.add(()->c.server.storagePoint("history-after-commit"));}
        boolean importResult=isImportResult(change.outcome().data());
        if(importResult)new ImportResultRepository(c).publish(change.outcome().data());
        repo.saveReceipt(c,scope,hash,change.outcome());
        if(importResult&&c.state.importWriteBudget!=null)c.state.importWriteBudget.finish();
        return change.outcome();
    }
    private HistoryWorkspace historyWorkspace(){
        var budget=ImportWriteBudget.forRequest(c);var lease=budget.scratchLease();boolean[] cleaned={false};
        try{var workspace=new HistoryWorkspace(c.server.storage.files.path("staging/imports"),()->c.state.kernel.assertOpen(),lease::reserve,()->{lease.close();cleaned[0]=true;},budget::ownHistoryWorkspace);c.state.release.add(()->{try{workspace.close();}catch(IOException failure){if(!cleaned[0])budget.retainForScratchCleanup();c.server.storage.diagnostic="Private history index cleanup pending";}});return workspace;}
        catch(IOException failure){if(!cleaned[0])budget.retainForScratchCleanup();throw new UncheckedIOException(failure);}
        catch(RuntimeException failure){if(!cleaned[0])budget.retainForScratchCleanup();throw failure;}
    }
    private static boolean isImportResult(Map<String,Object> data){return ImportResultContract.FORMAT.equals(data.get("format"));}
    protected Outcome outcome(Node node) {return new Outcome(node,null,repo.data(node));}
}
