package deposit.common;

import com.sun.net.httpserver.*;
import deposit.service.BrowserDownloadService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Narrow authenticated browser transport and sole owner of listener admission/drain. */
public final class ResourceDownloadServer implements AutoCloseable {
    private static final String SESSION_COOKIE = "Deposit-Session", LOGIN_CSRF_COOKIE = "Deposit-Login-Csrf";
    private static final System.Logger LOG = System.getLogger(ResourceDownloadServer.class.getName());
    private final BrowserDownloadService service;
    private final URI publicBase;
    private final String prefix, cookiePath, origin;
    private final boolean secure;
    private HttpServer listener;
    private ExecutorService executor;
    private volatile boolean stopping;
    private final Object drainMonitor = new Object();
    private final Set<HttpExchange> activeExchanges = ConcurrentHashMap.newKeySet();
    private final Set<BrowserDownloadService.ResourceRead> activeReads = ConcurrentHashMap.newKeySet();

    public ResourceDownloadServer(ServerContext server, GraphDatabaseAPI database) throws Exception {
        service = new BrowserDownloadService(server, database);
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(System.getProperty("deposit.config")))) { config.load(in); }
        InetAddress address = InetAddress.getByName(config.getProperty("download.listenAddress"));
        int port = Integer.parseInt(config.getProperty("download.port"));
        InetSocketAddress bindAddress = new InetSocketAddress(address, port);
        publicBase = URI.create(config.getProperty("download.publicBaseUrl")); secure = "https".equalsIgnoreCase(publicBase.getScheme());
        prefix = publicBase.getRawPath() == null ? "" : BrowserHttpSupport.trimSlashes(publicBase.getRawPath());
        cookiePath = prefix.isEmpty() ? "/" : prefix + "/";
        int defaultPort = secure ? 443 : 80; String host = publicBase.getHost().contains(":") ? "[" + publicBase.getHost() + "]" : publicBase.getHost();
        origin = publicBase.getScheme() + "://" + host + (publicBase.getPort() < 0 || publicBase.getPort() == defaultPort ? "" : ":" + publicBase.getPort());
        if (secure && config.containsKey("download.tlsKeyStore")) listener = createHttps(config);
        else listener = HttpServer.create(bindAddress, 64);
        listener.createContext("/", this::handle);
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("deposit-download-http-", 0).factory()); listener.setExecutor(executor);
    }

    public String publicBaseUrl() { return publicBase.toASCIIString(); }
    public void start() { if (stopping) throw new IllegalStateException("Download listener is stopping"); listener.start(); }

    private static HttpsServer createHttps(Properties config) throws Exception {
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getByName(config.getProperty("download.listenAddress")), Integer.parseInt(config.getProperty("download.port"))), 64);
        char[] password = Files.readString(Path.of(config.getProperty("download.tlsKeyStorePasswordFile")), StandardCharsets.UTF_8).strip().toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = Files.newInputStream(Path.of(config.getProperty("download.tlsKeyStore")))) { store.load(in, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(store, password);
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null); https.setHttpsConfigurator(new HttpsConfigurator(ssl)); return https;
        } finally { Arrays.fill(password, '\0'); }
    }

    private void handle(HttpExchange exchange) {
        String diagnostic = UUID.randomUUID().toString();
        boolean accepted;
        synchronized (drainMonitor) { accepted = !stopping; if (accepted) activeExchanges.add(exchange); }
        if (!accepted) { BrowserHttpSupport.sendText(exchange, 503, "Download listener is stopping"); BrowserHttpSupport.closeExchange(exchange); return; }
        try {
            if (stopping) { BrowserHttpSupport.sendText(exchange, 503, "Download listener is stopping"); return; }
            BrowserHttpSupport.secureHeaders(exchange.getResponseHeaders());
            String route = BrowserHttpSupport.route(exchange.getRequestURI(), prefix), method = exchange.getRequestMethod();
            if (route.startsWith("/resources/")) { if (!method.equals("GET")) { BrowserHttpSupport.methodNotAllowed(exchange, "GET"); return; } resource(exchange, route); return; }
            switch (route) {
                case "/login" -> { if (method.equals("GET")) loginPage(exchange); else if (method.equals("POST")) login(exchange); else BrowserHttpSupport.methodNotAllowed(exchange, "GET, POST"); }
                case "/logout" -> { if (!method.equals("POST")) { BrowserHttpSupport.methodNotAllowed(exchange, "POST"); return; } logout(exchange); }
                case "/administrative-mode" -> { if (!method.equals("POST")) { BrowserHttpSupport.methodNotAllowed(exchange, "POST"); return; } administrative(exchange); }
                default -> BrowserHttpSupport.sendText(exchange, 404, "Not found");
            }
        } catch (BrowserDownloadService.DownloadFailure e) {
            if (e.status == 416) { exchange.getResponseHeaders().set("Content-Range", "bytes */" + e.rangeSize); BrowserHttpSupport.sendText(exchange, 416, "Range not satisfiable"); }
            else BrowserHttpSupport.sendText(exchange, e.status, BrowserHttpSupport.safeMessage(e.status));
        } catch (IllegalArgumentException e) { BrowserHttpSupport.sendText(exchange, 400, "Invalid request"); }
        catch (IOException e) { LOG.log(System.Logger.Level.WARNING, "Download request ended before completion; reference {0}", diagnostic); BrowserHttpSupport.closeExchange(exchange); }
        catch (Throwable e) { LOG.log(System.Logger.Level.ERROR, "Download request failed; reference {0}", diagnostic); BrowserHttpSupport.sendText(exchange, 500, "Request failed; reference " + diagnostic); }
        finally {
            BrowserHttpSupport.closeExchange(exchange); activeExchanges.remove(exchange);
            synchronized (drainMonitor) { drainMonitor.notifyAll(); }
        }
    }

    private void resource(HttpExchange exchange, String route) throws IOException {
        String[] parts = route.substring("/resources/".length()).split("/", -1);
        if (parts.length != 2) { BrowserHttpSupport.sendText(exchange, 404, "Not found"); return; }
        String namespace = BrowserHttpSupport.canonical(parts[0]), resource = BrowserHttpSupport.canonical(parts[1]);
        String token = BrowserHttpSupport.cookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token == null) { redirectLogin(exchange, absoluteRoute(route)); return; }
        BrowserDownloadService.ResourceRead read;
        try { read = service.openResource(token, namespace, resource, exchange.getRequestHeaders().getFirst("Range")); }
        catch (BrowserDownloadService.DownloadFailure failure) {
            if (failure.status == 401) { clearSession(exchange); redirectLogin(exchange, absoluteRoute(route)); return; }
            if (failure.status == 416) exchange.getResponseHeaders().set("Content-Range", "bytes */" + failure.rangeSize);
            throw failure;
        }
        // The resource pin is acquired before opening bytes; admission and stop share one linearization lock.
        boolean admitted = admitRead(read);
        if (!admitted) { read.close(); BrowserHttpSupport.sendText(exchange, 503, "Download listener is stopping"); return; }
        try (read) {
            BrowserHttpSupport.transfer(exchange, read.stream, read.mediaType, read.filename, read.checksum, read.size,
                    read.offset, read.length, read.partial, () -> !stopping && service.reauthorize(token, namespace, resource));
        } finally {
            activeReads.remove(read); synchronized (drainMonitor) { drainMonitor.notifyAll(); }
        }
    }

    private String absoluteRoute(String route) { return prefix + route; }

    boolean admitRead(BrowserDownloadService.ResourceRead read) {
        synchronized (drainMonitor) { if (stopping) return false; activeReads.add(read); return true; }
    }

    private void loginPage(HttpExchange exchange) throws IOException {
        String returnPath = BrowserHttpSupport.validatedReturn(exchange.getRequestURI().getRawQuery(), prefix);
        String token = BrowserHttpSupport.cookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token != null) try { var account = service.account(token); BrowserHttpSupport.sendHtml(exchange, 200, accountPage(account)); return; }
        catch (BrowserDownloadService.DownloadFailure ignored) { clearSession(exchange); }
        String csrf = ServerContext.randomToken(); exchange.getResponseHeaders().add("Set-Cookie", cookieHeader(LOGIN_CSRF_COOKIE, csrf, false, "Strict"));
        BrowserHttpSupport.sendHtml(exchange, 200, loginForm(returnPath, csrf, false));
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!BrowserHttpSupport.sameOrigin(exchange.getRequestHeaders(), origin)) { BrowserHttpSupport.sendText(exchange, 403, "Request rejected"); return; }
        Map<String, String> form = BrowserHttpSupport.form(exchange);
        String csrfCookie = BrowserHttpSupport.cookie(exchange.getRequestHeaders(), LOGIN_CSRF_COOKIE);
        if (!BrowserHttpSupport.constantEquals(csrfCookie, form.get("csrf"))) { BrowserHttpSupport.sendText(exchange, 403, "Request rejected"); return; }
        String returnPath = BrowserHttpSupport.validatedPath(form.get("return"), prefix);
        try {
            String token = service.login(form.get("username"), form.get("password"), origin, exchange.getRemoteAddress() == null ? null : exchange.getRemoteAddress().getAddress().getHostAddress()).token();
            service.account(token);
            Headers headers = exchange.getResponseHeaders(); headers.add("Set-Cookie", cookieHeader(SESSION_COOKIE, token, true, "Lax")); headers.add("Set-Cookie", clearCookie(LOGIN_CSRF_COOKIE, false, "Strict"));
            BrowserHttpSupport.redirect(exchange, returnPath);
        } catch (BrowserDownloadService.DownloadFailure failure) {
            String csrf = ServerContext.randomToken(); exchange.getResponseHeaders().add("Set-Cookie", cookieHeader(LOGIN_CSRF_COOKIE, csrf, false, "Strict"));
            BrowserHttpSupport.sendHtml(exchange, 200, loginForm(returnPath, csrf, true));
        }
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (!BrowserHttpSupport.sameOrigin(exchange.getRequestHeaders(), origin)) { BrowserHttpSupport.sendText(exchange, 403, "Request rejected"); return; }
        String token = BrowserHttpSupport.cookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token == null) { clearSession(exchange); BrowserHttpSupport.redirect(exchange, prefix + "/login"); return; }
        Map<String, String> form = BrowserHttpSupport.form(exchange); service.logout(token, form.get("csrf")); clearSession(exchange); BrowserHttpSupport.redirect(exchange, prefix + "/login");
    }

    private void administrative(HttpExchange exchange) throws IOException {
        if (!BrowserHttpSupport.sameOrigin(exchange.getRequestHeaders(), origin)) { BrowserHttpSupport.sendText(exchange, 403, "Request rejected"); return; }
        String token = BrowserHttpSupport.cookie(exchange.getRequestHeaders(), SESSION_COOKIE);
        if (token == null) { BrowserHttpSupport.sendText(exchange, 401, "Authentication required"); return; }
        Map<String, String> form = BrowserHttpSupport.form(exchange); String enabled = form.get("enabled");
        if (!"true".equals(enabled) && !"false".equals(enabled)) { BrowserHttpSupport.sendText(exchange, 400, "Invalid request"); return; }
        var account = service.administrativeMode(token, form.get("csrf"), Boolean.parseBoolean(enabled)); BrowserHttpSupport.sendHtml(exchange, 200, accountPage(account));
    }

    private String accountPage(BrowserDownloadService.Account account) {
        String state = account.administrative() ? "enabled" : "disabled", toggle = account.administrative() ? "false" : "true";
        String action = account.administrative() ? "Disable" : "Enable";
        String admin = account.role().equals("administrator") ? "<form method=\"post\" action=\"" + prefix + "/administrative-mode\"><input type=\"hidden\" name=\"csrf\" value=\"" + BrowserHttpSupport.escape(account.csrfToken()) + "\"><input type=\"hidden\" name=\"enabled\" value=\"" + toggle + "\"><button>" + action + " administrative mode (currently " + state + ")</button></form>" : "";
        return page("Deposit downloads", "<h1>Deposit downloads</h1><p>Signed in as " + BrowserHttpSupport.escape(account.username()) + ".</p>" + admin + "<form method=\"post\" action=\"" + prefix + "/logout\"><input type=\"hidden\" name=\"csrf\" value=\"" + BrowserHttpSupport.escape(account.csrfToken()) + "\"><button>Log out</button></form>");
    }

    private String loginForm(String returnPath, String csrf, boolean failed) {
        return page("Deposit login", "<h1>Deposit login</h1>" + (failed ? "<p role=\"alert\">Login failed. Check your username and password.</p>" : "") + "<form method=\"post\" action=\"" + prefix + "/login\"><input type=\"hidden\" name=\"csrf\" value=\"" + BrowserHttpSupport.escape(csrf) + "\"><input type=\"hidden\" name=\"return\" value=\"" + BrowserHttpSupport.escape(returnPath) + "\"><label>Username <input name=\"username\" autocomplete=\"username\" required></label><label>Password <input type=\"password\" name=\"password\" autocomplete=\"current-password\" required></label><button>Sign in</button></form>");
    }

    private String page(String title, String body) { return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + BrowserHttpSupport.escape(title) + "</title></head><body>" + body + "</body></html>"; }
    private String cookieHeader(String name, String value, boolean httpOnly, String sameSite) { return BrowserHttpSupport.cookieHeader(name, value, httpOnly, sameSite, cookiePath, secure); }
    private String clearCookie(String name, boolean httpOnly, String sameSite) { return BrowserHttpSupport.clearCookie(name, httpOnly, sameSite, cookiePath, secure); }
    private void clearSession(HttpExchange exchange) { exchange.getResponseHeaders().add("Set-Cookie", clearCookie(SESSION_COOKIE, true, "Lax")); exchange.getResponseHeaders().add("Set-Cookie", clearCookie(LOGIN_CSRF_COOKIE, false, "Strict")); }
    private void redirectLogin(HttpExchange exchange, String returnPath) throws IOException { clearSession(exchange); BrowserHttpSupport.redirect(exchange, prefix + "/login?return=" + URLEncoder.encode(returnPath, StandardCharsets.UTF_8)); }

    public void stopAccepting() { synchronized (drainMonitor) { if (stopping) return; stopping = true; } if (listener != null) listener.stop(0); }
    int activeReaderCount() { return activeReads.size(); }
    int activeHandlerCount() { return activeExchanges.size(); }

    @Override public void close() {
        stopAccepting();
        for (var read : activeReads) try { read.close(); } catch (IOException e) { LOG.log(System.Logger.Level.WARNING, "Unable to close active resource stream during shutdown"); }
        for (HttpExchange exchange : activeExchanges) BrowserHttpSupport.closeExchange(exchange);
        if (executor == null) return;
        executor.shutdown(); if (awaitHandlers(30, TimeUnit.SECONDS)) return;
        for (var read : activeReads) try { read.close(); } catch (IOException ignored) { }
        for (HttpExchange exchange : activeExchanges) BrowserHttpSupport.closeExchange(exchange);
        executor.shutdownNow(); if (awaitHandlers(30, TimeUnit.SECONDS)) return;
        throw new IllegalStateException("Download handlers did not drain; resource storage remains open");
    }

    private boolean awaitHandlers(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (drainMonitor) {
            while (!activeExchanges.isEmpty() || !activeReads.isEmpty()) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        try { return executor.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
