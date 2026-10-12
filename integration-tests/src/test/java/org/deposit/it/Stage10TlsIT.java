package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Direct-TLS listener and Secure browser-cookie acceptance. */
@Timeout(60)
class Stage10TlsIT {
    static URI base=URI.create(Objects.requireNonNull(System.getenv("STAGE0_HTTP_BASE")));
    static URI uri(String path){return URI.create(base.toASCIIString()+path);}
    static String origin(){int port=base.getPort();return base.getScheme()+"://"+base.getHost()+(port<0||port==443?"":":"+port);}
    static String csrf(String html){var matcher=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(html);assertTrue(matcher.find());return matcher.group(1);}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    static HttpClient client()throws Exception{
        TrustManager[] trust={new X509TrustManager(){public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a){}public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a){}}};
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,trust,new SecureRandom());return HttpClient.newBuilder().sslContext(ssl).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test void directHttpsSetsSecureScopedCookieAndReusesTheSession()throws Exception{
        assertEquals("https",base.getScheme());assertNotEquals("localhost",base.getHost());HttpClient browser=client();URI login=uri("/login");
        HttpResponse<String> page=browser.send(HttpRequest.newBuilder(login).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,page.statusCode());assertEquals("same-origin",page.headers().firstValue("Referrer-Policy").orElseThrow());String csrf=csrf(page.body());
        HttpResponse<String> signedIn=browser.send(HttpRequest.newBuilder(login).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username","stage1-admin","password","stage1-account-password","csrf",csrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,signedIn.statusCode());assertTrue(signedIn.headers().allValues("Set-Cookie").stream().anyMatch(value->value.startsWith("Deposit-Session=")&&value.contains("HttpOnly")&&value.contains("SameSite=Lax")&&value.contains("Secure")));
        assertTrue(signedIn.headers().allValues("Set-Cookie").stream().anyMatch(value->value.startsWith("Deposit-Session=")&&value.contains("Path=/stage10-proxy/")));
        HttpResponse<String> reused=browser.send(HttpRequest.newBuilder(login).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,reused.statusCode());assertEquals("same-origin",reused.headers().firstValue("Referrer-Policy").orElseThrow());assertTrue(reused.body().contains("Signed in as stage1-admin"));
    }
}
