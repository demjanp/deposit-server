package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.OperationAttemptRepository;
import java.util.*;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Authorization and ordering for the public one-attempt operation controls. */
final class OperationAttemptService extends BaseService {
    private static final Set<String> EXPORTS = Set.of("deposit.get.data", "deposit.get.namespaceData", "deposit.save.data","deposit.exchange.export",
        "deposit.save.csv", "deposit.save.xlsx", "deposit.save.gpkg");
    private static final Set<String> OWNER = Set.of("deposit.set.namespace", "deposit.delete.all", "deposit.set.namespaceRole",
        "deposit.set.versioningType", "deposit.set.versionTag", "deposit.utils.restore");
    private static final Set<String> ADMIN = Set.of("deposit.create.user", "deposit.set.user", "deposit.admin.pruneResources");
    private static final Set<String> SERVER_RECEIPTS = Set.of("deposit.delete.namespace", "deposit.import.cloneNamespace");
    private static final Set<String> RESOURCE_STEPS = Set.of("deposit.resource.uploadChunk", "deposit.resource.finishUpload", "deposit.resource.abortUpload");
    private static final Set<String> EXCHANGE_STEPS = Set.of("deposit.exchange.uploadChunk","deposit.exchange.finishUpload","deposit.exchange.abortUpload");
    private static final Set<String> SUMMARY_KEYS = Set.of("id", "namespaceId", "revisionId", "targetRevision",
        "planId", "resultId", "uploadId", "resourceId", "packageId", "tableId", "pruneId", "nextOffset", "bytes", "changed","grant","sha256","expiresAt","memberCount","member","chunkBytes","checksum","state","aborted");
    private final OperationAttemptRepository attempts;
    private final SessionActionScope scopes;

    OperationAttemptService(RequestContext c) {
        super(c);
        attempts = new OperationAttemptRepository(c.tx);
        scopes = new SessionActionScope(c);
    }

    static boolean tracked(String procedure, List<Object> arguments) {
        if (!ProcedureCatalog.spec(procedure).action()) return false;
        return !procedure.equals("deposit.admin.pruneResources")
            || Boolean.FALSE.equals(ValueCodec.map(arguments.getFirst()).get("dryRun"));
    }

    static String uploadContinuation(String procedure,List<?> arguments) {
        if (procedure.equals("deposit.action.begin") && arguments.size()==4
                && arguments.get(2) instanceof String route && arguments.get(3) instanceof List<?> domain)
            return uploadStepId(route,domain);
        return uploadStepId(procedure,arguments);
    }

    private static String uploadStepId(String route,List<?> arguments) {
        if ((RESOURCE_STEPS.contains(route)||EXCHANGE_STEPS.contains(route)) && !arguments.isEmpty()
                && arguments.getFirst() instanceof String id) return id;
        return null;
    }

