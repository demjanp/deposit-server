package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.OperationAttemptRepository;
import deposit.repository.OperationAttemptRepository.Attempt;
import deposit.repository.OperationAttemptRepository.Key;
import deposit.repository.OperationAttemptRepository.Source;
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
        attempts = c.server.storage.attempts;
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
        c.server.lockOperationAdmission(c.state.kernel);
        var key=new Key(c.actorId,scope,id);
        var attempt=attempts.find(key);
        if(attempt!=null) {
            checkControlSource(attempt);
            if(attempt.procedure()!=null && (!procedure.equals(attempt.procedure()) || !hash.equals(attempt.inputHash())
                    || !namespace.equals(attempt.authorizationScope())))
                c.fail("OPERATION_MISMATCH","Operation input changed");
            return status(attempt,id,scope,false);
        }
        long now=c.server.sessionTime();
        if(!c.server.storage.operationalState.admit(id,now))
            c.fail("OPERATION_ID_EXPIRED","Operation ID is outside its admission window");
        String connection=connection();
        if(!c.server.connectionLive(connection))c.fail("AUTH_FAILED","A live Bolt execution connection is required");
        String uploadId=writer(procedure,arguments,scope);
        Map<String,Object> bindings=new LinkedHashMap<>();
        Map<String,Object> resolved=new ArgumentService(c,bindings,false).request(procedure,arguments);
        Source source=bindSource(procedure,scope,resolved);
        Long revision=scopes.operational(procedure)?null:scopes.baseline(scope);
        Long namespaceRevision=procedure.equals("deposit.delete.namespace")?scopes.baseline(namespace):null;
        attempt=new Attempt(key,procedure,hash,namespace,role,revision,namespaceRevision,source,uploadId,
            OperationAttemptRepository.idTime(id)+OperationAttemptRepository.RETENTION,"REGISTERED",now,"none",null,null);
        c.server.registerOperation(c.state,attempt,new ServerContext.OperationBinding(c.state.tokenHash,connection,bindings));
        return status(attempt,id,scope,false);
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
        var key=new Key(c.actorId,operationScope,id);
        var saved=attempts.find(key);
        if(saved==null)throw unavailable();
        if(!"REGISTERED".equals(saved.state()))failClaim(saved.state());
        if(!procedure.equals(saved.procedure())||!c.inputHash.equals(saved.inputHash()))
            c.fail("OPERATION_MISMATCH","Operation input changed");
        var binding=c.server.operationBinding(key);
        if(!registrationLive(saved,c.server.sessionTime()) || binding==null
                || !Objects.equals(connection,binding.connection()) || !c.state.tokenHash.equals(binding.session()))
            c.fail("OPERATION_NOT_CLAIMABLE","Execution binding expired or changed. Inspect the operation");
        if(procedure.equals("deposit.delete.namespace")&&!scopes.active().equals(saved.authorizationScope()))
            c.fail("SCOPE_MISMATCH","Deletion target changed");
        authorize(procedure,saved.authorizationScope(),saved.role(),true);
        checkSource(saved,true);
        writer(procedure,arguments,operationScope);
        c.state.operationConnection=connection;
        c.state.operationActor=c.actorId;
        c.state.operationScope=operationScope;
        c.state.operationId=id;
        try(var claim=c.server.database().beginTx()) {
            c.server.lockAttempt(((InternalTransaction)claim).kernelTransaction(),key);
            if(!registrationLive(saved,c.server.sessionTime()))
                c.fail("OPERATION_NOT_CLAIMABLE","Execution binding expired or changed. Inspect the operation");
            c.server.beginOperationalCommit(c.state,c.actorId,operationScope,id,c.inputHash);
            claim.commit();
        }
        c.expectedRevision=saved.expectedRevision();
        c.request=new ArgumentService(c,binding.selectors(),true).request(procedure,arguments);
        if(procedure.equals("deposit.delete.namespace"))c.request.put("expectedNamespaceRevision",saved.namespaceRevision());
    }

    void finish(Object row) {
        c.server.storagePoint("operation-before-final-fence");
        boolean serverScope = c.actionScope.equals("server");
        repo.lockActor(c.actorId);
        if (!serverScope) repo.lockNamespace(c.actionScope);
        Node owner = serverScope ? repo.find("User", "id", c.actorId) : repo.namespace(c.actionScope);
        if (owner == null) c.fail("OUTCOME_UNKNOWN", "Operation commit marker owner is unavailable");
        var key=new Key(c.actorId,c.actionScope,c.operationId);
        c.server.lockAttempt(c.state.kernel,key);
        var attempt=attempts.find(key);
        if(attempt==null)c.fail("OUTCOME_UNKNOWN","Operation claim is unavailable");
        if(!"RUNNING".equals(attempt.state())||c.server.operationWorker(key)!=c.state
                || !c.server.connectionLive(c.state.operationConnection))
            c.fail("CANCELLED","Operation cancelled before the commit boundary");
        String procedure=c.procedure;
        String outcome=domainWrite(procedure)?"COMMITTED":"COMPLETED_NO_DOMAIN_WRITE";
        c.server.prepareOperationalCommit(c.state,owner,outcome,Json.write(summary(row)));
        if(!c.state.enterOperationFinal())c.fail("CANCELLED","Operation cancelled before the commit boundary");
        // Deletion and account self-changes have already checked the target before their writes.
        if(!procedure.equals("deposit.delete.namespace")&&!c.state.selfAccountChange)
            authorize(procedure,attempt.authorizationScope(),attempt.role(),true);
        c.checkSourceAuthorization.run();
        checkSource(attempt,false);
        c.server.writeOperationalMarker(c.state,owner);
    }

    Map<String,Object> inspect(Map<String,Object> request,boolean cancel) {
        String id=OperationAttemptRepository.id(request.get("operationId"));
        String scope=scope(request.get("scope"));
        var key=new Key(c.actorId,scope,id);
        var attempt=attempts.find(key);
        if(attempt==null&&cancel) {
            separate();
            c.server.lockOperationAdmission(c.state.kernel);
            attempt=attempts.find(key);
            if(attempt==null) {
                authorize(null,scope,"viewer",false);
                long now=c.server.sessionTime();
                if(!c.server.storage.operationalState.admit(id,now))return status(null,id,scope,false);
                attempt=new Attempt(key,null,null,null,null,null,null,null,null,
                    OperationAttemptRepository.idTime(id)+OperationAttemptRepository.RETENTION,
                    "CANCELLED_ROLLED_BACK",now,"none",null,null);
                c.server.registerOperation(c.state,attempt,null);
                return status(attempt,id,scope,false);
            }
        }
        if(attempt==null) {
            authorize(null,scope,"viewer",false);
            return status(null,id,scope,false);
        }
        boolean sanitized=checkAccess(attempt,scope,cancel);
        if(cancel&&OperationAttemptRepository.open(attempt.state())) {
            if(!c.state.selection)separate();
            var worker=c.server.operationWorker(key);
            if(worker!=null)worker.signalOperationCancellation();
            c.server.lockAttempt(c.state.kernel,key);
            attempt=attempts.find(key);
            if(attempt==null)return status(null,id,scope,false);
            sanitized=checkAccess(attempt,scope,true);
            if(OperationAttemptRepository.open(attempt.state())) {
                worker=c.server.operationWorker(key);
                boolean live=worker!=null&&worker.operationClaimed&&!worker.operationSettled;
                if("REGISTERED".equals(attempt.state())) {
                    if(c.server.cancelRegistration(c.state,key))attempt=attempt.cancelled(c.server.sessionTime());
                    else attempt=attempts.find(key);
                }else if(live&&!worker.operationFinal()) {
                    attempts.cancel(attempt,true,c.server.sessionTime());
                    attempt=attempts.find(key);
                }
            }
        }
        return status(attempt,id,scope,sanitized);
    }

    private boolean checkAccess(Attempt attempt,String scope,boolean cancel) {
        String procedure=attempt.procedure();
        if(procedure==null) {
            authorize(null,scope,"viewer",false);
            return false;
        }
        if("server".equals(scope)&&SERVER_RECEIPTS.contains(procedure)&&"COMMITTED".equals(attempt.state())) {
            checkAccount(false);
            if(!cancel||procedure.equals("deposit.delete.namespace"))return true;
            if(attempt.result()==null)return true;
            var result=Json.read(attempt.result());
            if(!(result.get("namespaceId") instanceof String namespace))throw unavailable();
            authorize(procedure,namespace,"viewer",false);
        }
        authorize(procedure,attempt.authorizationScope(),attempt.role(),false);
        checkControlSource(attempt);
        return false;
    }

    private void checkControlSource(Attempt attempt) {
        // Receiving stage disposal leaves status and cancellation under the receiving policy.
        if(attempt.source()!=null&&attempt.source().receivingUpload())return;
        if(!Set.of("COMMITTED","COMPLETED_NO_DOMAIN_WRITE").contains(attempt.state())
                || !Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern","deposit.import.apply").contains(attempt.procedure()))
            checkSource(attempt,true);
    }

    private Map<String,Object> status(Attempt attempt,String id,String scope,boolean sanitized) {
        String state=attempt==null?"OUTCOME_UNKNOWN":attempt.state();
        long now=c.server.sessionTime();
        boolean successful="COMMITTED".equals(state)||"COMPLETED_NO_DOMAIN_WRITE".equals(state);
        if(successful&&attempt.result()==null)state="OUTCOME_UNKNOWN";
        if("REGISTERED".equals(state)&&!registrationLive(attempt,now))state="CANCELLED_ROLLED_BACK";
        if("RUNNING".equals(state)||"CANCEL_REQUESTED".equals(state)) {
            var worker=c.server.operationWorker(attempt.key());
            if(worker==null||worker.operationSettled)state="OUTCOME_UNKNOWN";
            else if(worker.operationCancelled())state="CANCEL_REQUESTED";
            else if(!registrationLive(attempt,Long.MIN_VALUE)&&!worker.operationFinal()) {
                worker.signalOperationCancellation();
                state="CANCEL_REQUESTED";
            }
        }
        String code="OUTCOME_UNKNOWN".equals(state)?"OUTCOME_UNKNOWN":null;
        Map<String,Object> result=sanitized||!successful||code!=null?null:Json.read(attempt.result());
        if(result!=null && ImportResultContract.FORMAT.equals(result.get("format"))) {
            var root=c.server.storage.importResults.root((String)result.get("resultId"),null);
            if(now>=(long)result.get("expiresAt"))code="RESULT_EXPIRED";
            else if(root==null || !id.equals(root.operation()) || !c.actorId.equals(root.actor())
                    || !Objects.equals(result.get("expiresAt"),root.expires()))code="RESULT_UNAVAILABLE";
            else if(root.sourcePackage()!=null) {
                try {new ExchangePackages(c).authorizeResultSource(root.sourcePackage());}
                catch(ExceptionHandler.DepositException denied) {code="RESULT_UNAVAILABLE";}
            }
        } else if(result!=null && state.equals("COMPLETED_NO_DOMAIN_WRITE")) {
            if(now-attempt.updated()>=OperationAttemptRepository.RETENTION) {
                code="RESULT_EXPIRED";
                result=null;
            } else if(Set.of("deposit.exchange.export","deposit.exchange.finishUpload","deposit.get.data","deposit.get.namespaceData").contains(attempt.procedure())
                    && result.get("packageId") instanceof String packageId) {
                var stage=c.server.storage.exchange.stage(packageId);
                if(stage==null||stage.expiresAt()<=now){code="RESULT_EXPIRED";result=null;}
                else if(!"ready".equals(stage.state())){code="RESULT_UNAVAILABLE";result=null;}
            } else if(EXPORTS.contains(attempt.procedure())&&!result.containsKey("resourceId")&&!result.containsKey("packageId")) {
                code="RESULT_UNAVAILABLE";
            }
        }
        return Json.map("operationId",id,"scope",scope,"procedure",attempt==null?null:attempt.procedure(),"state",state,
            "cleanupState",attempt==null?"none":attempt.cleanup(),"updatedAt",attempt==null?now:attempt.updated(),
            "code",code,"result",result);
    }

    private boolean registrationLive(Attempt attempt,long now) {
        var binding=c.server.operationBinding(attempt.key());
        return binding!=null && now<attempt.claimDeadline() && c.server.sessionLive(binding.session())
            && c.server.connectionLive(binding.connection());
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
            return packages.writer(arguments.getFirst(),scope,procedure.endsWith("abortUpload")).id();
        }
        if (!RESOURCE_STEPS.contains(procedure)) return null;
        String id = RequestContext.uuid(arguments.getFirst());
        var upload=c.server.storage.transfers.transfer(id);
        if (upload == null || !c.actorId.equals(upload.actor())
                || !scope.equals(upload.namespace())) throw unavailable();
        if (!"uploading".equals(upload.state()))
            c.fail("UPLOAD_FENCED", "Upload writer is no longer open");
        c.server.storage.transfers.requireSettled(upload.resource(),c.server.operationalUnit(c.state.kernel));
        UploadBinding.requireUpload(c,id,upload.writerBound());
        for(var attempt:attempts.uploadAttempts(id)) {
            if(!Objects.equals(c.operationId,attempt.key().id()) && UploadBinding.failedStep(c.server,attempt))
                c.fail("UPLOAD_FENCED", "Upload was interrupted. Begin a new upload at offset zero");
        }
        return id;
    }

    private Source bindSource(String procedure, String scope, Map<String,Object> request) {
        return switch (procedure) {
            case "deposit.import.analyze", "deposit.import.cloneNamespace" ->
                bindExchange(scope,ValueCodec.map(request.get("source")));
            case "deposit.import.apply" -> {
                var plan=c.server.storage.importPlans.plan(RequestContext.uuid(request.get("planId")));
                if (plan == null || !scope.equals(plan.namespace()) || !c.actorId.equals(plan.actor())
                        || !"ready".equals(plan.state()) || plan.expires()<=c.server.sessionTime())
                    throw unavailable();
                if(!UploadBinding.stagingOwnedBy(c,plan.id()))
                    c.fail("PLAN_FENCED","Import plan is unavailable. Analyze the original input again");
                Object grantId = plan.sourceGrant();
                yield grantId == null ? null : bindExchange(scope,Map.of("packageId",plan.sourcePackage(),"grant",grantId));
            }
            default -> null;
        };
    }

    private Source bindExchange(String scope,Map<String,Object> raw) {
        var source=ExchangePackages.source(raw);
        String id=RequestContext.uuid(source.get("packageId")), grant=string(source.get("grant"));
        var packages=new ExchangePackages(c);
        var stage=packages.input(id,grant,scope,true,false);
        return new Source(id,grant,!packages.sourceExport(stage));
    }

    private void checkSource(Attempt attempt,boolean acquiring) {
        var source=attempt.source();
        if(source!=null)new ExchangePackages(c).input(source.packageId(),source.grant(),attempt.key().scope(),acquiring,false);
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
