package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Direct positional Cypher acceptance, deliberately independent of the server catalog/dispatch code. */
@Timeout(90)
class PreparedActionReplayIT {
    static Driver driver;
    static final Path ROOT=TestInstallationPaths.runRoot();
    static final String USER="typed-data-admin",PASSWORD="typed-data-account-password";
    String token,other,ns,name;
    static Map<String,Object> m(Object... entries){var map=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)map.put((String)entries[i],entries[i+1]);return map;}
    static List<org.neo4j.driver.Record> query(String query,Map<String,Object> parameters){try(var session=driver.session()){return session.run("CYPHER 25 "+query,parameters,TransactionConfig.builder().withTimeout(Duration.ofSeconds(45)).build()).list();}}
    static org.neo4j.driver.Record call(String procedure,Object... args){var params=new LinkedHashMap<String,Object>();var names=new ArrayList<String>();for(int i=0;i<args.length;i++){params.put("p"+i,args[i]);names.add("$p"+i);}return query("CALL "+procedure+"("+String.join(",",names)+")",params).getFirst();}
    static String login(){return call("deposit.utils.login",USER,PASSWORD).get("value").asString();}
    static String id(org.neo4j.driver.Record row){return row.get("node").asNode().get("id").asString();}
    static void error(String code,Runnable work){var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,work::run);assertTrue(failure.getMessage().contains("\"code\":\""+code+"\""),failure.getMessage());}
    static String key(){return UUID.randomUUID().toString();}
    static void release(String token){call("deposit.action.release",token);}
    static void prepare(String key,String proc,List<Object> arguments,String token){call("deposit.action.prepare",key,proc,arguments,token);}
    long sequence(){return call("deposit.get.revision",token).get("data").get("sequence").asLong();}
    long count(String label){return query("MATCH (n:`"+label+"` {namespaceId:$ns}) RETURN count(n) AS n",m("ns",ns)).getFirst().get("n").asLong();}
    @BeforeAll static void connect() throws Exception {
        driver=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")));driver.verifyConnectivity();
        Path secret=TestInstallationPaths.bootstrapSecret();if(Files.exists(secret))call("deposit.utils.bootstrap",Files.readString(secret).trim(),USER,PASSWORD);
    }
    @AfterAll static void disconnect(){driver.close();}
    @BeforeEach void setup(){token=login();name="PreparedAction-"+key();ns=id(call("deposit.create.namespace",name,m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",ns),token);other=login();call("deposit.set.activeNamespace",name,other);}
    @Test void readablePatchNativeCompositionAndExactColumns(){
        assertEquals(List.of("node"),call("deposit.create.class","Find",m(),token).keys());
        var row=call("deposit.set.class","Find",m("position",List.of(100L,200L),"order",3L,"comment","Archaeological find"),token);
        assertEquals(List.of("node"),row.keys());assertEquals(3,row.get("node").asNode().get("order").asInt());assertEquals(2,sequence());
        var data=query("CALL deposit.get.class('Find',$t) YIELD node CALL deposit.utils.toData(node,$t) YIELD data RETURN data",m("t",token)).getFirst().get("data");
        assertEquals("Find",data.get("name").asString());assertEquals(ns,data.get("namespaceId").asString());
        error("VALIDATION_FAILED",()->call("deposit.set.class","Find",m("id",key()),token));
    }
    @Test void immutableSelectorsAndUuidLookingNames(){String label=key();String clazz=id(call("deposit.create.class",label,m(),token));call("deposit.set.class",label,m("name","Renamed"),token);assertEquals(clazz,id(call("deposit.get.class",m("id",clazz),token)));error("NOT_FOUND",()->call("deposit.get.class",label,token));}
    @Test void pinnedBaselinePollingAndRefresh(){
        call("deposit.create.class","Find",m(),token);
        call("deposit.get.class","Find",other);call("deposit.get.revision",other);
        error("REVISION_CONFLICT",()->call("deposit.set.class","Find",m("comment","stale"),other));
        call("deposit.utils.refresh","namespace",other);call("deposit.set.class","Find",m("comment","fresh"),other);
        error("REVISION_CONFLICT",()->call("deposit.set.class","Find",m("comment","stale again"),token));assertEquals(2,sequence());
    }
    @Test void concurrentRefreshCannotRetargetAnExistingEditTransaction(){
        call("deposit.create.class","Find",m(),token);call("deposit.utils.refresh","namespace",other);
        try(var s=driver.session();var tx=s.beginTransaction()){
            tx.run("CALL deposit.get.class('Find',$t)",m("t",token)).consume();
            call("deposit.set.class","Find",m("comment","other view"),other);
            call("deposit.utils.refresh","namespace",token);
            error("REVISION_CONFLICT",()->tx.run("CALL deposit.set.class('Find',{comment:'old edit'},$t)",m("t",token)).consume());
        }
        assertEquals("other view",call("deposit.get.class","Find",token).get("node").asNode().get("comment").asString());
    }
    @Test void deliberateIdenticalCreatesRemainSeparateActions(){
        call("deposit.create.class","Find",m(),token);String a=id(call("deposit.create.object","Find",m(),token)),b=id(call("deposit.create.object","Find",m(),token));assertNotEquals(a,b);assertEquals(3,sequence());
    }
    @Test void preparedReplayAndReauthentication(){
        String recovery=key();prepare(recovery,"deposit.create.class",List.of("Find",m("comment","original")),token);
        var first=call("deposit.create.class","Find",m("comment","original"),token);String clazz=id(first);
        assertEquals(clazz,id(call("deposit.create.class","Find",m("comment","original"),token)));assertEquals(1,sequence());
        error("OPERATION_MISMATCH",()->call("deposit.create.class","Find",m("comment","changed"),token));release(token);
        call("deposit.set.class","Find",m("name","Renamed"),token);
        String renewed=login();call("deposit.set.activeNamespace",name,renewed);call("deposit.action.resume",recovery,renewed);
        assertEquals(clazz,id(call("deposit.create.class","Find",m("comment","original"),renewed)));release(renewed);
        assertEquals("original",call("deposit.action.status",recovery,renewed).get("data").get("outcome").get("comment").asString());
    }
    @Test void preparationResponseLossAndRequestNeverReceived(){
        String recovery=key();prepare(recovery,"deposit.create.object",List.of("Find",m()),token);prepare(recovery,"deposit.create.object",List.of("Find",m()),token);
        assertEquals(0,count("Object"));assertEquals("prepared",call("deposit.action.status",recovery,token).get("data").get("state").asString());
        call("deposit.create.object","Find",m(),token);assertEquals(1,count("Object"));release(token);
        error("OPERATION_MISMATCH",()->prepare(recovery,"deposit.create.object",List.of("Other",m()),token));
    }
    @Test void preparedRenameReplayDoesNotResolveOldName(){
        String clazz=id(call("deposit.create.class","Find",m(),token));String recovery=key();prepare(recovery,"deposit.set.class",List.of("Find",m("name","Renamed")),token);
        call("deposit.set.class","Find",m("name","Renamed"),token);assertEquals(clazz,id(call("deposit.set.class","Find",m("name","Renamed"),token)));release(token);
    }
    @Test void replayDeletedNativeHandleAndSavedPortableOutcome(){
        String recovery=key();prepare(recovery,"deposit.create.class",List.of("Find",m()),token);call("deposit.create.class","Find",m(),token);release(token);call("deposit.delete.class","Find",token);
        call("deposit.action.resume",recovery,token);assertTrue(call("deposit.create.class","Find",m(),token).get("node").isNull());assertEquals("Find",call("deposit.action.status",recovery,token).get("data").get("outcome").get("name").asString());release(token);
    }
    @Test void rollbackLeavesNoReceiptOrBaselineAdvance(){
        String recovery=key();prepare(recovery,"deposit.create.class",List.of("Find",m()),token);
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.create.class('Find',{},$t)",m("t",token)).consume();tx.rollback();}
        assertEquals(0,sequence());assertEquals("prepared",call("deposit.action.status",recovery,token).get("data").get("state").asString());call("deposit.create.class","Find",m(),token);release(token);assertEquals(1,sequence());
    }
    @Test void onePatchOneHistoryRevisionAndNoopReceipt(){
        call("deposit.create.class","Find",m(),token);call("deposit.set.class","Find",m("comment","a","order",2L,"position",List.of(2L,3L)),token);assertEquals(2,sequence());long before=count("Revision");
        call("deposit.set.class","Find",m("comment","a","order",2L),token);assertEquals(before,count("Revision"));assertEquals(2,sequence());assertFalse(call("deposit.get.lastOperation",token).get("data").get("outcome").get("changed").asBoolean());
    }
    @Test void consolidatedDeclarationPatchAndConversionRollback(){
        call("deposit.create.class","Find",m("descriptors",List.of(m("name","Mass","type","Integer"))),token);call("deposit.create.object","Find",m("descriptors",m("Mass",3L)),token);
        long before=sequence();call("deposit.set.classDescriptor",m("class","Find","name","Mass"),m("type","Float","conversion","integerToFloat","order",4L,"comment","mass"),token);assertEquals(before+1,sequence());
        error("VALIDATION_FAILED",()->call("deposit.set.classDescriptor",m("class","Find","name","Mass"),m("type","Integer","conversion","floatToIntegerExact","order","invalid"),token));assertEquals(before+1,sequence());
        var declaration=call("deposit.get.classDescriptor",m("class","Find","name","Mass"),token).get("node").asNode();assertEquals("Float",declaration.get("type").asString());assertEquals(4,declaration.get("order").asInt());
    }
    @Test void positionalBatchReferencesAndAtomicFailure(){
        var commands=List.of(m("key","class","procedure","deposit.create.class","arguments",List.of("Find",m())),m("key","object","procedure","deposit.create.object","arguments",List.of(m("ref","class","field","id"),m())));
        var outcome=call("deposit.apply.batch",commands,token).get("data");assertEquals(2,outcome.get("results").size());assertEquals(1,sequence());
        long before=count("Class");error("NOT_FOUND",()->call("deposit.apply.batch",List.of(m("key","a","procedure","deposit.create.class","arguments",List.of("Rolled back",m())),m("key","b","procedure","deposit.set.class","arguments",List.of("Absent",m("comment","bad")))),token));assertEquals(before,count("Class"));assertEquals(1,sequence());
    }
    @Test void pagesAndQueryPreserveScientificPresence(){
        call("deposit.create.class","Find",m("descriptors",List.of(m("name","Mass","type","Integer"))),token);call("deposit.create.object","Find",m("descriptors",m("Mass",null)),token);call("deposit.create.object","Find",m(),token);
        var page=call("deposit.get.objectPage",m("class","Find"),m("pageSize",1L),token);assertEquals(List.of("data"),page.keys());String cursor=page.get("data").get("nextCursor").asString();assertEquals(1,call("deposit.get.objectPage",m("class","Find"),m("pageSize",1L,"cursor",cursor),token).get("data").get("items").size());
        var query=call("deposit.get.query","SELECT Find.Mass AS mass",m(),m(),token);assertEquals(Set.of("data","columns","presence"),new HashSet<>(query.keys()));
        var rows=query("CALL deposit.get.query('SELECT Find.Mass AS mass',{}, {},$t)",m("t",token));assertEquals(Set.of(true,false),new HashSet<>(rows.stream().map(r->r.get("presence").get("mass").asBoolean()).toList()));
    }
    @Test void registeredCatalogMatchesNamesModesArgumentsAndColumns() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("runtime/public-api.json")))root=root.getParent();assertNotNull(root);
        var catalog=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("runtime/public-api.json")));
        var rows=query("SHOW PROCEDURES YIELD name,signature,mode WHERE name STARTS WITH 'deposit.' RETURN name,signature,mode",m());assertEquals(catalog.size()+1,rows.size());
        var types=Map.of("String","STRING","Object","ANY","Map<String,Object>","MAP","List<Object>","LIST<ANY>","List<String>","LIST<STRING>","List<Map<String,Object>>","LIST<MAP>","Long","INTEGER","Boolean","BOOLEAN","byte[]","BYTEARRAY");
        for(var spec:catalog){String name=spec.get("name").asText();var row=rows.stream().filter(r->r.get("name").asString().equals(name)).findFirst().orElseThrow();assertEquals(spec.get("mode").asText(),row.get("mode").asString(),name);
            var params=new ArrayList<String>();for(var arg:spec.get("args"))params.add(arg.get("name").asText()+" :: "+types.get(arg.get("type").asText()));
            if(!Set.of("deposit.utils.login","deposit.utils.bootstrap").contains(name))params.add("token :: STRING");
            String columns=switch(spec.get("output").asText()){case "node"->"node :: NODE";case "rel"->"rel :: RELATIONSHIP";case "value"->"value :: ANY";case "query"->"data :: MAP, columns :: LIST<MAP>, presence :: MAP";case "eligibility"->"value :: BOOLEAN, data :: MAP";default->"data :: MAP";};
            assertEquals(name+"("+String.join(", ",params)+") :: ("+columns+")",row.get("signature").asString().replace("?",""),name);
        }
    }
    @Test void preparedBatchBindsNamesBeforeExecution(){
        String original=id(call("deposit.create.class","Find",m(),token));String recovery=key();var commands=List.of(m("key","edit","procedure","deposit.set.class","arguments",List.of("Find",m("comment","bound"))));
        prepare(recovery,"deposit.apply.batch",List.of(commands),token);
        call("deposit.utils.refresh","namespace",other);call("deposit.set.class","Find",m("name","Renamed"),other);
        error("REVISION_CONFLICT",()->call("deposit.apply.batch",commands,token));
        assertEquals("Renamed",call("deposit.get.class",m("id",original),token).get("node").get("name").asString());
    }
    @Test void unknownFieldsAndOldEnvelopeRejected(){
        error("VALIDATION_FAILED",()->call("deposit.create.class","Find",m("operationId",key()),token));
        assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->query("CALL deposit.set.class({id:$id},{token:$t})",m("id",key(),"t",token)));
        var names=query("SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'deposit.' RETURN name",m()).stream().map(r->r.get("name").asString()).toList();assertFalse(names.contains("deposit.set.classDescriptorOrder"));assertTrue(names.contains("deposit.action.prepare"));
        var signatures=query("SHOW PROCEDURES YIELD name,signature WHERE name STARTS WITH 'deposit.' RETURN signature",m());assertTrue(signatures.stream().noneMatch(r->r.get("signature").asString().contains("meta ::")));assertTrue(signatures.stream().noneMatch(r->r.get("signature").asString().contains("context ::")));
    }
    @Test void preparedStatusWaitsForTransactionResolution() throws Exception {
        String recovery=key();prepare(recovery,"deposit.create.class",List.of("Find",m()),token);
        try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()){
            tx.run("CALL deposit.create.class('Find',{},$t)",m("t",token)).consume();var entered=new CountDownLatch(1);
            var status=pool.submit(()->{entered.countDown();return call("deposit.action.status",recovery,other);});assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->status.get(200,TimeUnit.MILLISECONDS));tx.commit();assertEquals("committed",status.get(10,TimeUnit.SECONDS).get("data").get("state").asString());
        }
    }
    @Test void generatedRequestsSupportTargetedAndSessionCancellation(){
        try(var session=driver.session();var tx=session.beginTransaction()){
            tx.run("CALL deposit.create.class('Cancelled',{},$t)",m("t",token)).consume();
            var active=query("CALL deposit.get.requests($t)",m("t",other));assertFalse(active.isEmpty());String request=active.getFirst().get("data").get("requestId").asString();UUID.fromString(request);
            assertTrue(call("deposit.utils.cancelRequest",request,other).get("value").asBoolean());assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);
        }
        assertEquals(0,count("Class"));
        try(var session=driver.session();var tx=session.beginTransaction()){
            tx.run("CALL deposit.create.class('Session cancelled',{},$t)",m("t",token)).consume();assertTrue(call("deposit.utils.cancel",token).get("value").asBoolean());assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);
        }
        assertEquals(0,sequence());
    }
    @Test void passwordRevocationAllowsAuthorizedPreparedRecoveryWithoutRepeatingRevocation(){
        String username="Prepared-password-"+key(),old="old-password-65",changed="new-password-65";
        call("deposit.create.user",username,old,m(),token);String account=call("deposit.utils.login",username,old).get("value").asString(),recovery=key();
        prepare(recovery,"deposit.set.password",List.of(old,changed),account);call("deposit.set.password",old,changed,account);error("AUTH_FAILED",()->call("deposit.get.session",account));
        String renewed=call("deposit.utils.login",username,changed).get("value").asString();call("deposit.action.resume",recovery,renewed);assertTrue(call("deposit.set.password",old,changed,renewed).get("value").asBoolean());call("deposit.get.session",renewed);
        String stored=query("MATCH (a:DepositPreparedAction {recoveryKey:$key}) RETURN properties(a) AS data",m("key",recovery)).getFirst().get("data").asMap().toString();assertFalse(stored.contains(old));assertFalse(stored.contains(changed));assertFalse(stored.contains(account));
    }
    @Test void sessionMutationOverlapRejectedUntilCommit(){
        try(var s=driver.session();var tx=s.beginTransaction()){
            tx.run("CALL deposit.create.class('Find',{},$t)",m("t",token)).consume();
            error("SESSION_BUSY",()->call("deposit.create.class","Concurrent",m(),token));tx.commit();
        }
        call("deposit.create.class","After commit",m(),token);assertEquals(2,sequence());
    }
    @Test void mixedSessionsSelectionAndSecondMutationRejected(){
        try(var s=driver.session();var tx=s.beginTransaction()){
            tx.run("CALL deposit.create.class('Find',{},$t)",m("t",token)).consume();error("VALIDATION_FAILED",()->tx.run("CALL deposit.create.class('Other',{},$t)",m("t",token)).consume());tx.rollback();
        }
        try(var s=driver.session();var tx=s.beginTransaction()){
            tx.run("CALL deposit.get.class(null,$t)",m("t",token)).consume();error("VALIDATION_FAILED",()->tx.run("CALL deposit.get.class(null,$t)",m("t",other)).consume());
        }
        assertEquals(0,count("Class"));
    }
    @Test void deletionRecoverySurvivesClearedSelection(){
        String recovery=key();prepare(recovery,"deposit.delete.namespace",List.of(name),token);call("deposit.delete.namespace",name,token);assertTrue(call("deposit.get.activeNamespace",token).get("node").isNull());
        assertTrue(call("deposit.delete.namespace",name,token).get("data").get("deletedIds").asList().contains(ns));release(token);
        String renewed=login();call("deposit.action.resume",recovery,renewed);call("deposit.delete.namespace",name,renewed);release(renewed);
    }
    @Test void bothNamespaceDeletionPreconditionsAreEnforced(){
        call("deposit.create.class","Find",m(),token);error("REVISION_CONFLICT",()->call("deposit.delete.namespace",name,other));call("deposit.utils.refresh","both",other);call("deposit.delete.namespace",name,other);assertTrue(call("deposit.get.activeNamespace",other).get("node").isNull());
    }
    @Test void authorizationIsRecheckedBeforeReplay(){
        String username="viewer-"+key();String user=call("deposit.create.user",username,PASSWORD,m(),token).get("data").get("id").asString();call("deposit.set.namespaceRole",m("id",user),"editor",token);
        String editor=call("deposit.utils.login",username,PASSWORD).get("value").asString();call("deposit.set.activeNamespace",name,editor);String recovery=key();prepare(recovery,"deposit.create.class",List.of("Find",m()),editor);call("deposit.create.class","Find",m(),editor);
        call("deposit.utils.refresh","namespace",token);call("deposit.set.namespaceRole",m("id",user),"viewer",token);error("FORBIDDEN",()->call("deposit.create.class","Find",m(),editor));
    }
    @Test void resourcePreparedChunkFinishAndReauthentication() throws Exception {
        byte[] bytes="prepared-action resource".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));String begin=key();List<Object> args=List.of("example.txt",(long)bytes.length,hash,"text/plain");prepare(begin,"deposit.resource.beginUpload",args,token);
        String upload=call("deposit.resource.beginUpload",args.get(0),args.get(1),args.get(2),args.get(3),token).get("data").get("uploadId").asString();release(token);
        String chunk=key();prepare(chunk,"deposit.resource.uploadChunk",List.of(upload,0L,Base64.getEncoder().encodeToString(bytes),hash),token);call("deposit.resource.uploadChunk",upload,0L,Base64.getEncoder().encodeToString(bytes),hash,token);release(token);
        String renewed=login();call("deposit.set.activeNamespace",name,renewed);call("deposit.action.resume",begin,renewed);assertEquals(bytes.length,call("deposit.resource.beginUpload",args.get(0),args.get(1),args.get(2),args.get(3),renewed).get("data").get("nextOffset").asLong());release(renewed);
        String finish=key();prepare(finish,"deposit.resource.finishUpload",List.of(upload),renewed);String resource=call("deposit.resource.finishUpload",upload,renewed).get("data").get("resourceId").asString();assertEquals(resource,call("deposit.resource.finishUpload",upload,renewed).get("data").get("resourceId").asString());release(renewed);assertEquals(0,sequence());
    }
}
