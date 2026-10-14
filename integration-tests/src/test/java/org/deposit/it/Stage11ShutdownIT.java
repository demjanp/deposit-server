package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Coordinates a pending Deposit transaction and a live resource stream with the external service gate. */
@Timeout(180)
class Stage11ShutdownIT {
    static final Path ROOT=Path.of("C:/data_processed/Deposit_v2/stage0/runs/stage0-host");
    static final Path READY=ROOT.resolve("secrets/stage11-drain-ready.txt");
    static final Path RELEASE=ROOT.resolve("secrets/stage11-drain-release.txt");
    static final Path FIXTURE=ROOT.resolve("secrets/stage11-drain-fixture.properties");
    static org.neo4j.driver.Driver driver;
    static URI base;
    static final String PASSWORD=Objects.requireNonNull(System.getenv("STAGE0_PASSWORD"));
    static final String URI_VALUE=Objects.requireNonNull(System.getenv("STAGE0_URI"));

    @BeforeAll static void connect()throws Exception{
        base=URI.create(Objects.requireNonNull(System.getenv("STAGE0_HTTP_BASE")));
        driver=GraphDatabase.driver(URI_VALUE,AuthTokens.basic("neo4j",PASSWORD),Config.builder().withConnectionTimeout(5,TimeUnit.SECONDS).build());
        driver.verifyConnectivity();
    }
    @AfterAll static void close(){if(driver!=null)driver.close();}
    static Map<String,Object> m(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    static org.neo4j.driver.Record call(String procedure,Object... args){var params=new LinkedHashMap<String,Object>();var names=new ArrayList<String>();for(int i=0;i<args.length;i++){params.put("p"+i,args[i]);names.add("$p"+i);}try(var session=driver.session()){return session.run("CYPHER 25 CALL "+procedure+"("+String.join(",",names)+")",params).list().getFirst();}}
    static String id(org.neo4j.driver.Record row){return row.get("node").asNode().get("id").asString();}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String upload(byte[] bytes,String filename,String token)throws Exception{
        String upload=call("deposit.resource.beginUpload",filename,(long)bytes.length,hash(bytes),"application/octet-stream",token).get("data").get("uploadId").asString();
        for(int offset=0;offset<bytes.length;offset+=1_048_576){byte[] part=Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+1_048_576));call("deposit.resource.uploadChunk",upload,(long)offset,Base64.getEncoder().encodeToString(part),hash(part),token);}
        return call("deposit.resource.finishUpload",upload,token).get("data").get("resourceId").asString();
    }
    static String csrf(String html){var matcher=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(html);assertTrue(matcher.find());return matcher.group(1);}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->java.net.URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}

    @Test void holdTransactionAndDownloadUntilShutdownSignalsRelease()throws Exception{
        Files.deleteIfExists(READY);Files.deleteIfExists(RELEASE);Files.deleteIfExists(FIXTURE);
        String token=call("deposit.utils.login","stage1-admin","stage1-account-password").get("value").asString();
        String namespace=id(call("deposit.create.namespace","Stage11Drain-"+UUID.randomUUID(),m("historyMode","disabled"),token));
        call("deposit.set.activeNamespace",m("id",namespace),token);
        String type="Stage11DrainFile"+UUID.randomUUID().toString().replace("-","");
        call("deposit.create.class",type,m("descriptors",List.of(m("name","File","type","DResource"))),token);
        byte[] bytes=new byte[32*1024*1024];new Random(11711).nextBytes(bytes);String resource=upload(bytes,"stage11-drain.bin",token);
        call("deposit.create.object",type,m("descriptors",m("File",m("resourceId",resource))),token);
        String download=call("deposit.resource.info",resource,token).get("data").get("downloadUrl").asString();
        HttpClient browser=Stage10CoreIT.client();URI login=URI.create(base.toASCIIString()+"/login");
        HttpResponse<String> page=browser.send(HttpRequest.newBuilder(login).GET().build(),HttpResponse.BodyHandlers.ofString());String csrf=csrf(page.body());
        var signedIn=browser.send(HttpRequest.newBuilder(login).header("Origin",base.getScheme()+"://"+base.getAuthority()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username","stage1-admin","password","stage1-account-password","csrf",csrf,"return","/")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,signedIn.statusCode());
        HttpResponse<InputStream> response=browser.send(HttpRequest.newBuilder(URI.create(download)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());
        InputStream stream=response.body();byte[] first=stream.readNBytes(1024*1024);assertEquals(1024*1024,first.length,"the resource stream must remain active while shutdown starts");
        String writeId=UUID.randomUUID().toString();
        try(var session=driver.session();var tx=session.beginTransaction(TransactionConfig.builder().withTimeout(Duration.ofSeconds(90)).build())){
            tx.run("CYPHER 25 CALL deposit.utils.login($username,$password)",Map.of("username","stage1-admin","password","stage1-account-password")).consume();
            tx.run("CREATE (:Stage11ShutdownDrain {id:$id})",Map.of("id",writeId)).consume();
            Properties fixture=new Properties();fixture.setProperty("writeId",writeId);fixture.setProperty("namespace",namespace);fixture.setProperty("resource",resource);
            try(var output=Files.newOutputStream(FIXTURE,StandardOpenOption.CREATE_NEW)){fixture.store(output,"Stage 11 service drain fixture");}
            Files.writeString(READY,"ready\n",StandardOpenOption.CREATE_NEW);
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(!Files.exists(RELEASE)&&System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(Files.exists(RELEASE),"the gate must start service shutdown and release the held work");
            stream.close();tx.commit();
        } finally {try{stream.close();}catch(Exception ignored){}}
    }

    @Test void verifyCommittedTransactionSurvivedServiceShutdown()throws Exception{
        Properties fixture=new Properties();try(var input=Files.newInputStream(FIXTURE)){fixture.load(input);}
        try(var session=driver.session()){
            long count=session.run("MATCH (n:Stage11ShutdownDrain {id:$id}) RETURN count(n) AS n",Map.of("id",fixture.getProperty("writeId"))).single().get("n").asLong();
            assertEquals(1,count,"the already admitted write must commit before storage closes");
        }
    }
}
