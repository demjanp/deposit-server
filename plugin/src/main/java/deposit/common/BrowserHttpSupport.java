package deposit.common;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Value-based browser request parsing, response formatting and bounded transfer support. */
final class BrowserHttpSupport {
    private static final int MAX_FORM_BYTES = 16 * 1024;
    private static final int CHUNK_BYTES = 1024 * 1024;

    private BrowserHttpSupport() { }

    static String route(URI uri, String prefix) {
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(prefix)) return "/__not_found";
        String route = path.substring(prefix.length());
        if (route.isEmpty()) return "/";
        return route.startsWith("/") ? route : "/__not_found";
    }

    static Map<String, String> form(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) throw new IllegalArgumentException("Form required");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (bytes.length > MAX_FORM_BYTES) throw new IllegalArgumentException("Form too large");
        Map<String, String> result = new HashMap<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate form field");
        }
        return result;
    }

    static String validatedReturn(String query, String prefix) {
        if (query == null) return prefix + "/";
        for (String item : query.split("&")) {
            int eq = item.indexOf('=');
            if (eq >= 0 && decode(item.substring(0, eq)).equals("return")) return validatedPath(decode(item.substring(eq + 1)), prefix);
        }
        return prefix + "/";
    }

    static String validatedPath(String value, String prefix) {
        if (value == null || value.length() > 2048 || !value.startsWith(prefix + "/resources/") || value.startsWith("//") || value.contains("\\") || value.contains("\r") || value.contains("\n")) return prefix + "/";
        try {
            URI path = URI.create(value);
            if (path.isAbsolute() || path.getRawAuthority() != null || path.getRawQuery() != null || path.getRawFragment() != null) return prefix + "/";
            String[] parts = path.getRawPath().substring((prefix + "/resources/").length()).split("/", -1);
            return parts.length == 2 ? prefix + "/resources/" + canonical(parts[0]) + "/" + canonical(parts[1]) : prefix + "/";
        } catch (Exception e) { return prefix + "/"; }
    }

    static boolean sameOrigin(Headers headers, String origin) { return origin.equals(headers.getFirst("Origin")); }

    static String cookie(Headers headers, String name) {
        String value = null;
        for (String header : headers.getOrDefault("Cookie", List.of())) for (String item : header.split(";")) {
            String[] pair = item.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) { if (value != null) return null; value = pair[1]; }
        }
        return value;
    }

    static String cookieHeader(String name, String value, boolean httpOnly, String sameSite, String cookiePath, boolean secure) {
        return name + "=" + value + "; Path=" + cookiePath + "; SameSite=" + sameSite + (httpOnly ? "; HttpOnly" : "") + (secure ? "; Secure" : "");
    }

    static String clearCookie(String name, boolean httpOnly, String sameSite, String cookiePath, boolean secure) {
        return name + "=; Path=" + cookiePath + "; Max-Age=0; SameSite=" + sameSite + (httpOnly ? "; HttpOnly" : "") + (secure ? "; Secure" : "");
    }

    static void secureHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff"); headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Referrer-Policy", "same-origin"); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    static void sendText(HttpExchange exchange, int status, String message) {
        try {
            byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length)); exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        } catch (IOException ignored) { }
    }

    static void methodNotAllowed(HttpExchange exchange, String allowed) {
        exchange.getResponseHeaders().set("Allow", allowed); sendText(exchange, 405, "Method not allowed");
    }

    static void redirect(HttpExchange exchange, String target) throws IOException {
        exchange.getResponseHeaders().set("Location", target); exchange.sendResponseHeaders(303, -1);
    }

    static String safeMessage(int status) {
        return switch (status) { case 404 -> "Not found"; case 409 -> "Resource is not ready"; case 503 -> "Resource unavailable"; case 401 -> "Authentication required"; case 403 -> "Request rejected"; default -> "Request failed"; };
    }

    static String safeMediaType(String media) {
        return media != null && media.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:\\s*;\\s*charset=[A-Za-z0-9._-]+)?") ? media : "application/octet-stream";
    }

    static String attachment(String filename) {
        String raw = filename == null ? "download" : filename;
        String safe = raw.replaceAll("[\\r\\n\\\"\\\\]", "_"); String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    static String canonical(String id) { try { return RequestContext.uuid(id); } catch (Exception e) { throw new IllegalArgumentException("Invalid resource path"); } }
    static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static boolean constantEquals(String left, String right) { return left != null && right != null && java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII)); }
    static String trimSlashes(String path) { int end = path.length(); while (end > 0 && path.charAt(end - 1) == '/') end--; return path.substring(0, end); }
    static void closeExchange(HttpExchange exchange) { try { exchange.close(); } catch (Exception ignored) { } }

    static void transfer(HttpExchange exchange, InputStream input, String mediaType, String filename, String checksum,
                         long size, long offset, long length, boolean partial, BooleanSupplier authorized) throws IOException {
        Headers headers = exchange.getResponseHeaders(); headers.set("Content-Type", safeMediaType(mediaType)); headers.set("Content-Length", Long.toString(length));
        headers.set("Content-Disposition", attachment(filename)); headers.set("Cache-Control", "private, no-store"); headers.set("ETag", "\"" + checksum + "\""); headers.set("Connection", "close");
        if (partial) headers.set("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + size);
        exchange.sendResponseHeaders(partial ? 206 : 200, length == 0 ? -1 : length);
        OutputStream out = exchange.getResponseBody(); boolean complete = false;
        try {
            byte[] buffer = new byte[CHUNK_BYTES]; long sent = 0;
            while (sent < length) {
                if (!authorized.getAsBoolean()) return;
                int allowed = (int)Math.min(buffer.length, length - sent); int count = input.readNBytes(buffer, 0, allowed);
                if (count <= 0) throw new IOException("Resource stream ended early");
                out.write(buffer, 0, count); out.flush(); sent += count;
            }
            if (input.read() != -1) throw new IOException("Resource stream exceeded its declared length");
            out.flush(); complete = true;
        } finally {
            if (!complete) closeExchange(exchange);
            try { out.close(); } catch (IOException ignored) { }
            if (complete) closeExchange(exchange);
        }
    }
}
