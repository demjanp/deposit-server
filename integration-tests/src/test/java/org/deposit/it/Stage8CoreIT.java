package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Stage 8 import analysis, apply, stale-plan, and clone acceptance. */
@Timeout(120)
class Stage8CoreIT {
    static Driver driver;static final Path ROOT=Path.of("C:/data_processed/Deposit_v2/stage0/runs/stage0-host");static final String USER="stage1-admin",PASSWORD="stage1-account-password";
    String token,namespace,name;
    static Map<String,Object> m(Object... entries){var map=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)map.put((String)entries[i],entries[i+1]);return map;}
    static List<org.neo4j.driver.Record> query(String q,Map<String,Object> p){try(var s=driver.session()){return s.run("CYPHER 25 "+q,p,TransactionConfig.builder().withTimeout(Duration.ofSeconds(45)).build()).list();}}
    static org.neo4j.driver.Record call(String proc,Object... args){var p=new LinkedHashMap<String,Object>();var names=new ArrayList<String>();for(int i=0;i<args.length;i++){p.put("p"+i,args[i]);names.add("$p"+i);}return query("CALL "+proc+"("+String.join(",",names)+")",p).getFirst();}
    static String id(org.neo4j.driver.Record row){return row.get("node").asNode().get("id").asString();}
    static String key(){return UUID.randomUUID().toString();}
    static void fails(String code,Runnable work){var e=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,work::run);assertTrue(e.getMessage().contains(code),e.getMessage());}
    static String login(){return call("deposit.utils.login",USER,PASSWORD).get("value").asString();}
    @BeforeAll static void connect()throws Exception{driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")));driver.verifyConnectivity();Path secret=ROOT.resolve("neo4j/conf/deposit-bootstrap.secret");if(Files.exists(secret))call("deposit.utils.bootstrap",Files.readString(secret).trim(),USER,PASSWORD);}
    @AfterAll static void disconnect(){if(driver!=null)driver.close();}
    @BeforeEach void setup(){token=login();name="Stage8-"+key();namespace=id(call("deposit.create.namespace",name,m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",namespace),token);}
    private String export(){return call("deposit.save.data",m(),m("includeHistory",true),token).get("data").get("resourceId").asString();}
    private String analyze(String packageId){return call("deposit.import.analyze",m("packageResourceId",packageId),m(),token).get("data").get("planId").asString();}

    @Test void fixedSnapshotAppliesOnceAndUuidIndependentReanalysisReuses(){
        call("deposit.create.class","Artifact",m("descriptors",List.of(m("name","Code","type","String"))),token);call("deposit.create.object","Artifact",m("descriptors",m("Code","A-1")),token);String packageId=export();
        String receiverName="Stage8-receiver-"+key(),receiver=id(call("deposit.create.namespace",receiverName,m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);
        String plan=analyze(packageId),recovery=key();call("deposit.action.prepare",recovery,"deposit.import.apply",List.of(plan,List.of()),token);var first=call("deposit.import.apply",plan,List.of(),token).get("data").asMap();var replay=call("deposit.import.apply",plan,List.of(),token).get("data").asMap();assertEquals(first,replay);assertEquals(1,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());call("deposit.action.release",token);
        String second=analyze(packageId);Map<?,?> summary=(Map<?,?>)call("deposit.get.importPlan",second,token).get("data").asMap().get("summary");assertEquals(0,((Number)summary.get("createObjects")).longValue());call("deposit.import.apply",second,List.of(),token);assertEquals(1,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void staleAnalysisHasNoPartialEffects(){
        call("deposit.create.class","Thing",m(),token);call("deposit.create.object","Thing",m(),token);String packageId=export();String receiverName="Stage8-stale-"+key(),receiver=id(call("deposit.create.namespace",receiverName,m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);String plan=analyze(packageId);call("deposit.create.class","Concurrent",m(),token);long before=query("MATCH (n:Class {namespaceId:$ns}) RETURN count(n) AS n",m("ns",receiver)).getFirst().get("n").asLong();fails("PLAN_STALE",()->call("deposit.import.apply",plan,List.of(),token));assertEquals(before,query("MATCH (n:Class {namespaceId:$ns}) RETURN count(n) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void equalMinimumMappingAmbiguityRequiresAnAdvertisedChoice(){
        call("deposit.create.class","Unique",m("descriptors",List.of(m("name","Key","type","String"))),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);String packageId=export();String receiver=id(call("deposit.create.namespace","Stage8-ambiguous-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);call("deposit.create.class","Unique",m("descriptors",List.of(m("name","Key","type","String"))),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);
        String plan=analyze(packageId);var data=call("deposit.get.importPlan",plan,token).get("data").asMap();var conflicts=(List<?>)data.get("conflicts");assertTrue(conflicts.stream().anyMatch(c->"mappingAmbiguity".equals(((Map<?,?>)c).get("kind"))));
        String target=query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Key'}) RETURN o.id AS id ORDER BY id LIMIT 1",m("ns",receiver)).getFirst().get("id").asString();call("deposit.import.apply",plan,List.of(m("conflictId","mapping","choice","mapTarget","targetId",target)),token);assertEquals(2,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void nonKeyValueConflictSupportsKeepTargetAndUseSource(){
        String sourceClass=id(call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"),m("name","Mass","type","Integer"))),token));String sourceObject=id(call("deposit.create.object","Specimen",m("descriptors",m("Code","S-1","Mass",20L)),token));call("deposit.create.pattern","SpecimenKey",m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code"))))),m(),token);String packageId=export();String receiver=id(call("deposit.create.namespace","Stage8-value-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"),m("name","Mass","type","Integer"))),token);call("deposit.create.object","Specimen",m("descriptors",m("Code","S-1","Mass",10L)),token);call("deposit.create.pattern","SpecimenKey",m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code"))))),m(),token);
        String plan=analyze(packageId);var conflicts=(List<?>)call("deposit.get.importPlan",plan,token).get("data").asMap().get("conflicts");Map<?,?> valueConflict=(Map<?,?>)conflicts.stream().filter(c->"value".equals(((Map<?,?>)c).get("kind"))).findFirst().orElseThrow();String conflictId=(String)valueConflict.get("conflictId");call("deposit.import.apply",plan,List.of(m("conflictId",conflictId,"choice","keepTarget")),token);assertEquals(10,query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN d.value AS value",m("ns",receiver)).getFirst().get("value").asLong());
        String sourcePlan=analyze(packageId);var sourceConflicts=(List<?>)call("deposit.get.importPlan",sourcePlan,token).get("data").asMap().get("conflicts");Map<?,?> sourceConflict=(Map<?,?>)sourceConflicts.stream().filter(c->"value".equals(((Map<?,?>)c).get("kind"))).findFirst().orElseThrow();call("deposit.import.apply",sourcePlan,List.of(m("conflictId",sourceConflict.get("conflictId"),"choice","useSource")),token);assertEquals(20,query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN d.value AS value",m("ns",receiver)).getFirst().get("value").asLong());
    }

    @Test void persistentCloneRemapsHistoryAndKeepsSelection(){
        call("deposit.create.class","Find",m(),token);call("deposit.create.object","Find",m(),token);String packageId=export();assertEquals(packageId,UUID.fromString(packageId).toString());assertTrue(call("deposit.load.data",packageId,token).get("data").get("valid").asBoolean());String original=name;call("deposit.set.namespace",m("name",name+"-source-renamed"),token);String selected=call("deposit.get.session",token).get("data").get("namespaceId").asString();var clone=call("deposit.import.namespaceData",packageId,token).get("data").asMap();String cloneId=(String)clone.get("namespaceId");assertNotEquals(namespace,cloneId);assertEquals(selected,call("deposit.get.session",token).get("data").get("namespaceId").asString());assertEquals(original,clone.get("name"));assertTrue(query("MATCH (r:Revision {namespaceId:$ns}) RETURN count(r) AS n",m("ns",cloneId)).getFirst().get("n").asLong()>=2);assertNotEquals(query("MATCH (n:Namespace {id:$a}) RETURN n.headRevisionId AS id",m("a",namespace)).getFirst().get("id").asString(),query("MATCH (n:Namespace {id:$a}) RETURN n.headRevisionId AS id",m("a",cloneId)).getFirst().get("id").asString());
    }
}
