package deposit.service;

import deposit.common.*;
import java.util.*;

/** Request-local selection, scope, role and pinned-revision calculations. */
final class SessionActionScope extends BaseService {
    SessionActionScope(RequestContext c) { super(c); }
    String active() {
        String namespace=c.state.scoped?c.state.namespace:c.session.namespace;
        if(namespace==null)c.fail("NO_ACTIVE_NAMESPACE","Select a Namespace first");
        return namespace;
    }
    String scope(String procedure,List<Object> arguments) {
        String scope=ProcedureCatalog.spec(procedure).scope();
        if(scope.equals("prune"))return "all".equals(ValueCodec.map(arguments.getFirst()).get("scope"))?"server":active();
        return scope.equals("server")?"server":active();
    }
    boolean operational(String procedure) {
        return ProcedureCatalog.spec(procedure).scope().equals("operational")||procedure.equals("deposit.admin.pruneResources");
    }
    String exportRole(String procedure,List<Object> arguments) {
        Object options=null;
        if(procedure.equals("deposit.save.data")||procedure.equals("deposit.get.data"))options=arguments!=null&&arguments.size()>1?arguments.get(1):null;
        else if(procedure.equals("deposit.get.namespaceData"))options=arguments!=null&&!arguments.isEmpty()?arguments.get(0):null;
        else if(procedure.equals("deposit.save.csv")||procedure.equals("deposit.save.xlsx")||procedure.equals("deposit.save.gpkg"))return "viewer";
        return options instanceof Map<?,?> m&&Boolean.TRUE.equals(ValueCodec.map(m).get("includeHistory"))?"owner":"viewer";
    }
    long baseline(String scope) {
        Long value=c.state.observations.get(scope);
        if(value!=null)return value;
        c.fail("REVISION_CONFLICT","No observed revision; refresh this scope before writing");return -1;
    }
}
