package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Fixed public inputs and independent graph assertions, with no plugin dependency. */
@Timeout(120)
class Stage3CoreIT {
    static String token,second,namespace,namespaceName;
    static final String TEXT="  opaque {not JSON}\r\n\tŽluťoučký λ 😀 e\u0301\u0000  ";
    static final Map<String,String> identities=new TreeMap<>();
    @BeforeAll static void connectStage3() {connect();ensureAccount();token=login();second=login();}
    static String login() {return special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD)).get("value").asString();}
    @BeforeEach void namespace() {
        namespaceName="Stage3-"+UUID.randomUUID();namespace=id(call("deposit.create.namespace",m("name",namespaceName,"historyMode","disabled"),write(token,server())));
        select(token,namespace);select(second,namespace);identities.put(namespaceName,namespace);
    }
    @AfterAll static void finish() { close(); }
    static void select(String token,String ns) {call("deposit.set.activeNamespace",m("id",ns),ctx(token));}
    static long server() {return revision(call("deposit.get.user",Map.of(),ctx(token)));}
    static long sequence() {return revision(call("deposit.get.activeNamespace",Map.of(),ctx(token)));}
    static org.neo4j.driver.Record writeCall(String proc,Map<String,Object> r) {return call(proc,r,write(token,sequence()));}
    static org.neo4j.driver.Record read(String proc,Map<String,Object> r) {return call(proc,r,ctx(token));}
    static Map<String,Object> command(String key,String proc,Map<String,Object> r) {return m("key",key,"procedure",proc,"request",r);}
    static Map<String,Object> ref(String key) {return m("ref",key,"field","id");}
    static Map<String,Object> typed(String type,Object value) {return m("type",type,"value",value);}
    static org.neo4j.driver.Record batch(List<Map<String,Object>> commands) {return writeCall("deposit.apply.batch",m("commands",commands));}
    static String resultId(org.neo4j.driver.Record result,String key) {return result.get("data").get("results").get(key).get("id").asString();}
    static List<Map<String,Object>> fixture() {return List.of(
        command("specimen","deposit.create.class",m("name","Specimen","descriptors",List.of(m("name","Mass","type","Integer")))),
        command("context","deposit.create.class",m("name","Context")),
        command("c1","deposit.create.object",m("classId",ref("context"))),command("c2","deposit.create.object",m("classId",ref("context"))),
        command("s1","deposit.create.object",m("classId",ref("specimen"),"descriptors",m("Mass",10L))),
        command("s2","deposit.create.object",m("classId",ref("specimen"),"descriptors",m("Mass",20L))),
        command("s3","deposit.create.object",m("classId",ref("specimen"))),
        command("r1","deposit.create.relationship",m("sourceId",ref("s1"),"targetId",ref("c1"),"label","foundIn","weight",null)),
        command("r2","deposit.create.relationship",m("sourceId",ref("s2"),"targetId",ref("c1"),"label","foundIn","weight",2.5)));
    }
    static Map<String,Object> state() {
        return query("MATCH (n:Namespace {id:$ns}) RETURN properties(n) AS namespace, [(n)-[:CONTAINS]->(x) | x.id] AS ids",m("ns",namespace)).getFirst().asMap();
    }
    static long count(String label) {return query("MATCH (n:`"+label+"` {namespaceId:$ns}) RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asLong();}
    @Test void oneBatchSeedsNativeModelAndPortableSnapshots() {
        var result=batch(fixture());assertEquals(1,revision(result));assertEquals(9,result.get("data").get("results").size());assertFalse(result.get("data").asMap().containsKey("revisionId"));
        var nativeResult=query("CALL deposit.get.relationship({},$c) YIELD rel MATCH (s)-[rel]->(target) RETURN count(rel) AS n,sum(rel.weight) AS total",m("c",ctx(token))).getFirst();
        assertEquals(2,nativeResult.get("n").asInt());assertEquals(2.5,nativeResult.get("total").asDouble());
        var relationshipRows=query("CALL deposit.get.relationship({},$c) YIELD data RETURN data",m("c",ctx(token)));
        assertEquals(relationshipRows.stream().map(r->r.get("data").asMap()).toList(),read("deposit.get.relationshipPage",Map.of()).get("data").get("items").asList());
        assertEquals(1,read("deposit.get.classRelationshipPage",Map.of()).get("data").get("items").size());
        assertEquals(30,query("CALL deposit.get.object({classId:$id},$c) YIELD node MATCH (node)-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN sum(d.value) AS n",m("id",resultId(result,"specimen"),"c",ctx(token))).getFirst().get("n").asInt());
        assertEquals(0,query("MATCH (n:DepositLive {namespaceId:$ns}) WHERE n.kind IS NOT NULL OR (n:Descriptor AND n.type IS NOT NULL) RETURN count(n) AS n",m("ns",namespace)).getFirst().get("n").asInt());
        for(String key:List.of("specimen","context","c1","c2","s1","s2","s3","r1","r2"))identities.put(key,resultId(result,key));
    }
    @Test void finalFailureCallerRollbackAndOneMutationRule() {
        var before=state();var context=write(token,sequence());var commands=new ArrayList<>(fixture());commands.add(command("bad","deposit.set.descriptor",m("objectId",ref("s1"),"name","Mass","value","wrong")));
        var failure=assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->call("deposit.apply.batch",m("commands",commands),context));assertTrue(failure.getMessage().contains("TYPE_CONFLICT"));assertTrue(failure.getMessage().contains("commandKey\":\"bad"));assertTrue(failure.getMessage().contains("commandIndex\":9"));
        assertEquals(before,state());assertEquals(0,count("Class"));assertEquals(0,query("MATCH (n:DepositEdgeIdentity) WHERE n.key STARTS WITH $prefix RETURN count(n) AS n",m("prefix",namespace+"/")).getFirst().get("n").asInt());
        error("NOT_FOUND",()->read("deposit.get.operation",m("operationId",context.get("operationId"))));
        context.put("operationId",UUID.randomUUID().toString());
        try(var session=driver.session();var tx=session.beginTransaction()) {tx.run("CALL deposit.apply.batch($r,$c)",m("r",m("commands",fixture()),"c",context)).consume();tx.rollback();}assertEquals(before,state());
        try(var session=driver.session();var tx=session.beginTransaction()) {
            tx.run("CALL deposit.apply.batch($r,$c)",m("r",m("commands",fixture()),"c",context)).consume();
            error("VALIDATION_FAILED",()->tx.run("CALL deposit.create.class($r,$c)",m("r",m("name","extra"),"c",write(token,1))).consume());
        }assertEquals(before,state());
    }
    @Test void changedThenRestoredIsChangedButAllNoChangeIsNot() {
        String cl=id(writeCall("deposit.create.class",m("name","A")));long before=sequence();var audit=state();
        var result=batch(List.of(command("b","deposit.set.class",m("id",cl,"name","B")),command("a","deposit.set.class",m("id",cl,"name","A"))));
        assertEquals(before+1,revision(result));assertEquals("B",result.get("data").get("results").get("b").get("name").asString());assertNotEquals(audit,state());
        before=sequence();audit=state();var context=write(token,before);var request=m("commands",List.of(command("same","deposit.set.class",m("id",cl,"name","A"))));
        result=call("deposit.apply.batch",request,context);assertFalse(result.get("data").get("changed").asBoolean());assertEquals(before,revision(result));assertEquals(audit,state());
        assertEquals(result.get("data").asMap(),call("deposit.apply.batch",request,context).get("data").asMap());
        var empty=writeCall("deposit.apply.batch",Map.of());assertFalse(empty.get("data").get("changed").asBoolean());assertEquals(before,revision(empty));
        result=batch(List.of(command("new","deposit.create.userTool",m("name","temporary","data","x")),command("gone","deposit.delete.userTool",m("id",ref("new")))));assertEquals(before+1,revision(result));assertEquals(0,count("UserTool"));
    }
    @Test void receiptsPreserveIntermediateResultsAndScope() {
        var request=m("commands",List.of(command("new","deposit.create.userTool",m("name","T","data",TEXT)),command("edit","deposit.set.userTool",m("id",ref("new"),"data","later")),command("delete","deposit.delete.userTool",m("id",ref("new")))));var context=write(token,0);
        var result=call("deposit.apply.batch",request,context);assertEquals(TEXT,result.get("data").get("results").get("new").get("data").asString());assertEquals("later",result.get("data").get("results").get("edit").get("data").asString());
        assertEquals(result.get("data").asMap(),read("deposit.get.operation",m("operationId",context.get("operationId"))).get("data").get("outcome").asMap());
        writeCall("deposit.create.class",m("name","later"));var before=state();assertEquals(result.get("data").asMap(),call("deposit.apply.batch",request,context).get("data").asMap());assertEquals(before,state());
        error("OPERATION_MISMATCH",()->call("deposit.apply.batch",m("commands",List.of()),context));
        String other=id(call("deposit.create.namespace",m("name","other-"+UUID.randomUUID(),"historyMode","disabled"),write(token,server())));select(token,other);error("SCOPE_MISMATCH",()->call("deposit.apply.batch",request,context));select(token,namespace);
        var cmds=new ArrayList<Map<String,Object>>();cmds.add(command("new","deposit.create.userTool",m("name","T","data","x")));cmds.add(command("gone","deposit.delete.userTool",m("id",ref("new"))));cmds.add(command("edit","deposit.set.userTool",m("id",ref("new"),"data","y")));error("NOT_FOUND",()->batch(cmds));assertEquals(before,state());
    }
    @Test void batchValidationAndBounds() throws Exception {
        var good=command("a","deposit.create.class",m("name","A"));var before=state();
        for(var commands:List.of(List.of(good,good),List.of(command("a","deposit.apply.batch",m("commands",List.of()))),List.of(command("a","deposit.create.object",m("classId",ref("b")))),List.of(command("a","deposit.create.class",m("name","A","unknown",true))),List.of(command("a","deposit.set.user",Map.of()))))error("VALIDATION_FAILED",()->batch(commands));
        var over=new ArrayList<Map<String,Object>>();for(int i=0;i<1001;i++)over.add(command("c"+i,"deposit.create.class",m("name","C"+i)));error("LIMIT_EXCEEDED",()->batch(over));assertEquals(before,state());
        String tool=id(writeCall("deposit.create.userTool",m("name","Boundary","data","")));
        String empty=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(m("commands",CorpusTransport.arguments("deposit.apply.batch",m("commands",List.of(command("x","deposit.set.userTool",m("id",tool,"data",""))))).getFirst()));
        int length=8*1024*1024-empty.getBytes(StandardCharsets.UTF_8).length;String payload="x".repeat(length);
        var accepted=batch(List.of(command("x","deposit.set.userTool",m("id",tool,"data",payload))));assertEquals(payload,accepted.get("data").get("results").get("x").get("data").asString());
        long seq=sequence();error("LIMIT_EXCEEDED",()->batch(List.of(command("x","deposit.set.userTool",m("id",tool,"data",payload+"x")))));assertEquals(seq,sequence());
        var exact=new ArrayList<Map<String,Object>>();for(int i=0;i<1000;i++)exact.add(command("c"+i,"deposit.set.userTool",m("id",tool,"data","small")));assertEquals(1000,batch(exact).get("data").get("results").size());
    }
    @Test void declarationSettersOrderedPatchesAndTypeFailures() {
        var result=batch(fixture());String cl=resultId(result,"specimen"),object=resultId(result,"s1");String declaration=id(read("deposit.get.classDescriptor",m("classId",cl,"name","Mass")));
        writeCall("deposit.set.classDescriptorOrder",m("id",declaration,"order",-2L));writeCall("deposit.set.classDescriptorComment",m("id",declaration,"comment","exact\ncomment"));
        assertEquals(-2,read("deposit.get.classDescriptor",m("id",declaration)).get("data").get("order").asInt());
        error("VALIDATION_FAILED",()->writeCall("deposit.set.classDescriptorComment",m("id",declaration,"name","Renamed","comment","x")));
        batch(List.of(command("set","deposit.set.object",m("id",object,"descriptors",m("Mass",40L))),command("clear","deposit.set.object",m("id",object,"clearDescriptors",List.of("Mass")))));
        assertTrue(query("CALL deposit.get.descriptor($r,$c)",m("r",m("objectId",object,"name","Mass"),"c",ctx(token))).isEmpty());
        error("VALIDATION_FAILED",()->writeCall("deposit.set.object",m("id",object,"descriptors",m("Mass",1L),"clearDescriptors",List.of("Mass"))));
        var before=state();error("TYPE_CONFLICT",()->batch(List.of(command("new","deposit.set.descriptor",m("objectId",object,"name","New","value",typed("String","x"))),command("wrong","deposit.set.descriptor",m("objectId",object,"name","Mass","value","bad")))));assertEquals(before,state());
        writeCall("deposit.set.descriptor",m("objectId",object,"name","Mass","value",Long.MAX_VALUE));error("TYPE_CONFLICT",()->writeCall("deposit.set.classDescriptorType",m("id",declaration,"type","Float","conversion","integerToFloat")));assertEquals("Integer",read("deposit.get.classDescriptor",m("id",declaration)).get("data").get("type").asString());
        var dict=m("type",typed("String","literal"),"value",typed("Integer",Long.MAX_VALUE));writeCall("deposit.set.descriptor",m("objectId",object,"name","Dictionary","value",typed("DDict",dict)));assertEquals(dict,read("deposit.get.descriptor",m("objectId",object,"name","Dictionary")).get("data").get("value").asMap());
        var literal=m("ref",typed("String","not-a-command"),"field",typed("String","id"));
        batch(List.of(command("dict","deposit.set.descriptor",m("objectId",object,"name","Dictionary","value",literal))));assertEquals(literal,read("deposit.get.descriptor",m("objectId",object,"name","Dictionary")).get("data").get("value").asMap());
    }
    @Test void membershipReconcilesBothDirectionsSelfEdgesAndRetainsSchema() {
        var f=batch(fixture());String object=resultId(f,"s1"),other=resultId(f,"s2"),cl=id(writeCall("deposit.create.class",m("name","Ceramic")));
        var self=writeCall("deposit.create.relationship",m("sourceId",object,"targetId",object,"label","self"));var incoming=writeCall("deposit.create.relationship",m("sourceId",other,"targetId",object,"label","pointsTo"));
        var before=query("MATCH (:Object {id:$id})-[r:MEMBER_OF]->() RETURN properties(r) AS r",m("id",object)).getFirst().get("r").asMap();
        var changed=writeCall("deposit.set.membership",m("objectId",object,"classId",cl));assertEquals(before.get("id"),id(changed));assertEquals(before.get("createdAt").toString(),changed.get("data").get("createdAt").asString());
        assertEquals(cl,read("deposit.get.object",m("id",object)).get("data").get("classId").asString());assertEquals(10,read("deposit.get.descriptor",m("objectId",object,"name","Mass")).get("data").get("value").asInt());
        for(var r:List.of(m("sourceId",cl,"targetId",resultId(f,"context"),"label","foundIn"),m("sourceId",cl,"targetId",cl,"label","self"),m("sourceId",resultId(f,"specimen"),"targetId",cl,"label","pointsTo"),m("sourceId",resultId(f,"specimen"),"targetId",resultId(f,"context"),"label","foundIn")))assertEquals(1,read("deposit.get.classRelationshipPage",r).get("data").get("items").size());
        assertEquals(id(self),id(read("deposit.get.relationship",m("id",id(self)))));assertEquals(id(incoming),id(read("deposit.get.relationship",m("id",id(incoming)))));
        String bad=id(writeCall("deposit.create.class",m("name","Bad","descriptors",List.of(m("name","Mass","type","String")))));var state=state();error("TYPE_CONFLICT",()->writeCall("deposit.set.membership",m("objectId",object,"classId",bad)));assertEquals(state,state());
    }
    @Test void relationshipConstraintsCascadesAndReceiptHandles() {
        var f=batch(fixture());String source=resultId(f,"s1"),target=resultId(f,"c1");var before=state();
        error("DUPLICATE",()->writeCall("deposit.create.relationship",m("sourceId",source,"targetId",target,"label","foundIn")));
        error("NOT_FOUND",()->writeCall("deposit.create.relationship",m("sourceId",source,"targetId",resultId(f,"context"),"label","bad")));
        error("TYPE_CONFLICT",()->writeCall("deposit.create.relationship",m("sourceId",source,"targetId",target,"label","bad","weight",Double.NaN)));assertEquals(before,state());
        var cr=read("deposit.get.classRelationship",m("label","foundIn"));error("VALIDATION_FAILED",()->writeCall("deposit.delete.classRelationship",m("id",id(cr))));error("VALIDATION_FAILED",()->writeCall("deposit.delete.class",m("id",resultId(f,"specimen"))));
        var request=m("sourceId",source,"targetId",target,"label","other","weight",-4.0);var context=write(token,sequence());var created=call("deposit.create.relationship",request,context);
        writeCall("deposit.set.relationship",m("id",id(created),"weight",null));var replay=call("deposit.create.relationship",request,context);assertEquals(-4.0,replay.get("data").get("weight").asDouble());assertTrue(replay.get("rel").asRelationship().get("weight").isNull());
        var deleted=writeCall("deposit.delete.object",m("id",source));assertTrue(deleted.get("data").get("deletedIds").asList().contains(id(created)));assertTrue(call("deposit.create.relationship",request,context).get("rel").isNull());
        assertEquals(0,query("MATCH (r:DepositEdgeIdentity) WHERE r.id IN $ids RETURN count(r) AS n",m("ids",deleted.get("data").get("deletedIds").asList())).getFirst().get("n").asInt());
        writeCall("deposit.delete.object",m("id",resultId(f,"s2")));writeCall("deposit.delete.classRelationship",m("id",id(cr)));
    }
    @Test void opaqueToolsAndClearKeepSettingsAndReceipts() {
        var tool=writeCall("deposit.create.userTool",m("name"," Tool ","data",TEXT));String id=id(tool);assertEquals(TEXT,read("deposit.get.userTool",m("id",id)).get("data").get("data").asString());
        assertEquals("Tool",tool.get("data").get("name").asString());assertEquals(TEXT,read("deposit.get.userToolPage",Map.of()).get("data").get("items").get(0).get("data").asString());
        for(Object value:Arrays.asList(null,12L,Map.of(),List.of()))error("VALIDATION_FAILED",()->writeCall("deposit.set.userTool",m("id",id,"data",value)));
        error("VALIDATION_FAILED",()->writeCall("deposit.create.userTool",m("name","bad","data","","kind","form")));error("DUPLICATE",()->writeCall("deposit.create.userTool",m("name","Tool","data","")));
        writeCall("deposit.set.userTool",m("id",id,"comment","comment"));assertEquals(TEXT,read("deposit.get.userTool",m("id",id)).get("data").get("data").asString());writeCall("deposit.set.userTool",m("id",id,"data",""));
        batch(fixture());var context=write(token,sequence());var request=m("confirmNamespaceName",namespaceName);var roles=read("deposit.get.namespaceRolesPage",Map.of()).get("data").asMap();
        var cleared=call("deposit.delete.all",request,context);assertEquals(0,count("Class"));assertEquals(0,count("Object"));assertEquals(0,count("UserTool"));assertEquals(roles,read("deposit.get.namespaceRolesPage",Map.of()).get("data").asMap());
        assertEquals(cleared.get("data").asMap(),call("deposit.delete.all",request,context).get("data").asMap());
        assertFalse(writeCall("deposit.delete.all",request).get("data").get("changed").asBoolean());
    }
    @Test void concurrentSchemaCreationAndCapturedSelection() throws Exception {
        String cl=id(writeCall("deposit.create.class",m("name","C")));long seq=sequence();
        var request=m("commands",List.of(command("o","deposit.create.object",m("classId",cl,"descriptors",m("New",typed("Integer",1L))))));
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.apply.batch($r,$c)",m("r",request,"c",write(token,seq))).consume();var entered=new CountDownLatch(1);
            var other=pool.submit(()->{entered.countDown();return call("deposit.apply.batch",request,write(second,seq));});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->other.get(150,TimeUnit.MILLISECONDS));tx.commit();
            var error=assertThrows(ExecutionException.class,()->other.get(15,TimeUnit.SECONDS));assertTrue(error.getCause().getMessage().contains("REVISION_CONFLICT"));
        }assertEquals(1,count("ClassDescriptor"));assertEquals(1,count("Object"));
        writeCall("deposit.create.object",m("classId",cl,"descriptors",m("New",2L)));assertEquals(1,count("ClassDescriptor"));
        String ns2=id(call("deposit.create.namespace",m("name","switched-"+UUID.randomUUID(),"historyMode","disabled"),write(token,server())));
        query("CALL deposit.action.release($token)",m("token",token));
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.get.class({},$c)",m("c",ctx(token))).consume();select(token,ns2);
            tx.run("CALL deposit.apply.batch($commands,$token)",m("commands",List.of(m("key","c","procedure","deposit.create.class","arguments",List.of("Captured",Map.of()))),"token",token)).consume();tx.commit();
        }select(token,namespace);assertNotNull(read("deposit.get.class",m("name","Captured")));
    }
    static long sequenceFor(String t) {return revision(call("deposit.get.activeNamespace",Map.of(),ctx(t)));}
    @Test void completeStreamsPagesOrderingAndSelectors() {
        String cl=id(writeCall("deposit.create.class",m("name","Bulk","order",10L)));String first=id(writeCall("deposit.create.class",m("name","First","order",-1L)));
        assertEquals(first,read("deposit.get.classPage",Map.of()).get("data").get("items").get(0).get("id").asString());
        for(int offset=0;offset<1007;offset+=500) {
            var commands=new ArrayList<Map<String,Object>>();for(int i=offset;i<Math.min(offset+500,1007);i++)commands.add(command("o"+i,"deposit.create.object",m("classId",cl)));batch(commands);
        }
        var nativeRows=query("CALL deposit.get.object($r,$c) YIELD data RETURN data",m("r",m("classId",cl),"c",ctx(token)));assertEquals(1007,nativeRows.size());
        var portable=new ArrayList<Object>();String cursor=null;do {var r=m("classId",cl,"pageSize",199L);if(cursor!=null)r.put("cursor",cursor);var page=read("deposit.get.objectPage",r);portable.addAll(page.get("data").get("items").asList());cursor=page.get("meta").get("nextCursor").asString(null);}while(cursor!=null);
        assertEquals(nativeRows.stream().map(r->r.get("data").asMap()).toList(),portable);
        assertEquals(1007,query("CALL deposit.get.object($r,$c) YIELD node RETURN count(node) AS n",m("r",m("classId",cl),"c",ctx(token))).getFirst().get("n").asInt());
        try(var s=driver.session(SessionConfig.builder().withFetchSize(1).build());var tx=s.beginTransaction()) {
            var stream=tx.run("CALL deposit.get.object($r,$c)",m("r",m("classId",cl),"c",ctx(token)));assertTrue(stream.hasNext());stream.next();
            writeCall("deposit.create.classDescriptor",m("classId",cl,"name","Concurrent","type","Integer"));
            var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,stream::list);assertTrue(failure.getMessage().contains("REVISION_CONFLICT"));
        }
        try(var s=driver.session(SessionConfig.builder().withFetchSize(1).build());var tx=s.beginTransaction()) {tx.run("CALL deposit.get.object($r,$c)",m("r",m("classId",cl),"c",ctx(token))).next();tx.rollback();}
        var page=read("deposit.get.objectPage",m("classId",cl,"pageSize",10L));String stale=page.get("meta").get("nextCursor").asString();writeCall("deposit.create.userTool",m("name","edit","data",""));error("REVISION_CONFLICT",()->read("deposit.get.objectPage",m("classId",cl,"pageSize",10L,"cursor",stale)));
        error("VALIDATION_FAILED",()->read("deposit.get.object",m("pageSize",10L)));assertEquals(0,read("deposit.get.descriptorPage",Map.of()).get("data").get("items").size());error("NOT_FOUND",()->read("deposit.get.userToolPage",m("name","missing")));
        assertTrue(query("CALL deposit.get.relationship({},$c)",m("c",ctx(token))).isEmpty());
    }
    @Test void cancellationRollsBackAndCannotCancelAnotherActor() {
        String username="cancel-"+UUID.randomUUID();String user=id(call("deposit.create.user",m("username",username,"password",PASSWORD),write(token,server())));String ordinary=special("deposit.utils.login",m("username",username,"password",PASSWORD)).get("value").asString();
        var context=write(token,sequence());var before=state();
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.apply.batch($r,$c)",m("r",m("commands",fixture()),"c",context)).consume();
            error("FORBIDDEN",()->call("deposit.utils.cancel",m("requestId",context.get("requestId")),ctx(ordinary)));
            assertTrue(call("deposit.utils.cancel",m("requestId",context.get("requestId")),ctx(second)).get("value").asBoolean());assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);
        }assertEquals(before,state());error("NOT_FOUND",()->read("deposit.get.operation",m("operationId",context.get("operationId"))));assertFalse(call("deposit.utils.cancel",m("requestId",context.get("requestId")),ctx(token)).get("value").asBoolean());
        call("deposit.set.namespaceRole",m("userId",user,"role","editor"),write(token,sequence()));select(ordinary,namespace);
        var ordinaryContext=write(ordinary,sequence());try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.create.class($r,$c)",m("r",m("name","AdminCancels"),"c",ordinaryContext)).consume();assertTrue(call("deposit.utils.cancel",m("requestId",ordinaryContext.get("requestId")),ctx(token)).get("value").asBoolean());assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);
        }
    }
    @Test void batchPermissionsReplayAndNamespaceDeletion() {
        String username="editor-"+UUID.randomUUID();String user=id(call("deposit.create.user",m("username",username,"password",PASSWORD),write(token,server())));call("deposit.set.namespaceRole",m("userId",user,"role","editor"),write(token,sequence()));String editor=special("deposit.utils.login",m("username",username,"password",PASSWORD)).get("value").asString();select(editor,namespace);
        var before=state();error("FORBIDDEN",()->call("deposit.apply.batch",m("commands",List.of(command("c","deposit.create.class",m("name","NoPartial")),command("clear","deposit.delete.all",m("confirmNamespaceName",namespaceName)))),write(editor,sequence())));assertEquals(before,state());
        var request=m("commands",List.of(command("t","deposit.create.userTool",m("name","EditorTool","data",TEXT))));var context=write(editor,sequence());call("deposit.apply.batch",request,context);
        call("deposit.set.namespaceRole",m("userId",user,"role","viewer"),write(token,sequence()));error("FORBIDDEN",()->call("deposit.apply.batch",request,context));
        batch(fixture());var deletion=call("deposit.delete.namespace",m("confirmNamespaceName",namespaceName,"expectedNamespaceRevision",sequence()),write(token,server()));assertTrue(deletion.get("data").get("deletedIds").size()>1);
        assertEquals(0,count("UserTool"));assertEquals(0,count("Object"));assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(editor)).get("data").isNull());
    }
    @Test void concreteLabelIdentityAndEdgeCategoriesDoNotCollide() {
        String a=id(writeCall("deposit.create.class",m("name","A"))),b=id(writeCall("deposit.create.class",m("name","B")));
        String oa=id(writeCall("deposit.create.object",m("classId",a))),ob=id(writeCall("deposit.create.object",m("classId",b)));
        // Deliberately construct equal public IDs across concrete labels to exercise the specified uniqueness scope.
        query("MATCH (a:Class {id:$a}),(b:Class {id:$b}) SET a.id=$oa,b.id=$ob",m("a",a,"b",b,"oa",oa,"ob",ob));
        var instance=writeCall("deposit.create.relationship",m("sourceId",oa,"targetId",ob,"label","same"));var schema=read("deposit.get.classRelationship",m("sourceId",oa,"targetId",ob,"label","same"));
        assertNotEquals(id(instance),id(schema));assertEquals("Relationship",instance.get("data").get("kind").asString());assertEquals("ClassRelationship",schema.get("data").get("kind").asString());
        assertEquals(2,query("MATCH (r:DepositEdgeIdentity) WHERE r.id IN $ids RETURN count(r) AS n",m("ids",List.of(id(instance),id(schema)))).getFirst().get("n").asInt());
        assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->query("CREATE (:Class {namespaceId:$ns,id:$id,name:'duplicate-id'})",m("ns",namespace,"id",oa)));
        String other=id(call("deposit.create.namespace",m("name","identity-"+UUID.randomUUID(),"historyMode","disabled"),write(token,server())));
        query("MATCH (ns:Namespace {id:$ns}) CREATE (ns)-[:CONTAINS]->(:Class:DepositLive {namespaceId:$ns,id:$id,name:'same-id'})",m("ns",other,"id",oa));
        assertEquals(2,query("MATCH (n:Class {id:$id}) RETURN count(n) AS n",m("id",oa)).getFirst().get("n").asInt());
        error("NOT_FOUND",()->writeCall("deposit.create.relationship",m("sourceId",oa,"targetId",UUID.randomUUID().toString(),"label","missing")));
        var nativeRows=query("CALL deposit.get.classRelationship({},$c) YIELD rel,data RETURN data,startNode(rel).id AS source",m("c",ctx(token)));assertEquals(oa,nativeRows.getFirst().get("source").asString());
        assertEquals(nativeRows.stream().map(r->r.get("data").asMap()).toList(),read("deposit.get.classRelationshipPage",Map.of()).get("data").get("items").asList());
    }
    @Test void persistBatchForGracefulAndForcedRecovery() throws Exception {
        var context=write(token,0);var request=m("commands",List.of(command("tool","deposit.create.userTool",m("name","Recovered","data",TEXT))));var result=call("deposit.apply.batch",request,context);String tool=resultId(result,"tool");
        writeCall("deposit.set.userTool",m("id",tool,"data","current"));var p=new Properties();p.setProperty("namespace",namespace);p.setProperty("operation",(String)context.get("operationId"));p.setProperty("tool",tool);p.setProperty("sequence",Long.toString(sequence()));try(var out=Files.newOutputStream(ROOT.resolve("stage3-fixture.properties"))){p.store(out,"Stage 3 public recovery identities; no credentials");}
    }
    @Test void lostCommitResponseIsResolvedThroughReceipt() throws Exception {
        var context=write(token,0);var request=m("commands",List.of(command("tool","deposit.create.userTool",m("name","LostResponse","data",TEXT))));
        query("CALL deposit.action.release($token)",m("token",token));
        query("CALL deposit.utils.refresh('namespace',$token)",m("token",token));
        var arguments=CorpusTransport.arguments("deposit.apply.batch",request);
        String operation=query("CALL deposit.action.prepare($key,'deposit.apply.batch',$args,$token)",m("key",context.get("operationId"),"args",arguments,"token",token)).getFirst().get("data").get("operationId").asString();
        CorpusTransport.OPERATIONS.put((String)context.get("operationId"),operation);
        try(var proxy=new LostResponseProxy();var client=GraphDatabase.driver("bolt://127.0.0.1:"+proxy.port(),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")));var session=client.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.apply.batch($commands,$token)",m("commands",arguments.getFirst(),"token",token)).consume();proxy.suppress.set(true);
            var commit=pool.submit(()->{tx.commit();return true;});
            boolean committed=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                long receipts=query("MATCH (r:DepositReceipt {scope:$ns,operationId:$op}) RETURN count(r) AS n",m("ns",namespace,"op",context.get("operationId"))).getFirst().get("n").asLong();
                if(receipts==1) {committed=true;break;}Thread.sleep(20);
            }
            proxy.disconnect();assertTrue(committed,"Commit did not become durable behind the suppressed response");assertThrows(ExecutionException.class,()->commit.get(10,TimeUnit.SECONDS));
        }
        var receipt=read("deposit.get.operation",m("operationId",context.get("operationId")));var result=call("deposit.apply.batch",request,context);assertEquals(receipt.get("data").get("outcome").asMap(),result.get("data").asMap());assertEquals(1,count("UserTool"));assertEquals(1,sequence());
    }
    /** Test-only TCP relay drops server responses after the command result, without logging payloads. */
    static final class LostResponseProxy implements AutoCloseable {
        final AtomicBoolean suppress=new AtomicBoolean();final ServerSocket listener;final ExecutorService threads=Executors.newCachedThreadPool();volatile Socket client,server;
        LostResponseProxy() throws IOException {
            listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            threads.submit(()->{try {
                client=listener.accept();var target=URI.create(System.getenv("STAGE0_URI"));server=new Socket(target.getHost(),target.getPort());
                threads.submit(()->copy(client,server,false));copy(server,client,true);
            }catch(IOException ignored) {}});
        }
        int port() {return listener.getLocalPort();}
        void copy(Socket from,Socket to,boolean response) {
            try {var in=from.getInputStream();var out=to.getOutputStream();byte[] bytes=new byte[8192];int n;
                while((n=in.read(bytes))>=0)if(!response||!suppress.get()) {out.write(bytes,0,n);out.flush();}
            }catch(IOException ignored) {}
        }
        void disconnect() throws IOException {if(client!=null)client.close();if(server!=null)server.close();}
        public void close() throws IOException {listener.close();disconnect();threads.shutdownNow();}
    }
}
