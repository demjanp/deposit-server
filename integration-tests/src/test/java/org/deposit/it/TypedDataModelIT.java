package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Public API corpus. Expected values and graph assertions are independent of plugin code. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(90)
class TypedDataModelIT {
    static String token,second,namespace,otherNamespace,specimen,contextClass,s1,s2,s3,typedObject,typedClass;
    static long sequence;
    static Map<String,Object> receiptContext,receiptRequest;
    static String receiptObject;
    static final Properties recovery=new Properties();
    @BeforeAll static void setup() {
        AuthenticationNamespaceLifecycleIT.connect();
        ensureAccount();
        var login=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD));token=login.get("value").asString();
        second=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD)).get("value").asString();
        String name="Core-"+UUID.randomUUID();var nsContext=write(token,revision(login));
        var ns=call("deposit.create.namespace",m("name",name,"historyMode","disabled"),nsContext);namespace=id(ns);
        recovery.setProperty("namespaceName",name);recovery.setProperty("serverOperation",(String)nsContext.get("operationId"));recovery.setProperty("serverExpected",nsContext.get("expectedRevision").toString());
        otherNamespace=id(call("deposit.create.namespace",m("name","Other-"+UUID.randomUUID(),"historyMode","disabled"),write(token,revision(ns))));
        select(token,namespace);select(second,namespace);sequence=0;
    }
    @AfterAll static void shutdown() {AuthenticationNamespaceLifecycleIT.close();}
    static void select(String token,String id) {call("deposit.set.activeNamespace",m("id",id),ctx(token));}
    static org.neo4j.driver.Record mutate(String procedure,Map<String,Object> request) {var row=call(procedure,request,write(token,sequence));sequence=revision(row);return row;}
    static org.neo4j.driver.Record get(String procedure,Map<String,Object> request) {return call(procedure,request,ctx(token));}
    static long count(String label) {return query("MATCH (n:`"+label+"` {namespaceId:$ns}) RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asLong();}
    static Map<String,Object> state() {return query("MATCH (n:Namespace {id:$ns}) RETURN properties(n) AS n",m("ns",namespace)).getFirst().get("n").asMap();}
    static Map<String,Object> typed(String type,Object value) {return m("type",type,"value",value);}

    @Test @Order(1) void seedSemanticFixtureAndValidateNativeHandles() {
        specimen=id(mutate("deposit.create.class",m("name","Specimen","descriptors",List.of(m("name","Mass","type","Integer")))));
        contextClass=id(mutate("deposit.create.class",m("name","Context")));
        String c1=id(mutate("deposit.create.object",m("classId",contextClass))),c2=id(mutate("deposit.create.object",m("classId",contextClass)));
        s1=id(mutate("deposit.create.object",m("classId",specimen,"descriptors",m("Mass",10L))));s2=id(mutate("deposit.create.object",m("className","Specimen","descriptors",m("Mass",20L))));s3=id(mutate("deposit.create.object",m("classId",specimen)));
        var row=query("CALL deposit.get.object({classId:$class},$c) YIELD node MATCH (node)-[:MEMBER_OF]->(cl:Class) OPTIONAL MATCH (node)-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN count(node) AS n,sum(d.value) AS total,collect(DISTINCT cl.id) AS classes",m("class",specimen,"c",ctx(token))).getFirst();
        assertEquals(3,row.get("n").asInt());assertEquals(30,row.get("total").asInt());assertEquals(List.of(specimen),row.get("classes").asList());
        assertEquals(specimen,id(get("deposit.get.objectClass",m("objectId",s1))));
        var descriptor=get("deposit.get.descriptor",m("objectId",s1,"name","Mass"));assertEquals(s1,descriptor.get("data").get("objectId").asString());assertFalse(descriptor.get("data").asMap().containsKey("type"));
        assertEquals(s1,id(get("deposit.get.objectByDescriptorId",m("descriptorId",id(descriptor)))));
        assertEquals(0,query("MATCH (n:DepositLive {namespaceId:$ns}) WHERE n.kind IS NOT NULL RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asInt());
        for(var entry:m("namespace",namespace,"Specimen",specimen,"Context",contextClass,"C1",c1,"C2",c2,"S1",s1,"S2",s2,"S3",s3).entrySet())recovery.setProperty(entry.getKey(),(String)entry.getValue());
    }
    @Test @Order(2) void allScalarAndCollectionTypesRoundTrip() {
        var values=m("String","  Unicode žλ 😀 {\"x\":1}  ","Integer",Long.MAX_VALUE,"Float",12.5,"Boolean",true,"Date","2024-02-29","Time","12:34:56.123456789+02:00","LocalTime","12:34:56.123456789","DateTime","2024-06-01T12:34:56+02:00[Europe/Bratislava]","LocalDateTime","2024-06-01T12:34:56","Duration",m("months",2L,"days",3L,"seconds",4L,"nanoseconds",5L),"Point",m("srid",9157L,"coordinates",List.of(1.25,2.5,3.75)),"List",m("itemType","List","items",List.of(m("itemType","Integer","items",Arrays.asList(Long.MIN_VALUE,null,Long.MAX_VALUE)),m("itemType","String","items",List.of()))),"DDict",m("type",typed("String","literal"),"value",typed("Integer",Long.MAX_VALUE),"nested",typed("List",m("itemType","DDict","items",List.of(m("key",typed("Boolean",true)))))));
        var declarations=new ArrayList<Map<String,Object>>();values.forEach((type,value)->declarations.add(m("name",type,"type",type)));declarations.add(m("name","Unknown","type","String"));
        typedClass=id(mutate("deposit.create.class",m("name","Typed","descriptors",declarations)));
        var input=new LinkedHashMap<>(values);input.put("Unknown",null);var created=mutate("deposit.create.object",m("classId",typedClass,"descriptors",input));typedObject=id(created);
        assertEquals(input,created.get("data").get("descriptors").asMap());assertEquals(input,get("deposit.get.object",m("id",typedObject)).get("data").get("descriptors").asMap());
        var stored=query("MATCH (o:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor) RETURN d.name AS name,d.encoding AS encoding,d.value AS value,d.type AS type ORDER BY name",m("id",typedObject));
        for(var row:stored) {assertTrue(row.get("type").isNull());if(Set.of("DDict","List").contains(row.get("name").asString())) {assertEquals("json",row.get("encoding").asString());assertTrue(row.get("value").asString().contains("\"9223372036854775807\""));}}
        assertEquals(1,query("MATCH (o:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Unknown'}) WHERE d.value IS NULL RETURN count(d) AS n",m("id",typedObject)).getFirst().get("n").asInt());
        var nullable=mutate("deposit.set.descriptor",m("objectId",s3,"name","Mass","value",null));assertTrue(nullable.get("data").get("value").isNull());
        mutate("deposit.delete.descriptor",m("id",id(nullable)));assertTrue(query("CALL deposit.get.descriptor($r,$c)",m("r",m("objectId",s3,"name","Mass"),"c",ctx(token))).isEmpty());
    }
    @Test @Order(3) void failedValidationRollsBackSchemaDataAuditAndReceipt() {
        var before=state();long descriptors=count("Descriptor"),declarations=count("ClassDescriptor");var failedContext=write(token,sequence);
        error("TYPE_CONFLICT",()->call("deposit.set.object",m("id",s1,"descriptors",m("New",typed("String","new"),"Mass","wrong")),failedContext));
        assertEquals(before,state());assertEquals(descriptors,count("Descriptor"));assertEquals(declarations,count("ClassDescriptor"));
        error("NOT_FOUND",()->get("deposit.get.operation",m("operationId",failedContext.get("operationId"))));
        for(Object bad:List.of(Double.NaN,Double.POSITIVE_INFINITY))error("TYPE_CONFLICT",()->call("deposit.set.descriptor",m("objectId",typedObject,"name","Float","value",bad),write(token,sequence)));
        error("TYPE_CONFLICT",()->call("deposit.set.descriptor",m("objectId",typedObject,"name","Float","value",Long.MAX_VALUE),write(token,sequence)));
        error("VALIDATION_FAILED",()->call("deposit.set.descriptor",m("objectId",s1,"name","Undeclared","value",null),write(token,sequence)));
        error("VALIDATION_FAILED",()->call("deposit.set.object",m("id",s1,"descriptors",m("Mass",1L),"clearDescriptors",List.of("Mass")),write(token,sequence)));
        error("VALIDATION_FAILED",()->call("deposit.create.object",m("classId",specimen,"className","Specimen"),write(token,sequence)));
        error("VALIDATION_FAILED",()->call("deposit.create.class",m("name","Bad","unexpected",true),write(token,sequence)));
        long requiredObjects=count("Object"),requiredClasses=count("Class"),requiredDescriptors=count("Descriptor"),requiredDeclarations=count("ClassDescriptor");
        long requiredMemberships=query("MATCH (:Object {namespaceId:$ns})-[r:MEMBER_OF]->(:Class {namespaceId:$ns}) RETURN count(r) AS n",m("ns",namespace)).getFirst().get("n").asLong();
        // Required lookups parse IDs before consulting the captured Namespace and preserve the same NOT_FOUND contract.
        error("VALIDATION_FAILED",()->get("deposit.get.object",m("id","not-a-uuid")));
        error("VALIDATION_FAILED",()->get("deposit.get.object",m("id",42L)));
        error("NOT_FOUND",()->get("deposit.get.object",m("id",specimen)));
        error("NOT_FOUND",()->get("deposit.get.object",m("id",UUID.randomUUID().toString())));
        error("VALIDATION_FAILED",()->call("deposit.delete.object",m("id","not-a-uuid"),write(token,sequence)));
        var nonStringDelete=assertThrows(org.neo4j.driver.exceptions.ClientException.class,
            ()->call("deposit.delete.object",m("id",42L),write(token,sequence)));
        assertTrue(nonStringDelete.getMessage().contains("expected String but was Integer"),nonStringDelete.getMessage());
        error("NOT_FOUND",()->call("deposit.delete.object",m("id",specimen),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.delete.object",m("id",UUID.randomUUID().toString()),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.set.membership",m("objectId",specimen,"classId",contextClass),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.set.membership",m("objectId",s1,"classId",s2),write(token,sequence)));
        error("VALIDATION_FAILED",()->call("deposit.set.membership",m("objectId",s1,"classId",42L),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.set.membership",m("objectId",s1,"classId",UUID.randomUUID().toString()),write(token,sequence)));
        var staleLookup=write(token,sequence+1);
        // Direct command UUID parsing remains before freshness checking.
        error("VALIDATION_FAILED",()->call("deposit.delete.object",m("id","not-a-uuid"),staleLookup));
        var selectorFailure=assertThrows(org.neo4j.driver.exceptions.ClientException.class,
            ()->get("deposit.get.object",m("id","not-a-uuid","classId",contextClass)));
        assertTrue(selectorFailure.getMessage().contains("ID selector is exclusive"),selectorFailure.getMessage());
        assertFalse(selectorFailure.getMessage().contains("Invalid UUID"),selectorFailure.getMessage());
        error("AUTH_FAILED",()->call("deposit.get.object",m("id","not-a-uuid"),ctx("not-a-valid-token")));
        select(token,otherNamespace);long outsideSequence=revision(get("deposit.get.activeNamespace",Map.of()));
        var outsideClass=call("deposit.create.class",m("name","Outside"),write(token,outsideSequence));
        String outsideObject=id(call("deposit.create.object",m("classId",id(outsideClass)),write(token,revision(outsideClass))));
        select(token,namespace);
        error("NOT_FOUND",()->get("deposit.get.object",m("id",outsideObject)));
        error("NOT_FOUND",()->call("deposit.delete.object",m("id",outsideObject),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.set.membership",m("objectId",outsideObject,"classId",contextClass),write(token,sequence)));
        error("NOT_FOUND",()->call("deposit.set.membership",m("objectId",s1,"classId",id(outsideClass)),write(token,sequence)));
        assertEquals(before,state());
        assertEquals(requiredObjects,count("Object"));assertEquals(requiredClasses,count("Class"));
        assertEquals(requiredDescriptors,count("Descriptor"));assertEquals(requiredDeclarations,count("ClassDescriptor"));
        assertEquals(requiredMemberships,query("MATCH (:Object {namespaceId:$ns})-[r:MEMBER_OF]->(:Class {namespaceId:$ns}) RETURN count(r) AS n",m("ns",namespace)).getFirst().get("n").asLong());
    }
    @Test @Order(4) void membershipConversionDeletionAndReceiptHandleRehydration() {
        String destination=id(mutate("deposit.create.class",m("name","Destination")));
        var membership=mutate("deposit.set.membership",m("objectId",s1,"classId",destination));assertEquals(destination,membership.get("data").get("classId").asString());assertEquals(destination,id(get("deposit.get.objectClass",m("objectId",s1))));
        assertEquals("Integer",get("deposit.get.classDescriptor",m("classId",destination,"name","Mass")).get("data").get("type").asString());
        mutate("deposit.set.membership",m("objectId",s1,"classId",specimen));
        String incompatible=id(mutate("deposit.create.class",m("name","Incompatible","descriptors",List.of(m("name","Mass","type","String")))));
        error("TYPE_CONFLICT",()->call("deposit.set.membership",m("objectId",s1,"classId",incompatible),write(token,sequence)));assertEquals(specimen,id(get("deposit.get.objectClass",m("objectId",s1))));
        error("VALIDATION_FAILED",()->call("deposit.delete.class",m("id",specimen),write(token,sequence)));
        String declaration=id(get("deposit.get.classDescriptor",m("classId",specimen,"name","Mass")));
        error("VALIDATION_FAILED",()->call("deposit.delete.classDescriptor",m("id",declaration),write(token,sequence)));
        mutate("deposit.set.classDescriptorType",m("id",declaration,"type","Float","conversion","integerToFloat"));assertEquals(10.0,get("deposit.get.descriptor",m("objectId",s1,"name","Mass")).get("data").get("value").asDouble());
        mutate("deposit.set.classDescriptorType",m("id",declaration,"type","Integer","conversion","floatToIntegerExact"));
        receiptContext=write(token,sequence);receiptRequest=m("classId",specimen,"descriptors",m("Mass",33L));var created=call("deposit.create.object",receiptRequest,receiptContext);sequence=revision(created);receiptObject=id(created);
        mutate("deposit.set.object",m("id",receiptObject,"descriptors",m("Mass",44L)));
        var replay=call("deposit.create.object",receiptRequest,receiptContext);assertEquals(33,replay.get("data").get("descriptors").get("Mass").asInt());assertFalse(replay.get("node").isNull());
        mutate("deposit.delete.object",m("id",receiptObject));replay=call("deposit.create.object",receiptRequest,receiptContext);assertTrue(replay.get("node").isNull());assertEquals(33,replay.get("data").get("descriptors").get("Mass").asInt());
        mutate("deposit.delete.class",m("id",destination));mutate("deposit.delete.class",m("id",incompatible));
    }
    @Test @Order(5) void concurrentWritesAndCapturedScope() throws Exception {
        long expected=sequence;var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var attempts=new ArrayList<Future<Object>>();
            for(String t:List.of(token,second))attempts.add(executor.submit(()->{ready.countDown();start.await();try{return call("deposit.set.descriptor",m("objectId",s3,"name","Race","value",typed("Integer",7L)),write(t,expected));}catch(org.neo4j.driver.exceptions.ClientException e){return e;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int successes=0,conflicts=0;
            for(var f:attempts) {Object result=f.get(30,TimeUnit.SECONDS);if(result instanceof org.neo4j.driver.Record r){successes++;sequence=revision(r);}else {assertTrue(((Exception)result).getMessage().contains("REVISION_CONFLICT"));conflicts++;}}
            assertEquals(1,successes);assertEquals(1,conflicts);
        }
        assertEquals(1,query("MATCH (n:ClassDescriptor {namespaceId:$ns,name:'Race'}) RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asInt());
        try(var session=driver.session();var tx=session.beginTransaction()) {
            tx.run("CALL deposit.get.object({id:$id},$c)",m("id",s1,"c",ctx(token))).consume();select(token,otherNamespace);
            assertEquals(s1,tx.run("CALL deposit.get.object({id:$id},$c)",m("id",s1,"c",ctx(token))).single().get("data").get("id").asString());
            error("VALIDATION_FAILED",()->tx.run("CALL deposit.get.object({},$c)",m("c",ctx(second))).consume());
        } finally {select(token,namespace);}
        long objects=count("Object");
        try(var session=driver.session();var tx=session.beginTransaction()) {
            tx.run("CALL deposit.create.object($r,$c)",m("r",m("classId",specimen),"c",write(token,sequence))).consume();
            error("VALIDATION_FAILED",()->tx.run("CALL deposit.create.object($r,$c)",m("r",m("classId",specimen),"c",write(token,sequence+1))).consume());
        }
        assertEquals(objects,count("Object"));assertEquals(sequence,revision(get("deposit.get.activeNamespace",Map.of())));
    }
    @Test @Order(6) void permissionsAndCredentialGuardrails() {
        var login=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD));long server=revision(login);
        var user=call("deposit.create.user",m("username","viewer-"+UUID.randomUUID(),"password","viewer-account-password"),write(token,server));String userId=id(user);
        assertFalse(user.get("data").asMap().containsKey("passwordHash"));
        mutate("deposit.set.namespaceRole",m("userId",userId,"role","viewer"));
        String viewer=special("deposit.utils.login",m("username",user.get("data").get("username").asString(),"password","viewer-account-password")).get("value").asString();
        error("NO_ACTIVE_NAMESPACE",()->call("deposit.get.object",Map.of(),ctx(viewer)));select(viewer,namespace);
        assertEquals(s1,id(call("deposit.get.object",m("id",s1),ctx(viewer))));
        error("FORBIDDEN",()->call("deposit.create.object",m("classId",specimen),write(viewer,sequence)));
        error("NOT_FOUND",()->call("deposit.set.activeNamespace",m("id",otherNamespace),ctx(viewer)));
        mutate("deposit.set.namespaceRole",m("userId",userId,"role","editor"));
        var created=call("deposit.create.object",m("classId",specimen),write(viewer,sequence));sequence=revision(created);
        mutate("deposit.delete.object",m("id",id(created)));mutate("deposit.set.namespaceRole",m("userId",userId,"role",null));
        error("NOT_FOUND",()->call("deposit.get.object",Map.of(),ctx(viewer)));
        error("AUTH_REQUIRED",()->call("deposit.get.object",Map.of(),ctx("")));
        error("AUTH_FAILED",()->special("deposit.utils.login",m("username",ACCOUNT,"password","incorrect")));
        call("deposit.utils.logout",Map.of(),ctx(viewer));error("AUTH_FAILED",()->call("deposit.get.user",Map.of(),ctx(viewer)));
        assertEquals(s1,id(get("deposit.get.object",m("id",s1))));
    }
    // Allow for 1,007 durable commits and full stream/page scans on the populated host.
    @Test @Order(7) @Timeout(600) void fullStreamsPortablePagesAndStaleCursors() {
        String bulk=id(mutate("deposit.create.class",m("name","Bulk")));
        for(int i=0;i<1007;i++)mutate("deposit.create.object",m("classId",bulk));
        var nativeRows=query("CALL deposit.get.object($r,$c) YIELD node,data,meta RETURN node,data,meta",m("r",m("classId",bulk),"c",ctx(token)));
        assertEquals(1007,nativeRows.size());assertEquals(1007,query("CALL deposit.get.object($r,$c) YIELD node RETURN count(node) AS n",m("r",m("classId",bulk),"c",ctx(token))).getFirst().get("n").asInt());
        var portable=new ArrayList<Map<String,Object>>();String cursor=null,firstCursor=null;
        do {
            var request=m("classId",bulk,"pageSize",137L);if(cursor!=null)request.put("cursor",cursor);
            var page=get("deposit.get.objectPage",request);for(var item:page.get("data").get("items").values())portable.add(item.asMap());cursor=page.get("meta").get("nextCursor").isNull()?null:page.get("meta").get("nextCursor").asString();if(firstCursor==null)firstCursor=cursor;
        }while(cursor!=null);
        assertEquals(nativeRows.stream().map(r->r.get("data").asMap()).toList(),portable);
        var empty=get("deposit.get.descriptorPage",m("objectId",s3,"name","absent"));assertEquals(List.of(),empty.get("data").get("items").asList());assertTrue(empty.get("meta").get("nextCursor").isNull());
        error("VALIDATION_FAILED",()->get("deposit.get.object",m("pageSize",10L)));
        error("LIMIT_EXCEEDED",()->get("deposit.get.objectPage",m("pageSize",1001L)));
        final String saved=firstCursor;
        select(token,otherNamespace);error("SCOPE_MISMATCH",()->get("deposit.get.objectPage",m("cursor",saved,"classId",bulk,"pageSize",137L)));select(token,namespace);
        mutate("deposit.set.class",m("id",bulk,"comment","invalidate cursor"));error("REVISION_CONFLICT",()->get("deposit.get.objectPage",m("cursor",saved,"classId",bulk,"pageSize",137L)));
        // An incomplete consumer can close its transaction; the next action must not be blocked.
        try(var s=driver.session(SessionConfig.builder().withFetchSize(1).build());var tx=s.beginTransaction()) {var result=tx.run("CALL deposit.get.object($r,$c)",m("r",m("classId",bulk),"c",ctx(token)));assertTrue(result.hasNext());result.next();tx.rollback();}
        mutate("deposit.set.class",m("id",bulk,"comment","after early close"));
        recovery.setProperty("bulk",bulk);recovery.setProperty("bulkCount","1007");
    }
    @Test @Order(8) void persistRecoveryInputsAndInspectFinalGraph() throws Exception {
        recovery.setProperty("sequence",Long.toString(sequence));recovery.setProperty("operation",(String)receiptContext.get("operationId"));recovery.setProperty("expectedRevision",receiptContext.get("expectedRevision").toString());recovery.setProperty("receiptObject",receiptObject);
        recovery.setProperty("namespaceCursor",get("deposit.get.objectPage",m("classId",recovery.getProperty("bulk"),"pageSize",1000L)).get("meta").get("nextCursor").asString());
        recovery.setProperty("serverCursor",get("deposit.get.namespacePage",m("pageSize",1L)).get("meta").get("nextCursor").asString());
        var fixture=ROOT.resolve("typed-data-fixture.properties");try(var output=Files.newOutputStream(fixture)){recovery.store(output,"typed-data public identities and receipt inputs");}
        var tokenFile=ROOT.resolve("secrets/typed-data-session.txt");Files.writeString(tokenFile,"");var acl=Files.getFileAttributeView(tokenFile,AclFileAttributeView.class);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(tokenFile)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));Files.writeString(tokenFile,token);
        assertEquals(0,query("MATCH (o:Object {namespaceId:$ns}) OPTIONAL MATCH (o)-[m:MEMBER_OF]->(:Class) WITH o,count(m) AS memberships WHERE memberships<>1 RETURN count(o) AS n",m("ns",namespace)).getFirst().get("n").asInt());
        assertEquals(0,query("MATCH (n:Revision {namespaceId:$ns}) RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asInt());
        var graph=query("MATCH (n) WHERE n.namespaceId=$ns OR n.id=$ns RETURN labels(n) AS labels, count(n) AS count ORDER BY labels",m("ns",namespace));
    }
    @Test @Order(9) void catalogAndFailureEnvelopeMatchPublicConventions() {
        var catalog=query("SHOW PROCEDURES YIELD name,signature WHERE name STARTS WITH 'deposit.' RETURN name,signature",Map.of());assertEquals(124,catalog.size());
        for(var procedure:catalog) {
            String name=procedure.get("name").asString(),signature=procedure.get("signature").asString();assertFalse(signature.contains("meta :: MAP"),signature);
            if(name.endsWith("Page")) {assertTrue(signature.contains("data :: MAP"));assertFalse(signature.contains("node :: NODE"));}
            if(!Set.of("deposit.get.info","deposit.utils.bootstrap","deposit.utils.login").contains(name)) {assertTrue(signature.contains("token :: STRING"),signature);assertFalse(signature.contains("context :: MAP"),signature);assertFalse(signature.contains("namespaceId :: STRING"));}
        }
        String requestId=UUID.randomUUID().toString();var context=write(token,sequence);context.put("requestId",requestId);
        var error=assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->call("deposit.create.object",m("classId",specimen,"unexpected",true),context));assertFalse(error.getMessage().contains(requestId));assertFalse(error.getMessage().contains(PASSWORD));
        error("VALIDATION_FAILED",()->get("deposit.get.object",m("id",s1,"classId",specimen)));
    }
}
