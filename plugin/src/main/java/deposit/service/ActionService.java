package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.HistoryWorkspace;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import org.neo4j.graphdb.Node;

public abstract class ActionService extends BaseService {
    protected ActionService(RequestContext c) {super(c);}
    protected record Change(Outcome outcome,boolean changed) {}
    protected Outcome action(String procedure,Map<String,Object> request,boolean serverScope,String role,Supplier<Change> work) {
        if(serverScope) authenticate();else scoped(role);
        mutation();
        // Serialize durable actor/operation scope reservation across Namespaces.
        // Dedicated lock nodes avoid cycles with role edges. Always actor before scope.
        repo.lockActor(c.actorId);
        if(serverScope)c.server.lockServer(c.state);
        if(!serverScope) {repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,role);}
        String scope=serverScope?"server":c.namespaceId;
        String hash=c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request,"expectedRevision",(Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern","deposit.import.apply").contains(procedure)||depositCloneAction(procedure))?null:c.expectedRevision)));
        Node binding=repo.legacyBinding(c);
        if(binding!=null)OperationalGuards.bindingScope(c,scope,binding.getProperty("scope"));
        Node receipt=repo.receipt(c,scope);
        if(receipt!=null) {
            c.replayed=true;
            OperationalGuards.receiptActor(c,receipt.getProperty("actorId"));
            OperationalGuards.receiptHash(c,hash,receipt.getProperty("requestHash"));
            c.revision=serverScope?c.server.revision(c.state):((Number)receipt.getProperty("revision")).longValue();
            return repo.replay(receipt,scope);
        }
        c.revision=serverScope?c.server.revision(c.state):repo.sequence(scope);if(c.expectedRevision!=null)checkRevision();
        boolean importApply=procedure.equals("deposit.import.apply");
        boolean importAnalysis=Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern").contains(procedure);
        if(importApply)ImportWriteBudget.forRequest(c);
        boolean scientific=!serverScope&&(DomainService.COMMANDS.contains(procedure)||procedure.equals("deposit.apply.batch")||procedure.equals("deposit.import.apply")||Set.of("deposit.utils.undo","deposit.utils.redo","deposit.utils.restore").contains(procedure));
        var history=new deposit.repository.HistoryRepository(c.tx,repo);
        HistoryWorkspace workspace=importApply?historyWorkspace():null;
        // Analysis, row paging and pattern samples are observations. They must not
        // capture the receiving Namespace or invoke scientific Pattern maintenance.
        Map<String,Map<String,Object>> beforeState=serverScope||importAnalysis?Map.of():capture(history,scope,workspace);
        if(scientific&&!"disabled".equals(repo.namespace(scope).getProperty("historyMode")))c.journal=workspace==null?new ChangeSet(beforeState):workspace.journal(beforeState);
        Change change=work.get();
        if(!serverScope&&!importAnalysis){var patterns=new PatternService(c);boolean canonicalized=patterns.canonicalizeResolvedDeclarations();Map<String,Map<String,Object>> resulting=capture(history,scope,workspace);patterns.validateNamespace();patterns.validateDeclarationDeletion(beforeState,resulting);if(canonicalized&&!change.changed())change=new Change(change.outcome(),true);}
        if(change.changed()) {
            if(serverScope) {
                if(!c.state.operationClaimed||!depositCloneAction(procedure)||procedure.equals("deposit.import.cloneNamespace"))c.state.serverChanged=true;
                c.revision=c.server.revision(c.state);
            }
            else {repo.advanceNamespace(scope,c.actorId);c.revision=repo.sequence(scope);}
            if(c.journal!=null){c.journal.checkpoint(capture(history,scope,workspace));String revisionId=importApply?history.appendId(scope,c.actorId,c.operationId,c.revision,c.historyKind,procedure,c.journal,c.historyLink,c.historyTarget,c.timestamp,ImportWriteBudget.forRequest(c)::reserveHistoryRecord):history.append(scope,c.actorId,c.operationId,c.revision,c.historyKind,procedure,c.journal,c.historyLink,c.historyTarget,c.timestamp).id();if(!isImportResult(change.outcome().data()))change.outcome().data().put("revisionId",revisionId);}
        } else if(!isImportResult(change.outcome().data()))change.outcome().data().put("changed",false);
        if(scientific){c.server.storagePoint("history-before-receipt");c.state.afterCommit.add(()->c.server.storagePoint("history-after-commit"));}
        boolean importResult=isImportResult(change.outcome().data());
        if(importResult)new ImportResultRepository(c).publish(change.outcome().data());
        repo.saveReceipt(c,scope,hash,change.outcome());
        if(importResult&&c.state.importWriteBudget!=null)c.state.importWriteBudget.finish();
        return change.outcome();
    }
    private Map<String,Map<String,Object>> capture(deposit.repository.HistoryRepository history,String scope,HistoryWorkspace workspace){return workspace==null?history.capture(scope):history.capture(scope,workspace.newState());}
    private HistoryWorkspace historyWorkspace(){
        var budget=ImportWriteBudget.forRequest(c);var lease=budget.scratchLease();boolean[] cleaned={false};
        try{var workspace=new HistoryWorkspace(c.server.storage.files.path("staging/imports"),()->c.state.kernel.assertOpen(),lease::reserve,()->{lease.close();cleaned[0]=true;},budget::ownHistoryWorkspace);c.state.release.add(()->{try{workspace.close();}catch(IOException failure){if(!cleaned[0])budget.retainForScratchCleanup();c.server.storage.diagnostic="Private history index cleanup pending";}});return workspace;}
        catch(IOException failure){if(!cleaned[0])budget.retainForScratchCleanup();throw new UncheckedIOException(failure);}
        catch(RuntimeException failure){if(!cleaned[0])budget.retainForScratchCleanup();throw failure;}
    }
    private static boolean depositCloneAction(String procedure) {
        return Set.of("deposit.import.cloneNamespace","deposit.import.beginCloneUpload","deposit.import.cloneUploadChunk","deposit.import.finishCloneUpload","deposit.import.abortCloneUpload").contains(procedure);
    }
    private static boolean isImportResult(Map<String,Object> data){return ImportResultContract.FORMAT.equals(data.get("format"));}
    protected Outcome outcome(Node node) {return new Outcome(node,null,repo.data(node));}
}
