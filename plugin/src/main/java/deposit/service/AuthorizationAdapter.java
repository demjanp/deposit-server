package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import org.neo4j.graphdb.Node;

/** Procedure-level Community policy. Native database access remains outside this boundary. */
final class AuthorizationAdapter {
    static boolean administrator(Node user) { return "administrator".equals(user.getProperty("serverRole", "user")); }
    static boolean mode(RequestContext c, Node user) {
        boolean flag = c.state.scoped ? c.state.administrative : c.session.administrative;
        boolean enabled=flag && administrator(user) && Boolean.TRUE.equals(user.getProperty("enabled", false));
        if(enabled)c.state.privileged=true;
        return enabled;
    }
    static String role(RequestContext c, CoreRepository repo, String namespace) {
        Node user = repo.find("User", "id", c.actorId);
        return mode(c, user) ? "owner" : repo.role(c.actorId, namespace);
    }
}
