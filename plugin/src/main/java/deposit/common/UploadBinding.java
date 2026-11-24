package deposit.common;

import deposit.repository.OperationAttemptRepository.Attempt;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** Incomplete staging belongs to its original Bolt connection and session. */
public final class UploadBinding {
    private UploadBinding() {}

    record Writer(String actor, String session, String connection) {}

    public static void bindStaging(RequestContext context, String id) {
        if (!context.state.operationClaimed) return;
        var writer = new Writer(context.actorId, context.state.tokenHash, connection(context));
        context.server.stagingWriters.put(id, writer);
        var committed = new boolean[1];
        context.state.afterCommit.add(() -> committed[0] = true);
        context.state.release.add(() -> {
            if (!committed[0]) context.server.stagingWriters.remove(id, writer);
        });
    }

    public static void requireUpload(RequestContext context, String id, boolean bound) {
        if (!context.publicCall || context.operationId == null && !"deposit.action.begin".equals(context.procedure)) return;
        if (!bound || !stagingOwnedBy(context, id, context.state.tokenHash, context.actorId))
            context.fail("UPLOAD_FENCED", "Upload writer is unavailable. Begin a new upload at offset zero");
    }

    public static boolean stagingOwnedBy(RequestContext context,String id) {
        return stagingOwnedBy(context,id,context.state.tokenHash,context.actorId);
    }

    public static boolean stagingAbandoned(ServerContext server,String id,boolean bound) {
        var writer=server.stagingWriters.get(id);
        return bound && (writer==null || !live(server,writer));
    }

    public static void forgetStaging(ServerContext server,String id) {
        server.stagingWriters.remove(id);
    }

    private static boolean stagingOwnedBy(RequestContext context, String id, String session, String actor) {
        var writer = context.server.stagingWriters.get(id);
        return writer != null && actor.equals(writer.actor()) && session.equals(writer.session())
            && Objects.equals(connection(context), writer.connection()) && live(context.server, writer);
    }

    private static boolean live(ServerContext server, Writer writer) {
        return server.sessionLive(writer.session()) && server.connectionLive(writer.connection());
    }

    static boolean abandonedUpload(ServerContext server,String id,boolean bound) {
        if(stagingAbandoned(server,id,bound))return true;
        return server.storage.attempts.uploadAttempts(id).stream().anyMatch(attempt->failedStep(server,attempt));
    }

    static boolean validContinuation(RequestContext c,String hash,String actor) {
        if(c.uploadContinuation==null)return false;
        var stage = c.server.storage.exchange.stage(c.uploadContinuation);
        if (stage != null && "uploading".equals(stage.state())
                && stagingOwnedBy(c, c.uploadContinuation, hash, actor)) return true;
        var transfer=c.server.storage.transfers.transfer(c.uploadContinuation);
        return transfer!=null&&"uploading".equals(transfer.state())
            &&stagingOwnedBy(c,c.uploadContinuation,hash,actor);
    }

    static boolean hasLiveWriter(RequestContext c,String hash) {
        for(var entry:c.server.stagingWriters.entrySet()) {
            var writer=entry.getValue();
            if(!hash.equals(writer.session())||!live(c.server,writer))continue;
            var stage=c.server.storage.exchange.stage(entry.getKey());
            if(stage!=null&&"uploading".equals(stage.state()))return true;
            var transfer=c.server.storage.transfers.transfer(entry.getKey());
            if(transfer!=null&&"uploading".equals(transfer.state()))return true;
        }
        return false;
    }

    public static boolean failedStep(ServerContext server,Attempt attempt) {
        return switch(attempt.state()) {
            case "FAILED_ROLLED_BACK","CANCELLED_ROLLED_BACK","CANCEL_REQUESTED" -> true;
            case "REGISTERED" -> server.sessionTime()>=attempt.claimDeadline();
            default -> false;
        };
    }

    private static String connection(RequestContext context) {
        return ((InternalTransaction)context.tx).clientInfo().connectionId();
    }
}
