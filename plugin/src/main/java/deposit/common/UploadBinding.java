package deposit.common;

import deposit.repository.CoreRepository;
import java.util.Map;
import java.util.List;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Incomplete staging belongs to its original Bolt connection and session. */
public final class UploadBinding {
    private static final List<String> WRITERS=List.of("DepositTransfer","DepositExchangeStage");
    private UploadBinding() {}

    public static void bind(RequestContext context, Node writer) {
        if (!context.state.operationClaimed) return;
        writer.setProperty("writerIncarnation", context.server.runId);
        writer.setProperty("writerSession", context.state.tokenHash);
        writer.setProperty("writerConnection", connection(context));
    }

    public static void require(RequestContext context, Node writer) {
        if (!context.publicCall || context.operationId == null && !"deposit.action.begin".equals(context.procedure)) return;
        if (!ownedByConnection(context,writer))
            context.fail("UPLOAD_FENCED", "Upload writer is unavailable. Begin a new upload at offset zero");
    }

    public static boolean ownedByConnection(RequestContext context,Node writer) {
        return context.server.runId.equals(writer.getProperty("writerIncarnation",null))
            && context.state.tokenHash.equals(writer.getProperty("writerSession",null))
            && java.util.Objects.equals(connection(context),writer.getProperty("writerConnection",null))
            && !abandoned(context.server,writer);
    }

    public static boolean abandoned(ServerContext server, Node writer) {
        return writer.hasProperty("writerIncarnation") && (!server.runId.equals(writer.getProperty("writerIncarnation"))
            || !server.sessionLive((String)writer.getProperty("writerSession"))
            || !server.connectionLive((String)writer.getProperty("writerConnection"))
            || Boolean.TRUE.equals(writer.getProperty("writerFenced", false)));
    }

    static boolean validContinuation(RequestContext c,String hash,String actor) {
        if(c.uploadContinuation==null)return false;
        for(String label:WRITERS){
            Node writer=c.tx.findNode(Label.label(label),"id",c.uploadContinuation);
            if(writer!=null && "uploading".equals(writer.getProperty("state",null))
                    && actor.equals(writer.getProperty("actorId",null))
                    && hash.equals(writer.getProperty("writerSession",null))
                    && java.util.Objects.equals(connection(c),writer.getProperty("writerConnection",null))
                    && !abandoned(c.server,writer))
                return true;
        }
        return false;
    }

    static boolean hasLiveWriter(RequestContext c,String hash) {
        for(String label:WRITERS)try(var writers=c.tx.findNodes(Label.label(label),"writerSession",hash)){
            while(writers.hasNext()){
                Node writer=writers.next();
                if("uploading".equals(writer.getProperty("state",null))&&!abandoned(c.server,writer))return true;
            }
        }
        return false;
    }

    static boolean abandoned(ServerContext server, CoreRepository core, Node writer) {
        if (abandoned(server, writer)) return true;
        return core.nodes("DepositOperationAttempt", Map.of("uploadId", CoreRepository.id(writer))).stream()
            .anyMatch(attempt -> failedStep(server, attempt.getAllProperties()));
    }

    static void cleaned(CoreRepository core, String uploadId) {
        for (Node attempt : core.nodes("DepositOperationAttempt", Map.of("uploadId", uploadId))) {
            if (!"RUNNING".equals(attempt.getProperty("state"))) {
                attempt.setProperty("cleanupState", "complete");
                attempt.removeProperty("uploadId");
            }
        }
    }

    static void cleanupFailed(CoreRepository core, String uploadId) {
        for (Node attempt : core.nodes("DepositOperationAttempt", Map.of("uploadId", uploadId))) {
            if (!deposit.repository.OperationAttemptRepository.open((String)attempt.getProperty("state")))
                attempt.setProperty("cleanupState","failed");
        }
    }

    public static boolean failedStep(ServerContext server, Map<String,Object> attempt) {
        String state = (String)attempt.get("state");
        if (state.equals("FAILED_ROLLED_BACK") || state.equals("CANCELLED_ROLLED_BACK") || state.equals("CANCEL_REQUESTED")) return true;
        if (!server.runId.equals(attempt.get("incarnation"))) return true;
        if (state.equals("REGISTERED")) return server.sessionTime() >= (long)attempt.get("claimDeadline");
        if (!state.equals("RUNNING")) return false;
        var worker = server.activeTransaction((long)attempt.get("transactionSequence"));
        return worker == null || worker.operationSettled;
    }

    private static String connection(RequestContext context) {
        return ((InternalTransaction)context.tx).clientInfo().connectionId();
    }
}
