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
        Object roots = switch (procedure) {
            case "deposit.get.namespaceData" -> List.of();
            case "deposit.get.data" -> arguments.getFirst();
            case "deposit.save.data" -> ValueCodec.map(arguments.getFirst()).get("rootIds");
            default -> null;
        };
        if (!Set.of("deposit.get.namespaceData","deposit.get.data","deposit.save.data").contains(procedure)) return "viewer";
        var namespace=repo.namespace(active());
        boolean whole=roots==null||roots instanceof List<?> list&&list.isEmpty();
        return whole&&namespace!=null&&"persistent".equals(namespace.getProperty("historyMode","disabled"))?"owner":"viewer";
    }
    long baseline(String scope) {
        Long value=c.state.observations.get(scope);
        if(value!=null)return value;
        c.fail("REVISION_CONFLICT","No observed revision; refresh this scope before writing");return -1;
    }
}
