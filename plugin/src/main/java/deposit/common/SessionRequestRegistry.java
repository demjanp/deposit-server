package deposit.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.neo4j.kernel.api.KernelTransaction;

/** Sole owner of per-database sessions, active requests, session time and login throttling. */
final class SessionRequestRegistry {
    private record ActiveRequest(String actor, ServerContext.TxState state) {}

    final ConcurrentMap<String, ServerContext.Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActiveRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Failures> failures = new ConcurrentHashMap<>();
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    long sessionTime() { return clock.getAsLong(); }
    void clock(java.util.function.LongSupplier value) { clock = Objects.requireNonNull(value); }

    void track(RequestContext context) {
        ActiveRequest request = new ActiveRequest(context.actorId, context.state);
        ActiveRequest old = requests.putIfAbsent(context.requestId, request);
        if (old != null && !old.equals(request)) context.fail("VALIDATION_FAILED", "requestId is already active");
        context.state.requestIds.add(context.requestId);
    }

    ServerContext.Session authenticateBolt(RequestContext context, String principal) {
        if (context.token == null || context.token.isEmpty()) context.fail("AUTH_REQUIRED", "Session token required");
        String hash = Json.hash(context.token);
        ServerContext.Session session = sessions.get(hash);
        if (session == null || session.transport != ServerContext.Transport.BOLT || !Objects.equals(session.principal, principal))
            context.fail("AUTH_FAILED", "Invalid session");
        synchronized (session) {
            long now = sessionTime();
            if (now - session.touched >= 1_800_000L || now - session.created >= 28_800_000L) {
                sessions.remove(hash, session);
                context.fail("SESSION_EXPIRED", "Session expired");
            }
            session.touched = now;
        }
        bind(context, hash, session);
        return session;
    }

    ServerContext.Session authenticateBrowser(RequestContext context) {
        if (context.token == null || context.token.isEmpty()) context.fail("AUTH_REQUIRED", "Browser session required");
        String hash = Json.hash(context.token);
        ServerContext.Session session = sessions.get(hash);
        if (session == null || session.transport != ServerContext.Transport.BROWSER) context.fail("AUTH_FAILED", "Invalid browser session");
        synchronized (session) {
            long now = sessionTime();
            if (now - session.touched >= 1_800_000L || now - session.created >= 28_800_000L) {
                sessions.remove(hash, session);
                context.fail("SESSION_EXPIRED", "Browser session expired");
            }
        }
        bind(context, hash, session);
        return session;
    }

    private void bind(RequestContext context, String hash, ServerContext.Session session) {
        if (context.state.tokenHash != null && !context.state.tokenHash.equals(hash)) context.fail("VALIDATION_FAILED", "Cannot mix sessions in a transaction");
        context.state.tokenHash = hash;
        context.session = session;
        context.actorId = session.actor;
        track(context);
    }

    boolean touchBrowserSession(String token, ServerContext.Session expected) {
        String hash = Json.hash(token);
        if (sessions.get(hash) != expected || expected.transport != ServerContext.Transport.BROWSER) return false;
        synchronized (expected) {
            long now = sessionTime();
            if (sessions.get(hash) != expected || now - expected.touched >= 1_800_000L || now - expected.created >= 28_800_000L) {
                sessions.remove(hash, expected);
                return false;
            }
            expected.touched = now;
            return true;
        }
    }

    boolean cancel(String requestId, String actor, boolean administrator) {
        ActiveRequest request = requests.get(requestId);
        if (request == null) return false;
        if (!administrator && !request.actor().equals(actor)) throw ExceptionHandler.fail("FORBIDDEN", "Cannot cancel another actor's request");
        request.state().kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);
        return true;
    }

    List<Map<String,Object>> activeRequests(String actor, boolean administrator, ServerContext.TxState caller) {
        return requests.entrySet().stream().filter(e -> e.getValue().state() != caller && (administrator || e.getValue().actor().equals(actor)))
            .map(e -> Json.map("requestId", e.getKey(), "actorId", e.getValue().actor(), "namespaceId", e.getValue().state().namespace)).toList();
    }

    boolean cancelSession(String tokenHash, ServerContext.TxState caller) {
        boolean cancelled = false;
        for (ActiveRequest request : requests.values()) if (request.state() != caller && Objects.equals(tokenHash, request.state().tokenHash)) {
            request.state().kernel.markForTermination(org.neo4j.kernel.api.exceptions.Status.Transaction.Terminated);
            cancelled = true;
        }
        return cancelled;
    }

    void completeRequests(ServerContext.TxState state) {
        for (String requestId : state.requestIds) requests.computeIfPresent(requestId, (key, value) -> value.state() == state ? null : value);
    }

    void revokeUser(String actor) { sessions.entrySet().removeIf(e -> e.getValue().actor.equals(actor)); }
    void demoteUser(String actor) { sessions.values().stream().filter(s -> s.actor.equals(actor)).forEach(s -> s.administrative = false); }
    void clearNamespace(String namespace) {
        sessions.values().forEach(s -> { synchronized (s) { if (namespace.equals(s.namespace)) s.namespace = null; } });
    }

    void checkLoginDelay(String username, String origin, KernelTransaction tx) {
        long delay = Math.max(delay("user:" + username), delay("origin:" + origin));
        long until = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < until) {
            tx.assertOpen();
            try { Thread.sleep(Math.min(100, until - System.currentTimeMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ExceptionHandler.fail("CANCELLED", "Login interrupted"); }
        }
    }
    private long delay(String key) { Failures value = failures.get(key); return value == null ? 0 : value.delay(); }
    void loginFailed(String username, String origin) {
        for (String key : List.of("user:" + username, "origin:" + origin)) failures.computeIfAbsent(key, k -> new Failures()).add();
    }

    private static final class Failures {
        private final Deque<Long> times = new ArrayDeque<>();
        synchronized void add() { prune(); times.addLast(System.currentTimeMillis()); }
        synchronized long delay() { prune(); return times.size() < 5 ? 0 : Math.min(5000, 250L << Math.min(5, times.size() - 5)); }
        private void prune() { while (!times.isEmpty() && times.getFirst() < System.currentTimeMillis() - 300_000) times.removeFirst(); }
    }
}
