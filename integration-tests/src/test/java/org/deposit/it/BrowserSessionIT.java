package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** installed listener, browser-cookie and DResource-link acceptance. */
@Timeout(180)
class BrowserSessionIT {
    static final String USER="typed-data-admin",PASSWORD="typed-data-account-password";
    static Driver driver;static URI base;static HttpClient http;
    String token,namespace;
    static Map<String,Object> m(Object... values){return TestTransport.orderedMap(values);}
    static org.neo4j.driver.Record call(String name,Object... args){return TestTransport.call(driver,Duration.ofSeconds(60),name,args);}
    static List<org.neo4j.driver.Record> query(String cypher,Map<String,Object> params){try(var session=driver.session()){return session.run(cypher,params).list();}}
    static long serverRevision(String value){return call("deposit.utils.login",USER,PASSWORD).get("meta").get("revision").asLong();}
    static long namespaceRevision(String value){return call("deposit.get.activeNamespace",value).get("meta").get("revision").asLong();}
    static Map<String,Object> ctx(String token){return m("token",token,"requestId",UUID.randomUUID().toString());}
    static Map<String,Object> write(String token,long revision){return m("token",token,"requestId",UUID.randomUUID().toString(),"operationId",UUID.randomUUID().toString(),"expectedRevision",revision);}
    static String dataId(org.neo4j.driver.Record row){return row.get("data").get("id").asString();}
    static String nodeId(org.neo4j.driver.Record row){return row.get("node").asNode().get("id").asString();}
    static String login(String username,String password){return call("deposit.utils.login",username,password).get("value").asString();}
    String upload(byte[] bytes,String filename){String checksum=hash(bytes);String upload=call("deposit.resource.beginUpload",filename,(long)bytes.length,checksum,"application/octet-stream",token).get("data").get("uploadId").asString();for(int offset=0;offset<bytes.length;offset+=1_048_576){byte[] part=Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+1_048_576));call("deposit.resource.uploadChunk",upload,(long)offset,Base64.getEncoder().encodeToString(part),hash(part),token);}return call("deposit.resource.finishUpload",upload,token).get("data").get("resourceId").asString();}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new AssertionError(e);}}
    static Path awaitDownload(Path directory,String expectedHash,WebDriver browser,String loginDiagnostics)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline){
            try(var files=Files.list(directory)){
                for(Path file:files.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().endsWith(".part")).toList()){
                    if(hash(Files.readAllBytes(file)).equals(expectedHash))return file;
                }
            }
            Thread.sleep(100);
        }
        String pageText="";String currentUrl="unavailable";try{currentUrl=browser.getCurrentUrl();pageText=browser.findElement(By.tagName("body")).getText();}catch(Exception ignored){}
        List<String> files;try(var stream=Files.list(directory)){files=stream.map(p->p.getFileName()+" ("+safeFileSize(p)+" bytes)").toList();}
        fail("Firefox did not finish the expected browser download; page="+pageText+"; current URL="+currentUrl+"; files="+files+"; "+loginDiagnostics);return null;
    }
    private static long safeFileSize(Path file){try{return Files.size(file);}catch(IOException ignored){return -1;}}
    static String text(String resource,long bytes,String actor){return new String(content(resource,bytes,actor),StandardCharsets.UTF_8);}
    static byte[] content(String resource,long bytes,String actor){try(var out=new ByteArrayOutputStream()){for(long offset=0;offset<bytes;){long length=Math.min(1_048_576L,bytes-offset);byte[] part=Base64.getDecoder().decode(call("deposit.resource.downloadChunk",resource,offset,length,actor).get("data").get("bytesBase64").asString());out.write(part);offset+=part.length;}return out.toByteArray();}catch(IOException e){throw new AssertionError(e);}}
    static HttpClient client(){return TestTransport.cookieClient();}
    static URI uri(String path){return TestTransport.endpoint(base,path);}
    static String origin(){return TestTransport.origin(base);}
    static HttpResponse<String> get(HttpClient client,URI uri)throws Exception{return client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());}
    static String form(Map<String,?> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(String.valueOf(e.getValue()),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    static String csrf(String html){Matcher matcher=Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(html);assertTrue(matcher.find(),"CSRF form token missing");return matcher.group(1);}

    @BeforeAll static void connect()throws Exception{
        base=URI.create(Objects.requireNonNull(System.getenv("DEPOSIT_TEST_HTTP_BASE"),"Installed download listener URL missing"));
        http=client();driver=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")));driver.verifyConnectivity();
        Path bootstrap=TestInstallationPaths.bootstrapSecret();if(Files.exists(bootstrap))call("deposit.utils.bootstrap",Files.readString(bootstrap).trim(),USER,PASSWORD);
    }
    @AfterAll static void close(){if(driver!=null)driver.close();}
    @BeforeEach void setup(){token=login(USER,PASSWORD);namespace=nodeId(call("deposit.create.namespace","Browser-"+UUID.randomUUID(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",namespace),token);}

    @Test void browserLoginReusesCookieStreamsExactBytesAndSupportsRangesAndCsrfLogout()throws Exception{
        byte[] bytes="the exact browser attachment bytes".getBytes(StandardCharsets.UTF_8);String resource=upload(bytes,"payload.txt");var info=call("deposit.resource.info",resource,token).get("data");
        String url=info.get("downloadUrl").asString();assertEquals(base+"/resources/"+namespace+"/"+resource,url);URI resourceUri=URI.create(url);
        HttpResponse<String> unauthenticated=get(client(),resourceUri);assertEquals(303,unauthenticated.statusCode());assertEquals("no-referrer",unauthenticated.headers().firstValue("Referrer-Policy").orElseThrow());assertTrue(unauthenticated.headers().firstValue("Location").orElseThrow().startsWith(base.getRawPath()+"/login?return="));
        HttpClient browser=client();URI loginUri=uri("/login");HttpResponse<String> page=get(browser,loginUri);assertEquals(200,page.statusCode());assertEquals("same-origin",page.headers().firstValue("Referrer-Policy").orElseThrow());assertTrue(page.body().contains("Deposit login"));String loginCsrf=csrf(page.body());
        for(String rejectedOrigin:Arrays.asList(null,"null","https://evil.example")){
            HttpRequest.Builder rejected=HttpRequest.newBuilder(loginUri).header("Content-Type","application/x-www-form-urlencoded");if(rejectedOrigin!=null)rejected.header("Origin",rejectedOrigin);
            HttpResponse<String> rejectedResponse=browser.send(rejected.POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",loginCsrf,"return",resourceUri.getRawPath())))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,rejectedResponse.statusCode(),"Login must reject missing, null, and foreign Origin headers");assertEquals("Request rejected\n",rejectedResponse.body());
        }
        HttpResponse<String> wrongCsrf=browser.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",loginCsrf+"mismatch","return",resourceUri.getRawPath())))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,wrongCsrf.statusCode(),"Login must reject a mismatched CSRF field");
        HttpResponse<String> login=browser.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",loginCsrf,"return","https://evil.example/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,login.statusCode());assertEquals(base.getRawPath()+"/",login.headers().firstValue("Location").orElseThrow(),"External return paths must be rejected");
        String expectedCookiePath=base.getRawPath();if(expectedCookiePath==null||expectedCookiePath.isEmpty())expectedCookiePath="/";else if(!expectedCookiePath.endsWith("/"))expectedCookiePath+="/";
        String cookiePath=expectedCookiePath;assertTrue(login.headers().allValues("Set-Cookie").stream().anyMatch(value->value.startsWith("Deposit-Session=")&&value.contains("HttpOnly")&&value.contains("SameSite=Lax")&&value.contains("Path="+cookiePath)));
        HttpResponse<String> signedIn=get(browser,loginUri);assertEquals(200,signedIn.statusCode());assertEquals("same-origin",signedIn.headers().firstValue("Referrer-Policy").orElseThrow());assertTrue(signedIn.body().contains("Signed in as"));String sessionCsrf=csrf(signedIn.body());
        HttpResponse<byte[]> full=browser.send(HttpRequest.newBuilder(resourceUri).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,full.statusCode());assertEquals("no-referrer",full.headers().firstValue("Referrer-Policy").orElseThrow());assertArrayEquals(bytes,full.body());assertEquals("\""+info.get("checksum").asString()+"\"",full.headers().firstValue("ETag").orElseThrow());assertTrue(full.headers().firstValue("Content-Disposition").orElseThrow().contains("filename=\"payload.txt\""));assertEquals("private, no-store",full.headers().firstValue("Cache-Control").orElseThrow());
        HttpResponse<byte[]> range=browser.send(HttpRequest.newBuilder(resourceUri).header("Range","bytes=4-10").GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(206,range.statusCode());assertArrayEquals(Arrays.copyOfRange(bytes,4,11),range.body());assertEquals("bytes 4-10/"+bytes.length,range.headers().firstValue("Content-Range").orElseThrow());
        HttpResponse<String> invalidRange=browser.send(HttpRequest.newBuilder(resourceUri).header("Range","bytes=0-1,4-5").GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(416,invalidRange.statusCode());assertEquals("bytes */"+bytes.length,invalidRange.headers().firstValue("Content-Range").orElseThrow());
        HttpResponse<String> csrfRejected=browser.send(HttpRequest.newBuilder(uri("/logout")).header("Origin","https://evil.example").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("csrf",sessionCsrf)))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,csrfRejected.statusCode());
        HttpResponse<String> logout=browser.send(HttpRequest.newBuilder(uri("/logout")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("csrf",sessionCsrf)))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(303,logout.statusCode());assertEquals(303,get(browser,resourceUri).statusCode());
        String renewedCsrf=csrf(get(browser,loginUri).body());HttpResponse<String> renewed=browser.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",renewedCsrf,"return",resourceUri.getRawPath())))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(303,renewed.statusCode());assertArrayEquals(bytes,browser.send(HttpRequest.newBuilder(resourceUri).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body(),"A renewed browser session can reuse the stable exported URL");
    }

    @Test void browserAndBoltTokensAreTransportBoundAndAccountDisableRevokesBoth()throws Exception{
        byte[] bytes="transport binding".getBytes(StandardCharsets.UTF_8);String resource=upload(bytes,"bound.txt");String url=call("deposit.resource.info",resource,token).get("data").get("downloadUrl").asString();
        HttpResponse<String> wrongTransport=client().send(HttpRequest.newBuilder(URI.create(url)).header("Cookie","Deposit-Session="+token).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(303,wrongTransport.statusCode());
        HttpClient browser=client();HttpResponse<String> loginPage=get(browser,uri("/login"));String browserCsrf=csrf(loginPage.body());browser.send(HttpRequest.newBuilder(uri("/login")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",browserCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        String browserToken=((CookieManager)browser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("Deposit-Session")).findFirst().orElseThrow().getValue();
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.resource.info",resource,browserToken));assertTrue(failure.getMessage().contains("AUTH_FAILED"),failure.getMessage());
        String username="browser-revoke-"+UUID.randomUUID(),password="browser-revoke-account-password";String userId=dataId(call("deposit.create.user",username,password,m(),token));String boltToken=login(username,password);
        HttpClient userBrowser=client();String loginCsrf=csrf(get(userBrowser,uri("/login")).body());userBrowser.send(HttpRequest.newBuilder(uri("/login")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",username,"password",password,"csrf",loginCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        String userBrowserToken=((CookieManager)userBrowser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("Deposit-Session")).findFirst().orElseThrow().getValue();
        call("deposit.set.user",m("id",userId),m("enabled",false),token);
        assertTrue(assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.get.activeNamespace",boltToken)).getMessage().contains("AUTH_FAILED"));
        assertEquals(303,get(userBrowser,URI.create(url)).statusCode());
        assertFalse(userBrowserToken.isBlank());
    }

    @Test void browserAdministrativeModeIsExplicitSessionLocalAndClearedOnDemotion()throws Exception{
        String username="browser-admin-"+UUID.randomUUID();String userId=dataId(call("deposit.create.user",username,"browser-admin-account-password",m("serverRole","administrator"),token));
        String boltToken=login(username,"browser-admin-account-password");HttpClient browser=client();URI loginUri=uri("/login");String loginCsrf=csrf(get(browser,loginUri).body());
        browser.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",username,"password","browser-admin-account-password","csrf",loginCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        String cookie=((CookieManager)browser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("Deposit-Session")).findFirst().orElseThrow().getValue();
        HttpResponse<String> account=get(browser,loginUri);String csrf=csrf(account.body());assertTrue(account.body().contains("currently disabled"));
        HttpResponse<String> enabled=browser.send(HttpRequest.newBuilder(uri("/administrative-mode")).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("csrf",csrf,"enabled","true")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,enabled.statusCode());assertTrue(enabled.body().contains("currently enabled"));
        assertFalse(call("deposit.get.administrativeMode",boltToken).get("value").asBoolean(),"Browser mode must not mutate the separate Bolt session");
        call("deposit.set.user",m("id",userId),m("serverRole","user"),token);
        HttpResponse<String> demoted=get(browser,loginUri);assertEquals(200,demoted.statusCode());assertTrue(demoted.body().contains("Signed in as"));assertFalse(demoted.body().contains("administrative mode"));assertFalse(demoted.body().contains("currently enabled"));
        assertTrue(cookie.length()>=40);
    }

    @Test void creatorBoundExportGrantDoesNotBecomeBrowserAccessForAnotherNamespaceMember()throws Exception{
        String username="browser-viewer-"+UUID.randomUUID(),password="browser-viewer-account-password";String userId=dataId(call("deposit.create.user",username,password,m(),token));
        call("deposit.set.namespaceRole",m("id",userId),"viewer",token);
        call("deposit.create.class","PrivateExport",m("descriptors",List.of(m("name","Value","type","String"))),token);call("deposit.create.object","PrivateExport",m("descriptors",m("Value","private export")),token);
        var exported=call("deposit.save.csv",m("query","SELECT PrivateExport.Value"),m(),m("filename","private.csv"),token).get("data");String resource=exported.get("resourceId").asString();
        String url=call("deposit.resource.info",resource,token).get("data").get("downloadUrl").asString();
        HttpClient creator=client();URI loginUri=uri("/login");String loginCsrf=csrf(get(creator,loginUri).body());creator.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",USER,"password",PASSWORD,"csrf",loginCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,creator.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpClient viewer=client();String viewerCsrf=csrf(get(viewer,loginUri).body());viewer.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",username,"password",password,"csrf",viewerCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,viewer.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String otherNamespace=nodeId(call("deposit.create.namespace","BrowserOther-"+UUID.randomUUID(),m("historyMode","disabled"),token));
        assertEquals(404,creator.send(HttpRequest.newBuilder(uri("/resources/"+otherNamespace+"/"+resource)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void namespaceRevocationStopsAnAlreadyStreamingDownloadBetweenChunks()throws Exception{
        String username="browser-stream-viewer-"+UUID.randomUUID(),password="browser-stream-account-password";String userId=dataId(call("deposit.create.user",username,password,m(),token));
        call("deposit.set.namespaceRole",m("id",userId),"viewer",token);
        String type="StreamFile"+UUID.randomUUID().toString().replace("-","");call("deposit.create.class",type,m("descriptors",List.of(m("name","File","type","DResource"))),token);
        byte[] bytes=new byte[32*1024*1024];new Random(17).nextBytes(bytes);String resource=upload(bytes,"large.bin");call("deposit.create.object",type,m("descriptors",m("File",m("resourceId",resource))),token);
        HttpClient viewer=client();URI loginUri=uri("/login");String csrf=csrf(get(viewer,loginUri).body());viewer.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",username,"password",password,"csrf",csrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        String url=base+"/resources/"+namespace+"/"+resource;HttpResponse<InputStream> response=viewer.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());
        InputStream input=response.body();byte[] firstChunk=input.readNBytes(1024*1024);assertEquals(1024*1024,firstChunk.length,"The first bounded transport chunk must be delivered before revocation");
        call("deposit.set.namespaceRole",m("id",userId),null,token);
        long received=firstChunk.length;try(var readers=Executors.newSingleThreadExecutor()){Future<Long> body=readers.submit(()->{long count=0;byte[] buffer=new byte[64*1024];while(true){try{int n=input.read(buffer);if(n<0)return count;count+=n;}catch(IOException interrupted){return count;}}});try{received+=body.get(20,TimeUnit.SECONDS);}catch(TimeoutException timeout){body.cancel(true);try{input.close();}catch(IOException ignored){}fail("Revoked streaming response did not close promptly");return;}finally{try{input.close();}catch(IOException ignored){}}}
        assertTrue(received<bytes.length,"Access revocation must terminate a response before all bytes are delivered; received "+received);
    }

    @Test void browserHistoryAccessHonorsClosedEpochRetentionAndTransientExpiry()throws Exception{
        String username="browser-history-viewer-"+UUID.randomUUID(),password="browser-history-account-password";String userId=dataId(call("deposit.create.user",username,password,m(),token));
        String historicalNs=nodeId(call("deposit.create.namespace","BrowserPersistentHistory-"+UUID.randomUUID(),m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",historicalNs),token);
        String type="HistoryFile"+UUID.randomUUID().toString().replace("-","");call("deposit.create.class",type,m("descriptors",List.of(m("name","File","type","DResource"))),token);
        byte[] historicalBytes="retained by a closed persistent history epoch".getBytes(StandardCharsets.UTF_8);String historicalResource=upload(historicalBytes,"history.txt");String historicalObject=nodeId(call("deposit.create.object",type,m("descriptors",m("File",m("resourceId",historicalResource))),token));
        call("deposit.delete.object",historicalObject,token);call("deposit.set.versioningType","disabled",token);call("deposit.set.namespaceRole",m("id",userId),"viewer",token);
        query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",historicalResource));
        String viewerToken=login(username,password);call("deposit.set.activeNamespace",m("id",historicalNs),viewerToken);String historicalUrl=call("deposit.resource.info",historicalResource,viewerToken).get("data").get("downloadUrl").asString();
        String transientNs=nodeId(call("deposit.create.namespace","BrowserTransientHistory-"+UUID.randomUUID(),m("historyMode","transient"),token));call("deposit.set.activeNamespace",m("id",transientNs),token);
        String transientType="TransientFile"+UUID.randomUUID().toString().replace("-","");call("deposit.create.class",transientType,m("descriptors",List.of(m("name","File","type","DResource"))),token);
        String transientResource=upload("expires with transient history".getBytes(StandardCharsets.UTF_8),"transient.txt");String transientObject=nodeId(call("deposit.create.object",transientType,m("descriptors",m("File",m("resourceId",transientResource))),token));
        call("deposit.delete.object",transientObject,token);call("deposit.set.versioningType","disabled",token);call("deposit.set.namespaceRole",m("id",userId),"viewer",token);
        query("MATCH (e:DepositHistoryEpoch {namespaceId:$ns}) SET e.expired=true",m("ns",transientNs));query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",transientResource));
        HttpClient browser=client();URI loginUri=uri("/login");String browserCsrf=csrf(get(browser,loginUri).body());browser.send(HttpRequest.newBuilder(loginUri).header("Origin",origin()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(m("username",username,"password",password,"csrf",browserCsrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        HttpResponse<byte[]> retained=browser.send(HttpRequest.newBuilder(URI.create(historicalUrl)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,retained.statusCode());assertArrayEquals(historicalBytes,retained.body());
        assertEquals(404,browser.send(HttpRequest.newBuilder(uri("/resources/"+transientNs+"/"+transientResource)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode(),"Expired transient history does not authorize a browser download");
    }

    @Test void csvXlsxAndGeoPackageRenderLinksWithoutGrantingTheReferencedResource()throws Exception{
        String source=upload("linked resource".getBytes(StandardCharsets.UTF_8),"source [file].txt");call("deposit.create.class","FileRecord",m("descriptors",List.of(m("name","File","type","DResource"))),token);call("deposit.create.object","FileRecord",m("descriptors",m("File",m("resourceId",source))),token);
        long sourceGrants=query("MATCH (g:DepositExportGrant {resourceId:$id}) RETURN count(g) AS n",m("id",source)).getFirst().get("n").asLong();String expected="[source \\[file\\].txt]("+base+"/resources/"+namespace+"/"+source+")";
        var csv=call("deposit.save.csv",m("query","SELECT FileRecord.File"),m(),m("filename","links.csv"),token).get("data");String csvContent=text(csv.get("resourceId").asString(),csv.get("bytes").asLong(),token);assertEquals("FileRecord.File\r\n"+expected+"\r\n",csvContent);assertFalse(csvContent.contains(token));
        var xlsx=call("deposit.save.xlsx",m("query","SELECT FileRecord.File"),m(),m("filename","links.xlsx"),token).get("data");byte[] workbookBytes=content(xlsx.get("resourceId").asString(),xlsx.get("bytes").asLong(),token);try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))){var cell=workbook.getSheetAt(0).getRow(1).getCell(0);assertEquals("source [file].txt",cell.getStringCellValue());assertEquals(base+"/resources/"+namespace+"/"+source,cell.getHyperlink().getAddress());assertFalse(cell.getHyperlink().getAddress().contains(token));}
        var gpkg=call("deposit.save.gpkg",m("query","SELECT FileRecord.File"),m(),m("filename","links.gpkg","layer","resources"),token).get("data");byte[] packageBytes=content(gpkg.get("resourceId").asString(),gpkg.get("bytes").asLong(),token);Path path=Files.createTempFile("browser-links-",".gpkg");try{Files.write(path,packageBytes);try(Connection database=DriverManager.getConnection("jdbc:sqlite:"+path);PreparedStatement statement=database.prepareStatement("SELECT * FROM resources");ResultSet row=statement.executeQuery()){assertTrue(row.next());boolean linkFound=false;for(int i=1;i<=row.getMetaData().getColumnCount();i++){String value=row.getString(i);if(expected.equals(value)){linkFound=true;assertFalse(value.contains(token));}}assertTrue(linkFound,"GeoPackage attribute must contain the authenticated DResource link");}}finally{Files.deleteIfExists(path);}
        assertEquals(sourceGrants,query("MATCH (g:DepositExportGrant {resourceId:$id}) RETURN count(g) AS n",m("id",source)).getFirst().get("n").asLong(),"Link rendering must not grant access to the linked resource");

        Path downloadDirectory=Files.createTempDirectory("browser-browser-");WebDriver browser=null;
        try{
            FirefoxOptions options=new FirefoxOptions();String browserPath=System.getenv("SE_BROWSER_PATH");if(browserPath!=null&&!browserPath.isBlank())options.setBinary(browserPath);options.addArguments("-headless");
            options.addPreference("browser.download.folderList",2);options.addPreference("browser.download.dir",downloadDirectory.toAbsolutePath().toString());options.addPreference("browser.download.useDownloadDir",true);
            options.addPreference("browser.download.manager.showWhenStarting",false);options.addPreference("browser.helperApps.neverAsk.saveToDisk","application/octet-stream,text/plain,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/geopackage+sqlite3");options.addPreference("pdfjs.disabled",true);
            browser=new FirefoxDriver(options);browser.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            URI linkedResource=URI.create(base+"/resources/"+namespace+"/"+source);URI exportedCsv=URI.create(call("deposit.resource.info",csv.get("resourceId").asString(),token).get("data").get("downloadUrl").asString());
            browser.get(linkedResource.toString());
            String renderedOrigin=String.valueOf(((JavascriptExecutor)browser).executeScript("return location.origin"));String formCsrf=browser.findElement(By.name("csrf")).getAttribute("value");Cookie csrfCookie=browser.manage().getCookieNamed("Deposit-Login-Csrf");boolean csrfCookieMatches=csrfCookie!=null&&Objects.equals(formCsrf,csrfCookie.getValue());
            String loginDiagnostics="pageOriginMatches="+Objects.equals(renderedOrigin,origin())+"; csrfCookiePresent="+(csrfCookie!=null)+"; csrfCookieMatchesForm="+csrfCookieMatches+"; csrfCookieSameSite="+(csrfCookie==null?"missing":csrfCookie.getSameSite());
            browser.findElement(By.name("username")).sendKeys(USER);browser.findElement(By.name("password")).sendKeys(PASSWORD);browser.findElement(By.cssSelector("form button")).click();
            Path firstDownload=awaitDownload(downloadDirectory,hash("linked resource".getBytes(StandardCharsets.UTF_8)),browser,loginDiagnostics);
            browser.get(base+"/login");new WebDriverWait(browser,Duration.ofSeconds(15)).until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(),'Signed in as typed-data-admin')]")));
            ((JavascriptExecutor)browser).executeScript("const a=document.createElement('a');a.href=arguments[0];a.hidden=true;document.body.appendChild(a);a.click();a.remove();",exportedCsv.toString());
            Path secondDownload=awaitDownload(downloadDirectory,hash(content(csv.get("resourceId").asString(),csv.get("bytes").asLong(),token)),browser,loginDiagnostics);
            browser.findElement(By.xpath("//button[normalize-space()='Log out']")).click();new WebDriverWait(browser,Duration.ofSeconds(15)).until(ExpectedConditions.visibilityOfElementLocated(By.name("username")));
        }finally{
            if(browser!=null)browser.quit();
            try(var paths=Files.walk(downloadDirectory)){for(Path entry:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(entry);}
        }
    }
}