    Map<String,Object> begin(Map<String,Object> request) {
        separate();
        String id = OperationAttemptRepository.id(request.get("operationId"));
        String scope = scope(request.get("scope"));
        String procedure = string(request.get("procedure"));
        var spec = ProcedureCatalog.spec(procedure);
        if (!(request.get("arguments") instanceof List<?> raw) || raw.size()!=spec.bindings().size())
            throw ExceptionHandler.fail("VALIDATION_FAILED", "Wrong number of domain arguments");
        @SuppressWarnings("unchecked") List<Object> arguments = (List<Object>) raw;
        if (!tracked(procedure, arguments)) c.fail("VALIDATION_FAILED", "Procedure does not accept an operation");
        if (!scope.equals(scopes.scope(procedure, arguments))) c.fail("SCOPE_MISMATCH", "Procedure scope changed");
        String namespace = procedure.equals("deposit.delete.namespace") ? scopes.active() : scope;
        String role = EXPORTS.contains(procedure) ? scopes.exportRole(procedure, arguments)
            : procedure.equals("deposit.admin.pruneResources") ? "viewer"
            : procedure.equals("deposit.delete.namespace") || OWNER.contains(procedure) || batchNeedsOwner(procedure,arguments)
                ? "owner" : "editor";
        authorize(procedure, namespace, role, false);
        String hash = fingerprint(procedure, arguments);
        Node admission = attempts.lockAdmission();
        Node node = attempts.find(c.actorId, scope, id);
        if (node != null) {
            c.tx.acquireReadLock(node);
            checkControlSource(node,procedure);
            if (node.hasProperty("procedure") && (!procedure.equals(node.getProperty("procedure"))
                    || !hash.equals(node.getProperty("inputHash")) || !namespace.equals(node.getProperty("authorizationScope"))))
                c.fail("OPERATION_MISMATCH", "Operation input changed");
            return status(node, id, scope, false);
        }
        long now = c.server.sessionTime();
        attempts.admit(admission, id, now);
        String connection = connection();
        if (!c.server.connectionLive(connection)) c.fail("AUTH_FAILED", "A live Bolt execution connection is required");
        String uploadId = writer(procedure, arguments, scope);
        Map<String,Object> bindings = new LinkedHashMap<>();
        Map<String,Object> resolved = new ArgumentService(c, bindings, false).request(procedure, arguments);
        SourceBinding source = bindSource(procedure, scope, resolved);
        node = attempts.create(c.actorId, scope, id);
        node.setProperty("procedure", procedure);
        node.setProperty("inputHash", hash);
        node.setProperty("authorizationScope", namespace);
        node.setProperty("role", role);
        node.setProperty("connectionId", connection);
        node.setProperty("sessionHash", c.state.tokenHash);
        node.setProperty("incarnation", c.server.runId);
        node.setProperty("bindings", Json.write(bindings));
        if (uploadId != null) node.setProperty("uploadId", uploadId);
        if (source != null) {
            node.setProperty("sourceExchange",source.packageId);
            node.setProperty("sourceGrant",source.grantId);
            if(source.receivingUpload)node.setProperty("receivingUpload",true);
        }
        node.setProperty("claimDeadline", OperationAttemptRepository.idTime(id) + OperationAttemptRepository.RETENTION);
        if (!scopes.operational(procedure)) node.setProperty("expectedRevision", scopes.baseline(scope));
        if (procedure.equals("deposit.delete.namespace")) node.setProperty("namespaceRevision", scopes.baseline(namespace));
        OperationAttemptRepository.transition(node, "REGISTERED", now);
        return status(node, id, scope, false);
    }

    private static boolean batchNeedsOwner(String procedure,List<Object> arguments) {
        if (!procedure.equals("deposit.apply.batch") || !(arguments.getFirst() instanceof List<?> commands)) return false;
        return commands.stream().anyMatch(command -> command instanceof Map<?,?> map
            && map.get("procedure") instanceof String route && DomainService.COMMANDS.contains(route)
            && "owner".equals(DomainService.commandRole(route)));
    }

    void claim(String procedure, List<Object> arguments, String operationScope) {
        String id = OperationAttemptRepository.id(c.operationId);
        c.actionScope = operationScope;
        c.operational = scopes.operational(procedure);
        c.inputHash = fingerprint(procedure, arguments);
        String connection = connection();
        Map<String,Object> saved;
        try (var registration = c.server.database().beginTx()) {
            var records = new OperationAttemptRepository(registration);
            Node node = records.find(c.actorId, operationScope, id);
            if (node == null) throw unavailable();
            registration.acquireWriteLock(node);
            saved = node.getAllProperties();
            String state = (String)saved.get("state");
            if (!state.equals("REGISTERED")) failClaim(state);
            if (!procedure.equals(saved.get("procedure")) || !c.inputHash.equals(saved.get("inputHash")))
                c.fail("OPERATION_MISMATCH", "Operation input changed");
            long now = c.server.sessionTime();
            if (!c.server.runId.equals(saved.get("incarnation")) || now >= (long)saved.get("claimDeadline")
                    || !Objects.equals(connection,saved.get("connectionId")) || !c.state.tokenHash.equals(saved.get("sessionHash"))
                    || !c.server.connectionLive(connection))
                c.fail("OPERATION_NOT_CLAIMABLE", "Execution binding expired or changed. Inspect the operation");
            if (procedure.equals("deposit.delete.namespace") && !scopes.active().equals(saved.get("authorizationScope")))
                c.fail("SCOPE_MISMATCH", "Deletion target changed");
            authorize(procedure,(String)saved.get("authorizationScope"),(String)saved.get("role"),true);
            checkSource(saved,true);
            writer(procedure, arguments, operationScope);
            c.state.operationConnection = connection;
            c.state.operationActor = c.actorId;
            c.state.operationScope = operationScope;
            c.state.operationId = id;
            c.state.operationClaimed = true;
            node.setProperty("requestId", c.requestId);
            node.setProperty("transactionSequence", c.state.kernel.getTransactionSequenceNumber());
            OperationAttemptRepository.transition(node, "RUNNING", now);
            registration.commit();
        }
        c.expectedRevision = (Long)saved.get("expectedRevision");
        c.request = new ArgumentService(c, Json.read((String)saved.get("bindings")), true).request(procedure, arguments);
        if (procedure.equals("deposit.delete.namespace")) c.request.put("expectedNamespaceRevision", saved.get("namespaceRevision"));
    }

