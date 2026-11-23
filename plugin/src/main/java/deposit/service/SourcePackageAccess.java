package deposit.service;

import deposit.common.*;
import deposit.repository.ResourceRepository;
import java.util.Map;
import org.neo4j.graphdb.Node;

/** Current local source authorization for the resource-backed Exchange paths. */
final class SourcePackageAccess extends BaseService {
    private final ResourceRepository resources;

    SourcePackageAccess(RequestContext c) {
        super(c);
        resources = new ResourceRepository(c.tx, repo);
    }

    String authorize(String packageId, String boundGrant, boolean acquiring) {
        Node location = resources.location(packageId);
        if (location == null) throw unavailable();
        String namespace = (String)location.getProperty("namespaceId");
        Node resource = resources.resource(namespace, packageId);
        String role = effectiveRole(namespace);
        if (resource == null || !"ready".equals(resource.getProperty("state", null))
                || repo.namespace(namespace)==null || role==null || rank(role)<rank("viewer")) throw unavailable();
        var grants = repo.nodes("DepositExportGrant", Map.of("resourceId",packageId,"actorId",c.actorId,"namespaceId",namespace));
        if (boundGrant != null || !grants.isEmpty()) {
            for (Node grant : grants) {
                String id = (String)grant.getProperty("id");
                if ((boundGrant == null || boundGrant.equals(id))
                        && (!acquiring || ResourceRepository.number(grant,"expires",0)>resources.now())
                        && (!Boolean.TRUE.equals(grant.getProperty("history",false)) || "owner".equals(role))) return id;
            }
            throw unavailable();
        }
        if (!resources.accessible(resource,c.actorId,role,false)) throw unavailable();
        return null;
    }

    private static RuntimeException unavailable() {
        return ExceptionHandler.fail("NOT_FOUND", "Package resource is unavailable");
    }
}
