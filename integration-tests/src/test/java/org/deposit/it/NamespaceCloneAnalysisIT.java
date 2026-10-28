package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** clone import analysis, apply, stale-plan, and clone acceptance. */
@Timeout(120)
class NamespaceCloneAnalysisIT {
    static Driver driver;static final Path ROOT=TestInstallationPaths.runRoot();static final String USER="typed-data-admin",PASSWORD="typed-data-account-password";
    String token,namespace,name;
    static Map<String,Object> m(Object... entries){return TestTransport.orderedMap(entries);}
    static List<org.neo4j.driver.Record> query(String q,Map<String,Object> p){try(var s=driver.session()){return s.run("CYPHER 25 "+q,p,TransactionConfig.builder().withTimeout(Duration.ofSeconds(45)).build()).list();}}
    static org.neo4j.driver.Record call(String proc,Object... args){var call=TestTransport.positionalCall(proc,args);return query(call.text(),call.parameters()).getFirst();}
    static String id(org.neo4j.driver.Record row){return row.get("node").asNode().get("id").asString();}
    static String key(){return UUID.randomUUID().toString();}
    static void fails(String code,Runnable work){var e=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,work::run);assertTrue(e.getMessage().contains(code),e.getMessage());}
    static String login(){return call("deposit.utils.login",USER,PASSWORD).get("value").asString();}
    @BeforeAll static void connect()throws Exception{driver=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")));driver.verifyConnectivity();TestTransport.bootstrapIfFresh(driver,Duration.ofSeconds(30),USER,PASSWORD);}
    @AfterAll static void disconnect(){if(driver!=null)driver.close();}
    @BeforeEach void setup(){token=login();name="Clone-"+key();namespace=id(call("deposit.create.namespace",name,m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",namespace),token);}
    private String export(){return call("deposit.save.data",m(),m("includeHistory",true),token).get("data").get("resourceId").asString();}
    private String analyze(String packageId){return call("deposit.import.analyze",m("packageResourceId",packageId),m(),token).get("data").get("planId").asString();}
    private void assertPackagePlan(String planId,String packageId){
        var props=query("MATCH (p:DepositImportPlan {id:$id}) RETURN properties(p) AS properties",m("id",planId)).getFirst().get("properties").asMap();assertEquals(packageId,props.get("sourcePackageId"));assertEquals("ready",props.get("state"));assertTrue(props.get("targetRevision") instanceof Long);assertTrue(props.get("createdAt") instanceof Long);assertEquals(props.get("createdAt"),props.get("lastActivity"));assertEquals(86_400_000L,(Long)props.get("expiresAt")-(Long)props.get("createdAt"));assertTrue(props.get("rules") instanceof String);assertTrue(props.get("summary") instanceof String);assertFalse(props.containsKey("tableId"));assertFalse(props.containsKey("rowIdsJson"));
        Path staged=TestInstallationPaths.runRoot().resolve("resources").resolve((String)props.get("stagingPath")).normalize();assertTrue(staged.startsWith(TestInstallationPaths.runRoot().resolve("resources").normalize()));assertTrue(Files.isRegularFile(staged));assertEquals(1,query("MATCH (r:DResource {id:$id}) RETURN count(r) AS n",m("id",packageId)).getFirst().get("n").asLong());assertEquals(1,query("MATCH (r:DepositResourceLocation {id:$id}) RETURN count(r) AS n",m("id",packageId)).getFirst().get("n").asLong());String active=call("deposit.get.session",token).get("data").get("namespaceId").asString();call("deposit.set.activeNamespace",m("id",namespace),token);try{assertTrue(call("deposit.load.data",packageId,token).get("data").get("valid").asBoolean(),"Original package must remain readable while its plan owns a separate staged copy");}finally{call("deposit.set.activeNamespace",m("id",active),token);}
    }
    private String assertConflictWithoutPlan(String packageId,String expectedKind){
        String selected=call("deposit.get.session",token).get("data").get("namespaceId").asString();long revision=query("MATCH (n:Namespace {id:$ns}) RETURN n.sequence AS revision",m("ns",selected)).getFirst().get("revision").asLong();
        long plans=query("MATCH (p:DepositImportPlan {namespaceId:$ns}) RETURN count(p) AS n",m("ns",selected)).getFirst().get("n").asLong();
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.import.analyze",m("packageResourceId",packageId),m(),token));String message=failure.getMessage();
        assertTrue(message.contains("IMPORT_CONFLICT"),message);assertTrue(message.contains("\"conflicts\""),message);assertTrue(message.contains("\"kind\":\""+expectedKind+"\""),message);assertFalse(message.contains("planId"),message);
        assertEquals(revision,query("MATCH (n:Namespace {id:$ns}) RETURN n.sequence AS revision",m("ns",selected)).getFirst().get("revision").asLong());assertEquals(plans,query("MATCH (p:DepositImportPlan {namespaceId:$ns}) RETURN count(p) AS n",m("ns",selected)).getFirst().get("n").asLong());return message;
    }

    @Test void fixedSnapshotAppliesOnceAndUuidIndependentReanalysisReuses(){
        call("deposit.create.class","Artifact",m("descriptors",List.of(m("name","Code","type","String"))),token);call("deposit.create.object","Artifact",m("descriptors",m("Code","A-1")),token);String packageId=export();
        String receiverName="Clone-receiver-"+key(),receiver=id(call("deposit.create.namespace",receiverName,m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);
        String plan=analyze(packageId),recovery=key();assertPackagePlan(plan,packageId);call("deposit.action.prepare",recovery,"deposit.import.apply",List.of(plan),token);var first=call("deposit.import.apply",plan,token).get("data").asMap();var replay=call("deposit.import.apply",plan,token).get("data").asMap();assertEquals(first,replay);assertEquals(1,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());call("deposit.action.release",token);
        String second=analyze(packageId);Map<?,?> summary=(Map<?,?>)call("deposit.get.importPlan",second,token).get("data").asMap().get("summary");assertEquals(0,((Number)summary.get("createObjects")).longValue());call("deposit.import.apply",second,token);assertEquals(1,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void staleAnalysisHasNoPartialEffects(){
        call("deposit.create.class","Thing",m(),token);call("deposit.create.object","Thing",m(),token);String packageId=export();String receiverName="Clone-stale-"+key(),receiver=id(call("deposit.create.namespace",receiverName,m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);String plan=analyze(packageId);call("deposit.create.class","Concurrent",m(),token);long before=query("MATCH (n:Class {namespaceId:$ns}) RETURN count(n) AS n",m("ns",receiver)).getFirst().get("n").asLong();fails("PLAN_STALE",()->call("deposit.import.apply",plan,token));assertEquals(before,query("MATCH (n:Class {namespaceId:$ns}) RETURN count(n) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void equalMinimumMappingAmbiguityReturnsConflictWithoutPlan(){
        call("deposit.create.class","Unique",m("descriptors",List.of(m("name","Key","type","String"))),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);String packageId=export();String receiver=id(call("deposit.create.namespace","Clone-ambiguous-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);call("deposit.create.class","Unique",m("descriptors",List.of(m("name","Key","type","String"))),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);call("deposit.create.object","Unique",m("descriptors",m("Key","same")),token);
        assertConflictWithoutPlan(packageId,"mappingAmbiguity");assertEquals(2,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
    }

    @Test void nonKeyValueConflictReturnsNoPlan(){
        String sourceClass=id(call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"),m("name","Mass","type","Integer"))),token));String sourceObject=id(call("deposit.create.object","Specimen",m("descriptors",m("Code","S-1","Mass",20L)),token));call("deposit.create.pattern","SpecimenKey",m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code"))))),m(),token);String packageId=export();String receiver=id(call("deposit.create.namespace","Clone-value-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"),m("name","Mass","type","Integer"))),token);call("deposit.create.object","Specimen",m("descriptors",m("Code","S-1","Mass",10L)),token);call("deposit.create.pattern","SpecimenKey",m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code"))))),m(),token);
        assertConflictWithoutPlan(packageId,"value");assertEquals(10,query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN d.value AS value",m("ns",receiver)).getFirst().get("value").asLong());
    }

    @Test void compatibleSourcePatternsAreAddedAndSameNameDifferenceReturnsConflict(){
        call("deposit.create.class","PatternItem",m("descriptors",List.of(m("name","Code","type","String"),m("name","Measure","type","Integer"))),token);
        String sourceObject=id(call("deposit.create.object","PatternItem",m("descriptors",m("Code","P-1","Measure",5L)),token));
        var sourceDefinition=m("objects",m("item",m("className","PatternItem","root",true,"descriptors",List.of(m("name","Code")))));
        call("deposit.create.pattern","SourceIdentity",sourceDefinition,m(),token);call("deposit.create.userTool","ImportExportAutoTool",m("data","source tool"),token);String packageId=export();

        String compatible=id(call("deposit.create.namespace","Clone-compatible-pattern-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",compatible),token);
        call("deposit.create.class","PatternItem",m("descriptors",List.of(m("name","Code","type","String"),m("name","Measure","type","Integer"))),token);
        String reused=id(call("deposit.create.object","PatternItem",m("descriptors",m("Code","P-1","Measure",5L)),token));String plan=analyze(packageId);var applied=call("deposit.import.apply",plan,token).get("data");
        assertEquals(reused,applied.get("identityMappings").get("Object").asMap().get(sourceObject));
        assertEquals(1L,query("MATCH (p:Pattern {namespaceId:$ns,name:'SourceIdentity'}) RETURN count(p) AS n",m("ns",compatible)).getFirst().get("n").asLong());
        assertTrue(query("MATCH (p:Pattern {namespaceId:$ns,name:'SourceIdentity'}) RETURN p.definition AS definition",m("ns",compatible)).getFirst().get("definition").asString().contains("Code"));
        assertEquals("source tool",query("MATCH (t:UserTool {namespaceId:$ns,name:'ImportExportAutoTool'}) RETURN t.data AS data",m("ns",compatible)).getFirst().get("data").asString());

        String conflicting=id(call("deposit.create.namespace","Clone-conflicting-pattern-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",conflicting),token);
        call("deposit.create.class","PatternItem",m("descriptors",List.of(m("name","Code","type","String"),m("name","Measure","type","Integer"))),token);
        call("deposit.create.pattern","SourceIdentity",m("objects",m("item",m("className","PatternItem","root",true,"descriptors",List.of(m("name","Measure"))))),m(),token);
        call("deposit.create.userTool","ImportExportAutoTool",m("data","different tool"),token);
        String diagnostic=assertConflictWithoutPlan(packageId,"patternName");assertTrue(diagnostic.contains("\"kind\":\"userToolName\""),diagnostic);assertEquals(0L,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",conflicting)).getFirst().get("n").asLong());
    }

    @Test void keylessObjectBesideForcedNewNeighborIsCreatedWhileKeyedControlReuses(){
        call("deposit.create.class","Keyless",m(),token);call("deposit.create.class","Keyed",m("descriptors",List.of(m("name","Code","type","String"))),token);call("deposit.create.class","AlwaysNew",m(),token);
        String sourceKeyless=id(call("deposit.create.object","Keyless",m(),token)),sourceKeyed=id(call("deposit.create.object","Keyed",m("descriptors",m("Code","K-1")),token)),sourceNew=id(call("deposit.create.object","AlwaysNew",m(),token));call("deposit.create.relationship",sourceKeyless,sourceNew,"links",m(),token);call("deposit.create.relationship",sourceKeyed,sourceNew,"links",m(),token);String packageId=export();
        String receiver=id(call("deposit.create.namespace","Clone-keyless-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",receiver),token);call("deposit.create.class","Keyless",m(),token);call("deposit.create.class","Keyed",m("descriptors",List.of(m("name","Code","type","String"))),token);call("deposit.create.class","AlwaysNew",m(),token);
        String existingKeyless=id(call("deposit.create.object","Keyless",m(),token)),anotherKeyless=id(call("deposit.create.object","Keyless",m(),token)),existingKeyed=id(call("deposit.create.object","Keyed",m("descriptors",m("Code","K-1")),token));
        String plan=call("deposit.import.analyze",m("packageResourceId",packageId),m("alwaysCreate",List.of("AlwaysNew")),token).get("data").get("planId").asString();var applied=call("deposit.import.apply",plan,token).get("data");var objectMap=applied.get("identityMappings").get("Object").asMap();
        assertNotEquals(existingKeyless,objectMap.get(sourceKeyless));assertNotEquals(anotherKeyless,objectMap.get(sourceKeyless));assertNotEquals(existingKeyless,objectMap.get(sourceNew));assertEquals(existingKeyed,objectMap.get(sourceKeyed));
        assertEquals(5L,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
        assertEquals(1L,query("MATCH (:Object {id:$a})-[:RELATED_TO {label:'links'}]->(:Object {id:$b}) RETURN count(*) AS n",m("a",(String)objectMap.get(sourceKeyless),"b",(String)objectMap.get(sourceNew))).getFirst().get("n").asLong());
        assertEquals(1L,query("MATCH (:Object {id:$a})-[:RELATED_TO {label:'links'}]->(:Object {id:$b}) RETURN count(*) AS n",m("a",existingKeyed,"b",(String)objectMap.get(sourceNew))).getFirst().get("n").asLong());
    }

    @Test void subgraphPackageCanBeAnalyzedAndAppliedWithoutImportingUnselectedObjects(){
        call("deposit.create.class","SubgraphItem",m("descriptors",List.of(m("name","Code","type","String"))),token);
        call("deposit.create.object","SubgraphItem",m("descriptors",m("Code","selected")),token);
        call("deposit.create.object","SubgraphItem",m("descriptors",m("Code","excluded")),token);
        String selected=query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'Code',value:'selected'}) RETURN o.id AS id",m("ns",namespace)).getFirst().get("id").asString();
        var exported=call("deposit.save.data",m("rootIds",List.of(selected)),m(),token).get("data");
        String packageId=exported.get("resourceId").asString();
        var validation=call("deposit.load.data",packageId,token).get("data");
        assertTrue(validation.get("valid").asBoolean());
        assertEquals("subgraph",validation.get("scope").asString());
        String receiver=id(call("deposit.create.namespace","Subgraph-receiver-"+key(),m("historyMode","disabled"),token));
        call("deposit.set.activeNamespace",m("id",receiver),token);
        String plan=analyze(packageId);
        var applied=call("deposit.import.apply",plan,token).get("data");
        String mapped=applied.get("identityMappings").get("Object").get(selected).asString();
        assertNotEquals(selected,mapped);
        assertEquals(1L,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",receiver)).getFirst().get("n").asLong());
        assertEquals("selected",query("MATCH (o:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Code'}) RETURN d.value AS value",m("id",mapped)).getFirst().get("value").asString());
    }

    @Test void persistentCloneRemapsHistoryAndKeepsSelection(){
        call("deposit.create.class","Find",m(),token);call("deposit.create.object","Find",m(),token);String packageId=export();assertEquals(packageId,UUID.fromString(packageId).toString());assertTrue(call("deposit.load.data",packageId,token).get("data").get("valid").asBoolean());String original=name;call("deposit.set.namespace",m("name",name+"-source-renamed"),token);String selected=call("deposit.get.session",token).get("data").get("namespaceId").asString();var clone=call("deposit.import.namespaceData",packageId,token).get("data").asMap();String cloneId=(String)clone.get("namespaceId");assertNotEquals(namespace,cloneId);assertEquals(selected,call("deposit.get.session",token).get("data").get("namespaceId").asString());assertEquals(original,clone.get("name"));assertTrue(query("MATCH (r:Revision {namespaceId:$ns}) RETURN count(r) AS n",m("ns",cloneId)).getFirst().get("n").asLong()>=2);assertNotEquals(query("MATCH (n:Namespace {id:$a}) RETURN n.headRevisionId AS id",m("a",namespace)).getFirst().get("id").asString(),query("MATCH (n:Namespace {id:$a}) RETURN n.headRevisionId AS id",m("a",cloneId)).getFirst().get("id").asString());
    }
}
