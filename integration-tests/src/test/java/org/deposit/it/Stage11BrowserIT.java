package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage10CoreIT.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Focused installed-listener failures that complement the browser transport corpus. */
@Timeout(120)
class Stage11BrowserIT {
    Stage10CoreIT fixture;
    @BeforeAll static void open()throws Exception{Stage10CoreIT.connect();}
    @AfterAll static void close(){Stage10CoreIT.close();}
    @BeforeEach void setup(){fixture=new Stage10CoreIT();fixture.setup();}
    HttpResponse<String> signIn(HttpClient browser,String user,String password)throws Exception{
        String csrf=csrf(get(browser,uri("/login")).body());
        return browser.send(HttpRequest.newBuilder(uri("/login")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(m("username",user,"password",password,"csrf",csrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
    }
    URI resourceUrl(String id){return URI.create(call("deposit.resource.info",id,fixture.token).get("data").get("downloadUrl").asString());}

    @Test void invalidLoginAndPasswordResetRejectOldBrowserSession()throws Exception{
        byte[] bytes="password reset protected resource".getBytes(StandardCharsets.UTF_8);
        String resource=fixture.upload(bytes,"reset.txt");
        call("deposit.create.class","File",m("descriptors",List.of(m("name","Content","type","DResource"))),fixture.token);
        call("deposit.create.object","File",m("descriptors",m("Content",m("resourceId",resource))),fixture.token);
        String username="Stage11Browser-"+UUID.randomUUID(),password="stage11-browser-password",next="stage11-new-browser-password";
        String user=dataId(call("deposit.create.user",username,password,m(),fixture.token));
        call("deposit.set.namespaceRole",m("id",user),"viewer",fixture.token);
        HttpClient browser=client();
        var rejected=signIn(browser,username,"incorrect-password");
        assertEquals(200,rejected.statusCode());assertTrue(rejected.body().contains("Login failed"));
        assertEquals(303,get(browser,resourceUrl(resource)).statusCode(),"Invalid credentials must not establish an authenticated session");
        assertEquals(303,signIn(browser,username,password).statusCode());
        URI url=resourceUrl(resource);
        assertArrayEquals(bytes,browser.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body());
        call("deposit.utils.refresh","server",fixture.token);
        call("deposit.set.user",m("id",user),m("newPassword",next),fixture.token);
        assertEquals(303,get(browser,url).statusCode(),"Password reset must revoke the prior browser session");
        assertEquals(303,signIn(browser,username,next).statusCode());
        assertArrayEquals(bytes,browser.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body());
    }

    @Test void corruptContentReturnsUnavailableAndClientCancellationDoesNotBlockLaterReads()throws Exception{
        byte[] bytes=new byte[4*1024*1024];new Random(11).nextBytes(bytes);
        String resource=fixture.upload(bytes,"cancelled.bin");URI url=resourceUrl(resource);HttpClient browser=client();
        assertEquals(303,signIn(browser,USER,PASSWORD).statusCode());
        var partial=browser.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,partial.statusCode());try(var stream=partial.body()){assertEquals(Byte.toUnsignedInt(bytes[0]),stream.read());}
        var range=browser.send(HttpRequest.newBuilder(url).header("Range","bytes=0-31").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,range.statusCode());assertArrayEquals(Arrays.copyOf(bytes,32),range.body());
        String key=hash(resource.getBytes(StandardCharsets.UTF_8));
        Path inventory=Path.of("D:/data_processed/Deposit_v2/stage0/runs/stage0-host/resources/ocfl",key.substring(0,3),key.substring(3,6),key.substring(6,9),key,"inventory.json");
        byte[] original=Files.readAllBytes(inventory);
        try{Files.writeString(inventory,"corrupt inventory");assertEquals(503,get(browser,url).statusCode());}
        finally{Files.write(inventory,original);}
        var recovered=browser.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,recovered.statusCode());assertArrayEquals(bytes,recovered.body());
    }

    @Test void probeExpiredBrowserSessionRequiresNewAuthentication()throws Exception{
        String resource=fixture.upload("browser expiry".getBytes(StandardCharsets.UTF_8),"expiry.txt");URI url=resourceUrl(resource);
        HttpClient browser=client();assertEquals(303,signIn(browser,USER,PASSWORD).statusCode());
        try{call("stage0.security.clock",1_800_001L);assertEquals(303,get(browser,url).statusCode());}
        finally{call("stage0.security.clock",0L);}
        assertEquals(303,signIn(browser,USER,PASSWORD).statusCode());assertEquals(200,get(browser,url).statusCode());
    }
}
