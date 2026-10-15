package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

/** Browser-session fixture split across an installed runtime restart. */
@Timeout(120)
class Stage10RestartIT {
    static final Path SESSION=Path.of("D:/data_processed/Deposit_v2/stage0/runs/stage0-host/secrets/stage10-browser-session.properties");
    static URI base;
    static org.neo4j.driver.Driver driver;
    static HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();}
    static URI uri(String path){return URI.create(base.toASCIIString()+path);}
    static String origin(){int port=base.getPort(),defaultPort="https".equals(base.getScheme())?443:80;return base.getScheme()+"://"+base.getHost()+(port<0||port==defaultPort?"":":"+port);}
    static String csrf(String html){var matcher=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(html);assertTrue(matcher.find());return matcher.group(1);}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    @BeforeAll static void connect()throws Exception{Stage10CoreIT.connect();driver=Stage10CoreIT.driver;base=Stage10CoreIT.base;}
    @AfterAll static void close(){Stage10CoreIT.close();}

    @Test void seed()throws Exception {
        String token=Stage10CoreIT.login(Stage10CoreIT.USER,Stage10CoreIT.PASSWORD);
        String namespace=Stage10CoreIT.nodeId(Stage10CoreIT.call("deposit.create.namespace","Stage10Restart-"+UUID.randomUUID(),Stage10CoreIT.m("historyMode","disabled"),token));
        Stage10CoreIT.call("deposit.set.activeNamespace",Stage10CoreIT.m("id",namespace),token);
        Stage10CoreIT helper=new Stage10CoreIT();helper.token=token;String resource=helper.upload("restart protected attachment".getBytes(StandardCharsets.UTF_8),"restart.txt");
        String url=Stage10CoreIT.call("deposit.resource.info",resource,token).get("data").get("downloadUrl").asString();
        HttpClient browser=client();HttpResponse<String> page=browser.send(HttpRequest.newBuilder(uri("/login")).GET().build(),HttpResponse.BodyHandlers.ofString());String csrf=csrf(page.body());
        HttpResponse<String> signedIn=browser.send(HttpRequest.newBuilder(uri("/login")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username",Stage10CoreIT.USER,"password",Stage10CoreIT.PASSWORD,"csrf",csrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(303,signedIn.statusCode());
        String session=browser.cookieHandler().orElseThrow() instanceof CookieManager cookies?cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("Deposit-Session")).findFirst().orElseThrow().getValue():null;
        assertNotNull(session);Properties properties=new Properties();properties.setProperty("session",session);properties.setProperty("url",url);
        try(var output=Files.newOutputStream(SESSION)){properties.store(output,"Stage 10 protected browser restart fixture");}
    }

    @Test void verify()throws Exception {
        Properties properties=new Properties();try(var input=Files.newInputStream(SESSION)){properties.load(input);}
        HttpResponse<String> response=client().send(HttpRequest.newBuilder(URI.create(properties.getProperty("url"))).header("Cookie","Deposit-Session="+properties.getProperty("session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,response.statusCode());assertTrue(response.headers().firstValue("Location").orElseThrow().startsWith(base.getRawPath()+"/login?return="));
        assertTrue(response.headers().allValues("Set-Cookie").stream().anyMatch(value->value.startsWith("Deposit-Session=")&&value.contains("Max-Age=0")));
        HttpClient renewed=client();URI login=uri("/login");
        String loginCsrf=csrf(renewed.send(HttpRequest.newBuilder(login).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> signedIn=renewed.send(HttpRequest.newBuilder(login).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username",Stage10CoreIT.USER,"password",Stage10CoreIT.PASSWORD,"csrf",loginCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,signedIn.statusCode());
        HttpResponse<byte[]> recovered=renewed.send(HttpRequest.newBuilder(URI.create(properties.getProperty("url"))).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,recovered.statusCode());
        assertArrayEquals("restart protected attachment".getBytes(StandardCharsets.UTF_8),recovered.body(),"A renewed session must download the original resource from its pre-restart URL");
        assertTrue(recovered.headers().firstValue("Content-Disposition").orElseThrow().contains("filename=\"restart.txt\""));
    }
}