    void finish(Object row) {
        c.server.storagePoint("operation-before-final-fence");
        Node node = attempts.find(c.actorId, c.actionScope, c.operationId);
        if (node == null) c.fail("OUTCOME_UNKNOWN", "Operation claim is unavailable");
        c.tx.acquireWriteLock(node);
        if (!"RUNNING".equals(OperationAttemptRepository.state(node))
                || !c.requestId.equals(node.getProperty("requestId", null))
                || !c.server.connectionLive(c.state.operationConnection)
                || !c.state.enterOperationFinal())
            c.fail("CANCELLED", "Operation cancelled before the commit boundary");
        String procedure = c.procedure;
        // A deletion checks the target owner under the authorization and Namespace
        // locks before removing it. Account self-changes have the same pre-write check.
        if (!procedure.equals("deposit.delete.namespace") && !c.state.selfAccountChange)
            authorize(procedure,(String)node.getProperty("authorizationScope"),(String)node.getProperty("role"),true);
        c.checkSourceAuthorization.run();
        checkSource(node.getAllProperties(),false);
        OperationAttemptRepository.transition(node, domainWrite(procedure) ? "COMMITTED" : "COMPLETED_NO_DOMAIN_WRITE", c.server.sessionTime());
        node.setProperty("result", Json.write(summary(row)));
        node.removeProperty("uploadId");
        OperationAttemptRepository.clearClaim(node);
    }

    Map<String,Object> inspect(Map<String,Object> request, boolean cancel) {
        String id = OperationAttemptRepository.id(request.get("operationId"));
        String scope = scope(request.get("scope"));
        Node node = attempts.find(c.actorId, scope, id);
        if (node == null && cancel) {
            separate();
            Node admission = attempts.lockAdmission();
            node = attempts.find(c.actorId, scope, id);
            if (node == null) {
                authorize(null, scope, "viewer", false);
                long now = c.server.sessionTime();
                if (!attempts.admissible(admission,id,now)) return status(null,id,scope,false);
                attempts.admit(admission, id, now);
                node = attempts.create(c.actorId, scope, id);
                node.setProperty("claimDeadline", OperationAttemptRepository.idTime(id) + OperationAttemptRepository.RETENTION);
                OperationAttemptRepository.transition(node, "CANCELLED_ROLLED_BACK", now);
                return status(node, id, scope, false);
            }
        }
        if (node == null) {
            authorize(null, scope, "viewer", false);
            return status(null, id, scope, false);
        }
        boolean sanitized = checkAccess(node, scope, cancel);
        if (cancel && OperationAttemptRepository.open(OperationAttemptRepository.state(node))) {
            separate();
            var worker = worker(node.getAllProperties());
            if (worker != null) worker.signalOperationCancellation();
            c.tx.acquireWriteLock(node);
            sanitized = checkAccess(node, scope, true);
            String current = OperationAttemptRepository.state(node);
            if (OperationAttemptRepository.open(current)) {
                var values = node.getAllProperties();
                boolean live = !current.equals("REGISTERED") && worker(values) != null;
                long sequence = (long)values.getOrDefault("transactionSequence",-1L);
                boolean aborted = current.equals("REGISTERED") || current.equals("CANCEL_REQUESTED")
                    || c.server.runId.equals(values.get("incarnation")) && c.server.settledCancelled(sequence);
                OperationAttemptRepository.transition(node, live ? "CANCEL_REQUESTED" : aborted ? "CANCELLED_ROLLED_BACK" : "FAILED_ROLLED_BACK", c.server.sessionTime());
                if (node.hasProperty("uploadId")) node.setProperty("cleanupState","pending");
                if (!live) {
                    OperationAttemptRepository.clearClaim(node);
                    c.state.afterCommit.add(()->c.server.forgetCancelled(sequence));
                }
            }
        } else c.tx.acquireReadLock(node);
        return status(node, id, scope, sanitized);
    }

