package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import java.util.*;
import java.util.stream.Stream;

/** Thin public dispatch; domain validation and persistence remain in existing services/repositories. */
public final class ProcedureService {
    private final RequestContext c;
    public ProcedureService(RequestContext c){this.c=c;}
    public Stream<?> execute(){
        String p=c.procedure;Map<String,Object> r=c.request;
        if(DomainService.COMMANDS.contains(p))return outcome(new DomainService(c).execute(p,r),p);
        var account=new AccountService(c);var history=new HistoryService(c);var resource=new ResourceService(c);
        return switch(p){
            case "deposit.utils.bootstrap"->Stream.of(account.bootstrap(r));
            case "deposit.utils.login"->Stream.of(account.login(r));
            case "deposit.utils.logout"->Stream.of(account.logout(r));
            case "deposit.create.user"->Stream.of(account.createUser(r).structured(c));
            case "deposit.create.namespace"->Stream.of(account.createNamespace(r).entity(c));
            case "deposit.set.user"->Stream.of(account.setUser(r).structured(c));
            case "deposit.set.password"->Stream.of(account.setPassword(r));
            case "deposit.set.namespaceRole"->Stream.of(account.setRole(r).structured(c));
            case "deposit.delete.namespace"->Stream.of(account.deleteNamespace(r).structured(c));
            case "deposit.set.activeNamespace","deposit.get.activeNamespace"->Stream.of(account.active(r,p.startsWith("deposit.set.")));
            case "deposit.set.administrativeMode","deposit.get.administrativeMode"->Stream.of(account.administrative(r,p.startsWith("deposit.set.")));
            case "deposit.get.operation"->Stream.of(account.operation(r));
            case "deposit.apply.batch"->Stream.of(new BatchService(c).apply(r).structured(c));
            case "deposit.get.revision"->Stream.of(history.revision(r));
            case "deposit.get.history"->history.records(r);
            case "deposit.get.historyPage"->Stream.of(history.page(r));
            case "deposit.get.restorePreview"->Stream.of(history.restorePreview(r));
            case "deposit.utils.undo","deposit.utils.redo","deposit.utils.restore"->Stream.of(history.reverse(p.substring(p.lastIndexOf('.')+1),r).structured(c));
            case "deposit.utils.canUndo"->Stream.of(history.canUndo(r));
            case "deposit.utils.isRestored"->Stream.of(history.restored(r));
            case "deposit.set.versioningType"->Stream.of(history.mode(r).structured(c));
            case "deposit.set.versionTag"->Stream.of(history.tag(r).structured(c));
            case "deposit.get.query"->new GetQueryService(c).getQuery(r);
            case "deposit.get.queryPage"->Stream.of(new GetQueryService(c).getQueryPage(r));
            case "deposit.get.objectsByPattern"->new PatternService(c).search(r,false);
            case "deposit.get.objectsByPatternPage"->new PatternService(c).search(r,true);
            case "deposit.get.data","deposit.get.namespaceData","deposit.save.data","deposit.load.data"->Stream.of(new DataRow(new ExchangeService(c).execute(p,r),c.meta()));
            case "deposit.resource.beginUpload","deposit.resource.uploadChunk","deposit.resource.finishUpload","deposit.resource.abortUpload"->Stream.of(new DataRow(resource.transfer(p.substring(p.lastIndexOf('.')+1),r),c.meta()));
            case "deposit.resource.info","deposit.resource.downloadChunk"->Stream.of(new DataRow(resource.read(r,p.endsWith("downloadChunk")),c.meta()));
            case "deposit.admin.pruneResources"->Stream.of(new DataRow(resource.prune(r),c.meta()));
            case "deposit.admin.pruneResourcesStatus"->Stream.of(new DataRow(resource.status(r),c.meta()));
            case "deposit.utils.coordinatesToWKT","deposit.utils.wktToCoordinates"->Stream.of(new DataRow(new GeometryService(c).convert(r,p.endsWith("coordinatesToWKT")),c.meta()));
            case "deposit.utils.cancelRequest"->Stream.of(new CancellationService(c).cancel(r));
            case "deposit.utils.cancel","deposit.get.requests","deposit.get.session","deposit.get.lastOperation","deposit.utils.refresh","deposit.utils.toData","deposit.action.prepare","deposit.action.status","deposit.action.resume","deposit.action.release"->new SessionActionService(c).control(p,r);
            default->read(p,r);
        };
    }
    private Stream<?> read(String p,Map<String,Object> r){
        if(!p.startsWith("deposit.get."))c.fail("VALIDATION_FAILED","Unsupported public procedure");
        String name=p.substring("deposit.get.".length());boolean page=name.endsWith("Page");if(page)name=name.substring(0,name.length()-4);
        String kind=Character.toUpperCase(name.charAt(0))+name.substring(1);var service=new ReadService(c);
        if(page)return Stream.of(service.page(kind,r));
        if(Set.of("User","NamespaceRoles").contains(kind))return service.records(kind,r);
        if(Set.of("Relationship","ClassRelationship").contains(kind))return service.relationships(kind,r);
        return service.entities(kind,r);
    }
    private Stream<?> outcome(Outcome o,String procedure){
        if(procedure.startsWith("deposit.delete."))return Stream.of(o.structured(c));
        if(procedure.endsWith("relationship")||procedure.endsWith("classRelationship")||procedure.endsWith("membership"))return Stream.of(new RelRow(o.rel(),o.data(),c.meta()));
        return Stream.of(o.entity(c));
    }
}
