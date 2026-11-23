package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Descriptor declaration and Namespace-wide patch commands within a domain action. */
final class DomainSchemaCommands extends BaseService {
    private final DomainService domain;
    DomainSchemaCommands(DomainService domain,RequestContext c) { super(c);this.domain=domain; }

    ActionService.Change declarationPatch(Map<String,Object> request) {
        fields(request,"id","type","conversion","order","comment");
        if(request.containsKey("conversion")&&!request.containsKey("type"))c.fail("VALIDATION_FAILED","conversion requires type");
        boolean changed=false;
        if(request.containsKey("type")) {
            if(!request.containsKey("conversion"))c.fail("VALIDATION_FAILED","Type changes require an explicit conversion");
            changed=domain.convertCommand(Json.map("id",request.get("id"),"type",request.get("type"),"conversion",request.get("conversion"))).changed();
        }
        var properties=new LinkedHashMap<>(request);properties.remove("type");properties.remove("conversion");
        ActionService.Change patch=domain.setCommand("ClassDescriptor",properties);
        return new ActionService.Change(patch.outcome(),changed||patch.changed());
    }
    ActionService.Change namespace(Map<String,Object> request) {
        fields(request,"name","description");Node ns=repo.namespace(c.namespaceId);var updates=new LinkedHashMap<String,Object>();
        if(request.containsKey("name")) {
            String value=name(request.get("name"));Node other=repo.find("Namespace","name",value);
            if(other!=null&&!other.equals(ns))c.fail("DUPLICATE","Namespace name exists");updates.put("name",value);
        }
        if(request.containsKey("description"))updates.put("description",string(request.get("description")));
        boolean changed=repo.update(ns,updates,c.actorId);var result=domain.outcome(ns);result.data().put("role",effectiveRole(c.namespaceId));
        if(changed)result.data().put("sequence",c.revision+1);
        return new ActionService.Change(result,changed);
    }
    ActionService.Change clear(Map<String,Object> request) {
        fields(request,"confirmNamespaceName");
        if(!string(request.get("confirmNamespaceName")).equals(repo.namespace(c.namespaceId).getProperty("name")))c.fail("VALIDATION_FAILED","Namespace name confirmation changed");
        var deleted=new ArrayList<String>();
        for(String kind:List.of("Object","Class","Pattern","UserTool"))for(Node n:repo.nodes(kind,Map.of("namespaceId",c.namespaceId))) {c.state.kernel.assertOpen();repo.remove(n,deleted);}
        deleted.sort(String::compareTo);
        return new ActionService.Change(new Outcome(null,null,Json.map("deletedIds",deleted)),!deleted.isEmpty());
    }
}