    private boolean checkAccess(Node node, String scope, boolean cancel) {
        String procedure = (String)node.getProperty("procedure", null);
        if (procedure == null) {
            authorize(null, scope, "viewer", false);
            return false;
        }
        if ("server".equals(scope) && SERVER_RECEIPTS.contains(procedure)
                && "COMMITTED".equals(OperationAttemptRepository.state(node))) {
            checkAccount(false);
            if (!cancel || procedure.equals("deposit.delete.namespace")) return true;
            var result=Json.read((String)node.getProperty("result"));
            if (!(result.get("namespaceId") instanceof String namespace)) throw unavailable();
            authorize(procedure,namespace,"viewer",false);
        }
        authorize(procedure,(String)node.getProperty("authorizationScope"),(String)node.getProperty("role"),false);
        checkControlSource(node,procedure);
        return false;
    }

    private void checkControlSource(Node attempt,String procedure) {
        // A verified receiving upload confers no source access. Its deletion must
        // leave status and cancellation available under the original receiving policy.
        if(Boolean.TRUE.equals(attempt.getProperty("receivingUpload",false)))return;
        if(!Set.of("COMMITTED","COMPLETED_NO_DOMAIN_WRITE").contains(OperationAttemptRepository.state(attempt))
                || !Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern","deposit.import.apply").contains(procedure))
            checkSource(attempt.getAllProperties(),true);
    }

    private Map<String,Object> status(Node node, String id, String scope, boolean sanitized) {
        var values = node == null ? Map.<String,Object>of() : node.getAllProperties();
        String state = (String)values.getOrDefault("state", "OUTCOME_UNKNOWN");
        long now = c.server.sessionTime();
        String code = state.equals("OUTCOME_UNKNOWN") ? "OUTCOME_UNKNOWN" : null;
        Map<String,Object> result = sanitized || !values.containsKey("result") ? null : Json.read((String)values.get("result"));
        if (state.equals("REGISTERED") && !registrationLive(values, Long.MIN_VALUE)) state = "CANCELLED_ROLLED_BACK";
        if (state.equals("RUNNING") || state.equals("CANCEL_REQUESTED")) {
            var worker = worker(values);
            if (worker == null) state = state.equals("CANCEL_REQUESTED")
                || c.server.runId.equals(values.get("incarnation")) && c.server.settledCancelled((long)values.get("transactionSequence"))
                ? "CANCELLED_ROLLED_BACK" : "FAILED_ROLLED_BACK";
            else if (worker.operationCancelled()) state="CANCEL_REQUESTED";
            else if (!registrationLive(values, Long.MIN_VALUE)) {
                worker.signalOperationCancellation();
                state = "CANCEL_REQUESTED";
            }
        }
        if(result!=null && ImportResultContract.FORMAT.equals(result.get("format"))) {
            Node root=repo.find(ImportResultContract.LABEL,"id",result.get("resultId"));
            if(now>=(long)result.get("expiresAt"))code="RESULT_EXPIRED";
            else if(root==null || !id.equals(root.getProperty("operationId",null))
                    || !c.actorId.equals(root.getProperty("actorId",null))
                    || !Objects.equals(result.get("expiresAt"),root.getProperty("expiresAt",null)))code="RESULT_UNAVAILABLE";
            else if(root.getProperty("sourcePackageId",null) instanceof String source) {
                try {new ExchangePackages(c).authorizeResultSource(source);}
                catch(ExceptionHandler.DepositException denied) {code="RESULT_UNAVAILABLE";}
            }
        } else if (result != null && state.equals("COMPLETED_NO_DOMAIN_WRITE")) {
            if (now - (long)values.get("updatedAt") >= OperationAttemptRepository.RETENTION) {
                code = "RESULT_EXPIRED";
                result = null;
            } else if (Set.of("deposit.exchange.export","deposit.exchange.finishUpload","deposit.get.data","deposit.get.namespaceData").contains(values.get("procedure"))
                    && result.get("packageId") instanceof String packageId) {
                Node stage=repo.find(ExchangePackages.LABEL,"id",packageId);
                if(stage==null || (long)stage.getProperty("expiresAt",0L)<=now) {code="RESULT_EXPIRED"; result=null;}
                else if(!"ready".equals(stage.getProperty("state",null))) {code="RESULT_UNAVAILABLE"; result=null;}
            } else if (EXPORTS.contains(values.get("procedure")) && !result.containsKey("resourceId") && !result.containsKey("packageId")) {
                code = "RESULT_UNAVAILABLE";
            }
        }
        return Json.map("operationId", id, "scope", scope, "procedure", values.get("procedure"), "state", state,
            "cleanupState", values.getOrDefault("cleanupState", "none"), "updatedAt", values.getOrDefault("updatedAt", now),
            "code", code, "result", result);
    }

    private boolean registrationLive(Map<String,Object> values, long now) {
        return c.server.runId.equals(values.get("incarnation")) && now < (long)values.get("claimDeadline")
            && c.server.sessionLive((String)values.get("sessionHash")) && c.server.connectionLive((String)values.get("connectionId"));
    }

    private ServerContext.TxState worker(Map<String,Object> values) {
        if (!c.server.runId.equals(values.get("incarnation")) || !(values.get("transactionSequence") instanceof Long sequence)) return null;
        var state = c.server.activeTransaction(sequence);
        return state != null && !state.operationSettled && state.requestIds.contains(values.get("requestId")) ? state : null;
    }

    private void authorize(String procedure,String namespace,String role,boolean execution) {
        boolean reuse=execution&&c.protectedAuthorization;
        Node user=checkAccount(reuse);
        if(procedure!=null&&ADMIN.contains(procedure)&&!AuthorizationAdapter.administrator(user))throw unavailable();
        if(!namespace.equals("server")) {
            Node root=reuse?repo.namespace(namespace):repo.find("Namespace","id",namespace);
            String actual=AuthorizationAdapter.role(c,repo,user,namespace,reuse);
            if(root==null||actual==null||rank(actual)<rank(role))throw unavailable();
        }
    }

    private Node checkAccount(boolean reuse) {
        Node user=enabledActor(reuse);
        if(user==null)throw unavailable();
        return user;
    }

    private void separate() {
        if (c.state.scoped || c.state.mutation || c.state.selection)
            c.fail("VALIDATION_FAILED", "Operation controls require a separate transaction");
        c.state.selection = true;
    }

    private void failClaim(String state) {
        c.fail("OPERATION_NOT_CLAIMABLE", "Operation is " + state + ". Inspect the operation");
    }

    private static RuntimeException unavailable() {
        return ExceptionHandler.fail("NOT_FOUND", "Operation is unavailable");
    }

    private String connection() { return ((InternalTransaction)c.tx).clientInfo().connectionId(); }
    private String writer(String procedure, List<Object> arguments, String scope) {
        if(EXCHANGE_STEPS.contains(procedure)) {
            var packages=new ExchangePackages(c);
            return deposit.repository.CoreRepository.id(packages.writer(arguments.getFirst(),scope,procedure.endsWith("abortUpload")));
        }
        if (!RESOURCE_STEPS.contains(procedure)) return null;
        String id = RequestContext.uuid(arguments.getFirst());
        Node upload = repo.find("DepositTransfer", "id", id);
        if (upload == null || !c.actorId.equals(upload.getProperty("actorId", null))
                || !scope.equals(upload.getProperty("namespaceId", null))) throw unavailable();
        if (!"uploading".equals(upload.getProperty("state")))
            c.fail("UPLOAD_FENCED", "Upload writer is no longer open");
        UploadBinding.require(c, upload);
        for (Node attempt : repo.nodes("DepositOperationAttempt", Map.of("uploadId", id))) {
            if (!Objects.equals(c.operationId,attempt.getProperty("operationId")) && UploadBinding.failedStep(c.server,attempt.getAllProperties()))
                c.fail("UPLOAD_FENCED", "Upload was interrupted. Begin a new upload at offset zero");
        }
        return id;
    }

    private record SourceBinding(String packageId, String grantId, boolean receivingUpload) {}

    private SourceBinding bindSource(String procedure, String scope, Map<String,Object> request) {
        return switch (procedure) {
            case "deposit.import.analyze", "deposit.import.cloneNamespace" ->
                bindExchange(scope,ValueCodec.map(request.get("source")));
            case "deposit.import.apply" -> {
                Node plan = repo.find("DepositImportPlan","id",RequestContext.uuid(request.get("planId")));
                if (plan == null || !scope.equals(plan.getProperty("namespaceId",null)) || !c.actorId.equals(plan.getProperty("actorId",null))
                        || !"ready".equals(plan.getProperty("state",null)) || (long)plan.getProperty("expiresAt",0L)<=c.server.sessionTime())
                    throw unavailable();
                if(!UploadBinding.ownedByConnection(c,plan))
                    c.fail("PLAN_FENCED","Import plan is unavailable. Analyze the original input again");
                Object grantId = plan.getProperty("sourceGrantId",null);
                yield grantId == null ? null : bindExchange(scope,Map.of("packageId",plan.getProperty("sourcePackageId"),"grant",grantId));
            }
            default -> null;
        };
    }

    private SourceBinding bindExchange(String scope,Map<String,Object> raw) {
        var source=ExchangePackages.source(raw);
        String id=RequestContext.uuid(source.get("packageId")), grant=string(source.get("grant"));
        var packages=new ExchangePackages(c);
        Node stage=packages.input(id,grant,scope,true,false);
        return new SourceBinding(id,grant,!packages.sourceExport(stage));
    }

    private void checkSource(Map<String,Object> attempt, boolean acquiring) {
        if(attempt.get("sourceExchange") instanceof String id)
            new ExchangePackages(c).input(id,(String)attempt.get("sourceGrant"),(String)attempt.get("scope"),acquiring,false);
    }

    private static String scope(Object raw) { return "server".equals(raw) ? "server" : RequestContext.uuid(raw); }
    private String fingerprint(String procedure, List<Object> arguments) {
        return CursorCodec.requestHash(c, Json.map("procedure", procedure, "arguments", arguments));
    }

    private Map<String,Object> summary(Object row) {
        Map<String,Object> data = switch (row) {
            case DataRow r -> r.data;
            case EntityRow r -> r.data;
            case RelRow r -> r.data;
            default -> null;
        };
        var result = Json.map("revision", c.revision);
        if(data!=null && ImportResultContract.FORMAT.equals(data.get("format"))) {
            result.put("format",ImportResultContract.FORMAT);
            result.put("revision",data.get("revision"));
            result.put("kind",data.get("kind"));
            result.put("state",data.get("state"));
            result.put("collections",data.get("collections"));
        }
        if (data != null) {
            for (String key : SUMMARY_KEYS) {
                Object value = data.get(key);
                if (value instanceof Number || value instanceof Boolean || value instanceof String s && s.length() <= 128)
                    result.put(key, value);
            }
        }
        return result;
    }

    private boolean domainWrite(String procedure) {
        return !scopes.operational(procedure)
            || Set.of("deposit.import.apply", "deposit.admin.pruneResources").contains(procedure);
    }
}
