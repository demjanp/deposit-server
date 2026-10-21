package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.Headers;
import java.net.URI;
import org.junit.jupiter.api.Test;

class BrowserHttpSupportTest {
    @Test void parsesRoutesAndConstrainsReturnPathsToResourceRoutes() {
        String prefix = "/deposit";
        assertEquals("/login", BrowserHttpSupport.route(URI.create("https://example.test/deposit/login"), prefix));
        assertEquals("/__not_found", BrowserHttpSupport.route(URI.create("https://example.test/deposit-evil/login"), prefix));
        String resource = "/deposit/resources/123e4567-e89b-12d3-a456-426614174000/123e4567-e89b-12d3-a456-426614174001";
        assertEquals(resource, BrowserHttpSupport.validatedReturn("return=" + resource, prefix));
        assertEquals(prefix + "/", BrowserHttpSupport.validatedPath("https://attacker.test/", prefix));
        assertEquals(prefix + "/", BrowserHttpSupport.validatedPath("/deposit/resources/../login", prefix));
    }

    @Test void formatsSafeHtmlHeadersAndAttachmentValues() {
        assertEquals("&lt;user&gt; &amp; &#39;name&#39;", BrowserHttpSupport.escape("<user> & 'name'"));
        assertEquals("application/octet-stream", BrowserHttpSupport.safeMediaType("text/html\r\nSet-Cookie: secret"));
        String disposition = BrowserHttpSupport.attachment("report\r\n\"雪.csv");
        assertTrue(disposition.startsWith("attachment; filename=\"report____.csv\"; filename*=UTF-8''"));
        assertFalse(disposition.contains("\r")); assertFalse(disposition.contains("\n"));
        Headers headers = new Headers(); BrowserHttpSupport.secureHeaders(headers);
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertTrue(headers.getFirst("Content-Security-Policy").contains("default-src 'none'"));
        assertEquals("session=abc; Path=/app/; SameSite=Lax; HttpOnly; Secure", BrowserHttpSupport.cookieHeader("session", "abc", true, "Lax", "/app/", true));
    }
}
