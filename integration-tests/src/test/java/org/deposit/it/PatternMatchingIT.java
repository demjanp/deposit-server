package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** pattern Patterns and portable package contracts, exercised through native procedures. */
@Timeout(90)
class PatternMatchingIT {
    static Driver driver;
    static final String USER="typed-data-admin",PASSWORD="typed-data-account-password";
    String token,namespace,name;
    static Map<String,Object> m(Object... entries){return TestTransport.orderedMap(entries);}
    static List<org.neo4j.driver.Record> query(String q,Map<String,Object> p){try(var s=driver.session()){return s.run("CYPHER 25 "+q,p,TransactionConfig.builder().withTimeout(Duration.ofSeconds(45)).build()).list();}}
    static org.neo4j.driver.Record call(String proc,Object... args){var call=TestTransport.positionalCall(proc,args);return query(call.text(),call.parameters()).getFirst();}
    static String id(org.neo4j.driver.Record r){return r.get("node").asNode().get("id").asString();}
    static String key(){return UUID.randomUUID().toString();}
    @BeforeAll static void connect() throws Exception {driver=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")));driver.verifyConnectivity();Path secret=TestInstallationPaths.bootstrapSecret();if(Files.exists(secret))call("deposit.utils.bootstrap",Files.readString(secret).trim(),USER,PASSWORD);}
    @AfterAll static void disconnect(){if(driver!=null)driver.close();}
    @BeforeEach void setup(){token=call("deposit.utils.login",USER,PASSWORD).get("value").asString();name="Pattern-"+key();namespace=id(call("deposit.create.namespace",name,m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",namespace),token);}

    @Test void patternCrudCanonicalizationAndHistoryUndoRedo(){
        call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"))),token);
        var definition=m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code","type","String","comment","identifier")))));
        String pattern=id(call("deposit.create.pattern","Catalog",definition,m("comment","outer note"),token));
        var node=call("deposit.get.pattern",m("id",pattern),token).get("node").asNode();
        assertEquals("Catalog",node.get("name").asString());
        assertTrue(node.get("definition").asString().contains("identifier"));
        call("deposit.set.pattern",m("id",pattern),m("comment","updated"),token);
        assertEquals("updated",call("deposit.get.pattern",m("id",pattern),token).get("node").asNode().get("comment").asString());
        call("deposit.utils.undo",null,token);
        assertEquals("outer note",call("deposit.get.pattern",m("id",pattern),token).get("node").asNode().get("comment").asString());
        String undoId=call("deposit.get.revision",token).get("data").get("revisionId").asString();
        call("deposit.utils.redo",undoId,token);
        assertEquals("updated",call("deposit.get.pattern",m("id",pattern),token).get("node").asNode().get("comment").asString());
        call("deposit.delete.pattern",m("id",pattern),token);
        assertEquals(0,query("MATCH (p:Pattern {namespaceId:$ns}) RETURN count(p) AS n",m("ns",namespace)).getFirst().get("n").asLong());
    }

    @Test void patternValidationAndMatchingSeeUncommittedSchemaInSuppliedTransaction() {
        String clazz=key(),declaration=key(),object=key(),descriptor=key(),membership=key();
        var definition=m("objects",m("root",m("className","TxVisible","root",true,
            "descriptors",List.of(m("name","Code","type","String")))));
        String pattern;
        try(var session=driver.session();var tx=session.beginTransaction()) {
            tx.run("CREATE (c:Class {id:$classId,namespaceId:$ns,name:'TxVisible'}) " +
                   "CREATE (d:ClassDescriptor {id:$declarationId,namespaceId:$ns,ownerId:$classId,name:'Code',type:'String'}) " +
                   "CREATE (o:Object {id:$objectId,namespaceId:$ns}) " +
                   "CREATE (v:Descriptor {id:$descriptorId,namespaceId:$ns,ownerId:$objectId,name:'Code',value:'abc'}) " +
                   "CREATE (o)-[:MEMBER_OF {id:$membershipId,namespaceId:$ns}]->(c) " +
                   "CREATE (o)-[:HAS_DESCRIPTOR]->(v)",
                m("classId",clazz,"declarationId",declaration,"objectId",object,"descriptorId",descriptor,"membershipId",membership,"ns",namespace));
            var created=tx.run("CALL deposit.create.pattern($name,$definition,$properties,$token)",
                m("name","TxSchemaPattern","definition",definition,"properties",m(),"token",token)).single();
            pattern=id(created);
            tx.commit();
        }
        var matches=query("CALL deposit.get.objectsByPattern({id:$pattern},$values,{},$token) YIELD data RETURN data",
            m("pattern",pattern,"values",m("root",m("Code","abc")),"token",token));
        assertEquals(1,matches.size());
        assertEquals(object,matches.getFirst().get("data").get("bindings").get("root").asNode().get("id").asString());
    }

    @Test void resultingNamespaceCompatibilityAndClassRenameAreAtomic(){
        call("deposit.create.class","Find",m("descriptors",List.of(m("name","Code","type","String"))),token);
        var def=m("objects",m("root",m("className","Find","root",true,"descriptors",List.of(m("name","Code")))));
        String pattern=id(call("deposit.create.pattern","ByCode",def,m(),token));
        call("deposit.set.class","Find",m("name","Specimen"),token);
        var data=call("deposit.get.pattern",m("id",pattern),token).get("node").asNode();
        assertTrue(data.get("definition").asString().contains("Specimen"));
        var incompatible=m("objects",m("root",m("className","Specimen","root",true,"descriptors",List.of())));
        try(var s=driver.session()){
            var e=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.create.pattern","Conflict",incompatible,m(),token));
            assertTrue(e.getMessage().contains("VALIDATION_FAILED"),e.getMessage());
        }
        assertEquals(1,query("MATCH (p:Pattern {namespaceId:$ns}) RETURN count(p) AS n",m("ns",namespace)).getFirst().get("n").asLong());
    }

    @Test void patternSearchChecksSelfLoopsAndPagedBindingsAgree(){
        call("deposit.create.class","Thing",m(),token);
        String withoutLoop=id(call("deposit.create.object","Thing",m(),token));
        String withLoop=id(call("deposit.create.object","Thing",m(),token));
        call("deposit.create.relationship",withLoop,withLoop,"self",m("weight",1.0),token);
        var definition=m("objects",m("thing",m("className","Thing","root",true,
            "relationships",List.of(m("label","self","targetName","thing")))));
        String pattern=id(call("deposit.create.pattern","SelfLoop",definition,m(),token));

        var stream=query("CALL deposit.get.objectsByPattern({id:$id}, {}, {}, $token) YIELD data RETURN data",
            m("id",pattern,"token",token));
        assertEquals(1,stream.size());
        var binding=stream.getFirst().get("data").asMap();
        assertEquals("thing",binding.get("rootName"));
        var bound=(org.neo4j.driver.types.Node)((Map<?,?>)binding.get("bindings")).get("thing");
        assertEquals(withLoop,bound.get("id").asString());
        assertNotEquals(withoutLoop,bound.get("id").asString());

        var page=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),m("pageSize",1L),token)
            .get("data").asMap();
        var items=(List<?>)page.get("items");
        assertEquals(1,items.size());
        assertEquals("thing",((Map<?,?>)items.getFirst()).get("rootName"));
        assertNull(page.get("nextCursor"));
    }

    @Test void conflictingMissingDeclarationProposalsRollBackAtomically(){
        call("deposit.create.class","Find",m(),token);
        var first=m("objects",m("find",m("className","Find","root",true,
            "descriptors",List.of(m("name","Code","type","String")))));
        call("deposit.create.pattern","StringProposal",first,m(),token);
        long revision=call("deposit.get.revision",token).get("data").get("sequence").asLong();
        var conflicting=m("objects",m("find",m("className","Find","root",true,
            "descriptors",List.of(m("name","Code","type","Integer")))));
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,
            ()->call("deposit.create.pattern","IntegerProposal",conflicting,m(),token));
        assertTrue(failure.getMessage().contains("VALIDATION_FAILED"),failure.getMessage());
        assertEquals(revision,call("deposit.get.revision",token).get("data").get("sequence").asLong());
        assertEquals(1,query("MATCH (p:Pattern {namespaceId:$ns}) RETURN count(p) AS n",m("ns",namespace)).getFirst().get("n").asLong());
        var unknownKey=m("objects",m("find",m("className","Find","root",true,"descriptors",List.of(m("name","Missing")))));
        assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,
            ()->call("deposit.create.pattern","UnknownKey",unknownKey,m(),token));
    }

    @Test void disconnectedGroupsAreMatchedIndependently(){
        call("deposit.create.class","Thing",m(),token);
        call("deposit.create.class","Other",m(),token);
        String thing=id(call("deposit.create.object","Thing",m(),token));
        String other=id(call("deposit.create.object","Other",m(),token));
        call("deposit.create.relationship",thing,thing,"self",m("weight",1.0),token);
        var definition=m("objects",m(
            "thing",m("className","Thing","root",true,"relationships",List.of(m("label","self","targetName","thing"))),
            "other",m("className","Other","root",true,"relationships",List.of(m("label","self","targetName","other")))));
        String pattern=id(call("deposit.create.pattern","Disconnected",definition,m(),token));
        var first=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),m(),token).get("data").get("items").asList();
        assertEquals(1,first.size());
        call("deposit.create.relationship",other,other,"self",m("weight",1.0),token);
        var second=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),m(),token).get("data").get("items").asList();
        assertEquals(2,second.size());
        assertEquals("other",((Map<?,?>)second.getFirst()).get("rootName"));
        assertEquals("thing",((Map<?,?>)second.getLast()).get("rootName"));
    }

    private List<?> bindings(String pattern,Map<String,Object> values,Map<String,Object> paging){return call("deposit.get.objectsByPatternPage",m("id",pattern),values,m(),paging,token).get("data").get("items").asList();}
    private long sequence(){return call("deposit.get.revision",token).get("data").get("sequence").asLong();}
    private void fails(String code,Runnable action){var error=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,action::run);assertTrue(error.getMessage().contains(code),error.getMessage());}
    private String upload(String text)throws Exception{byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));String upload=call("deposit.resource.beginUpload","source ?.txt",(long)bytes.length,hash,"text/plain",token).get("data").get("uploadId").asString();call("deposit.resource.uploadChunk",upload,0L,Base64.getEncoder().encodeToString(bytes),hash,token);return call("deposit.resource.finishUpload",upload,token).get("data").get("resourceId").asString();}
    private byte[] download(Map<String,Object> outcome){try(var out=new java.io.ByteArrayOutputStream()){long size=((Number)outcome.get("bytes")).longValue();for(long offset=0;offset<size;){long length=Math.min(1_048_576L,size-offset);var chunk=call("deposit.resource.downloadChunk",outcome.get("resourceId"),offset,length,token).get("data").asMap();byte[] bytes=Base64.getDecoder().decode((String)chunk.get("bytesBase64"));assertEquals(length,bytes.length);out.write(bytes);offset+=length;}return out.toByteArray();}catch(Exception e){throw new AssertionError(e);}}
    private void retain(Map<String,Object> outcome,String suffix){String output=System.getenv("DEPOSIT_TEST_PACKAGE_OUTPUT");if(output!=null&&!output.isBlank())try{Path target=suffix.isEmpty()?Path.of(output):Path.of(output).resolveSibling("generated-"+suffix+".zip");Files.write(target,download(outcome),StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new AssertionError(e);}}

    @Test void explicitEmptyRoleAndNullPresenceHaveDifferentMatchingSemantics(){
        call("deposit.create.class","Thing",m("descriptors",List.of(m("name","Code","type","String"))),token);
        String absent=id(call("deposit.create.object","Thing",m(),token)),unknown=id(call("deposit.create.object","Thing",m("descriptors",m("Code",null)),token));
        call("deposit.create.object","Thing",m("descriptors",m("Code","known")),token);
        String pattern=id(call("deposit.create.pattern","Exact",m("objects",m("thing",m("className","Thing","root",true,"matchEmpty",true,"descriptors",List.of(m("name","Code"))))),m(),token));
        assertEquals(3,bindings(pattern,m(),m()).size());
        var empty=bindings(pattern,m("thing",m()),m());assertEquals(1,empty.size());assertEquals(absent,((Map<?,?>)((Map<?,?>)empty.getFirst()).get("bindings")).get("thing"));
        var nulls=bindings(pattern,m("thing",m("Code",null)),m());assertEquals(1,nulls.size());assertEquals(unknown,((Map<?,?>)((Map<?,?>)nulls.getFirst()).get("bindings")).get("thing"));
        call("deposit.set.pattern",m("id",pattern),m("definition",m("objects",m("thing",m("className","Thing","root",true,"matchEmpty",false,"descriptors",List.of(m("name","Code")))))),token);
        assertEquals(3,bindings(pattern,m("thing",m()),m()).size());
        assertEquals(3,bindings(pattern,m(),m()).size());
    }
    @Test void directedCyclicAliasesOverlapAndPaginationAreDeterministic(){
        call("deposit.create.class","Thing",m(),token);String a=id(call("deposit.create.object","Thing",m(),token)),b=id(call("deposit.create.object","Thing",m(),token)),c=id(call("deposit.create.object","Thing",m(),token));
        call("deposit.create.relationship",a,b,"next",m(),token);call("deposit.create.relationship",b,a,"next",m(),token);call("deposit.create.relationship",a,c,"next",m(),token);call("deposit.create.relationship",c,a,"wrong",m(),token);
        var definition=m("objects",m("first",m("className","Thing","root",true,"relationships",List.of(m("label","next","targetName","second"))),"second",m("className","Thing","relationships",List.of(m("label","next","targetName","first")))));
        String pattern=id(call("deposit.create.pattern","Cycle",definition,m(),token));String other=id(call("deposit.create.pattern","Overlap",definition,m("comment","same structure"),token));
        var all=bindings(pattern,m(),m());assertEquals(2,all.size());assertEquals(all,bindings(other,m(),m()));
        var first=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),m("pageSize",1L),token).get("data");String cursor=first.get("nextCursor").asString();
        var second=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),m("pageSize",1L,"cursor",cursor),token).get("data");assertEquals(all,List.of(first.get("items").asList().getFirst(),second.get("items").asList().getFirst()));
        call("deposit.set.pattern",m("id",pattern),m("comment","changed"),token);fails("REVISION_CONFLICT",()->bindings(pattern,m(),m("cursor",cursor)));
    }
    @Test void coordinatedBatchChecksOnlyResultingCompatibilityAndHistoryRestoresIt(){
        call("deposit.create.class","Thing",m(),token);var initial=m("objects",m("thing",m("className","Thing","root",true)));
        String one=id(call("deposit.create.pattern","One",initial,m(),token)),two=id(call("deposit.create.pattern","Two",initial,m(),token));String old=call("deposit.get.revision",token).get("data").get("revisionId").asString();
        var changed=m("objects",m("thing",m("className","Thing","root",true,"alwaysCreate",true)));
        long before=sequence();fails("VALIDATION_FAILED",()->call("deposit.set.pattern",m("id",one),m("definition",changed),token));assertEquals(before,sequence());
        call("deposit.apply.batch",List.of(m("key","one","procedure","deposit.set.pattern","arguments",List.of(m("id",one),m("definition",changed))),m("key","two","procedure","deposit.set.pattern","arguments",List.of(m("id",two),m("definition",changed)))),token);assertEquals(before+1,sequence());
        call("deposit.utils.undo",null,token);call("deposit.utils.restore",old,token);
        assertFalse(call("deposit.get.pattern",m("id",one),token).get("node").asNode().get("definition").asString().contains("\"alwaysCreate\":true"));
        long stable=sequence();fails("VALIDATION_FAILED",()->call("deposit.apply.batch",List.of(m("key","rename","procedure","deposit.set.class","arguments",List.of("Thing",m("name","Renamed"))),m("key","bad","procedure","deposit.set.pattern","arguments",List.of(m("id",one),m("definition",m("objects",m("thing",m("className","Renamed","root",true,"alwaysCreate",true))))))),token));assertEquals(stable,sequence());assertEquals("Thing",call("deposit.get.class","Thing",token).get("node").asNode().get("name").asString());
    }
    @Test void subgraphScopeBoundaryApplicablePatternsAndHistoricalResources()throws Exception{
        String resource=upload("historical resource bytes ?");
        call("deposit.create.class","A",m("descriptors",List.of(m("name","File","type","DResource"))),token);call("deposit.create.class","B",m(),token);call("deposit.create.class","Unused",m(),token);
        String a1=id(call("deposit.create.object","A",m("descriptors",m("File",m("resourceId",resource))),token)),a2=id(call("deposit.create.object","A",m(),token)),a3=id(call("deposit.create.object","A",m(),token)),b=id(call("deposit.create.object","B",m(),token));
        call("deposit.create.relationship",a1,a2,"next",m(),token);call("deposit.create.relationship",a2,a3,"next",m(),token);call("deposit.create.relationship",a2,b,"context",m(),token);
        var definition=m("objects",m("root",m("className","A","root",true),"unused",m("className","Unused","root",true)));
        String pattern=id(call("deposit.create.pattern","ApplicableFullDefinition",definition,m(),token));call("deposit.create.userTool","Opaque",m("data","  not JSON {absolute?: untouched} ?\n"),token);
        // Remove the selected Object's resource Descriptor; subgraph packages carry no history.
        call("deposit.set.object",a1,m("clearDescriptors",List.of("File")),token);
        var inline=call("deposit.get.data",List.of(a1),m("maxHops",2L,"includeHistory",true),token).get("data");var graph=inline.get("graph");assertEquals(3,graph.get("objects").size());assertEquals(1,graph.get("patterns").size());assertEquals(pattern,graph.get("patterns").get(0).get("id").asString());assertEquals(3,graph.get("classes").size());assertEquals(0,graph.get("userTools").size());assertEquals(0,graph.get("resources").size());assertEquals(false,inline.get("manifest").get("includeHistory").asBoolean());assertEquals(1,inline.get("manifest").get("omittedEdgeCount").asLong());
        var ordinary=call("deposit.get.data",List.of(a1),m("maxHops",2L,"rootClassBoundary",false),token).get("data");assertEquals(4,ordinary.get("graph").get("objects").size());
        var outcome=call("deposit.save.data",m("rootIds",List.of(a1)),m("maxHops",2L,"includeHistory",true),token).get("data").asMap();assertTrue(call("deposit.load.data",outcome.get("resourceId"),token).get("data").get("valid").asBoolean());retain(outcome,"subgraph");
    }
    @Test void exportIsOperationalAndPreparedReplayKeepsFixedBytes(){
        call("deposit.create.class","Thing",m(),token);String object=id(call("deposit.create.object","Thing",m(),token));long before=sequence();String recovery=key();
        call("deposit.action.prepare",recovery,"deposit.save.data",List.of(m(),m("includeHistory",true)),token);
        var first=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").asMap();byte[] bytes=download(first);assertEquals(before,sequence());
        var replay=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").asMap();assertEquals(first,replay);assertArrayEquals(bytes,download(replay));
        call("deposit.action.release",token);call("deposit.set.object",object,m("position",List.of(2.0,3.0)),token);assertArrayEquals(bytes,download(first));
        var second=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").asMap();assertNotEquals(first.get("resourceId"),second.get("resourceId"));assertTrue(call("deposit.load.data",first.get("resourceId"),token).get("data").get("valid").asBoolean());
    }
    @Test void declarationDependenciesRejectDeletionUntilReferencesAreRemoved(){
        call("deposit.create.class","Thing",m("descriptors",List.of(m("name","Code","type","String"))),token);String pattern=id(call("deposit.create.pattern","Depends",m("objects",m("thing",m("className","Thing","root",true,"descriptors",List.of(m("name","Code","type","String"))))),m(),token));
        long before=sequence();fails("VALIDATION_FAILED",()->call("deposit.delete.classDescriptor",m("class","Thing","name","Code"),token));assertEquals(before,sequence());
        call("deposit.apply.batch",List.of(m("key","pattern","procedure","deposit.delete.pattern","arguments",List.of(m("id",pattern))),m("key","field","procedure","deposit.delete.classDescriptor","arguments",List.of(m("class","Thing","name","Code")))),token);assertEquals(before+1,sequence());
    }
    @Test void generatedWholeNamespacePackageValidatesIndependently()throws Exception{
        String resource=upload("source package resource ?");
        var declarations=new ArrayList<Map<String,Object>>();var values=m("Count",Long.MIN_VALUE,"Unknown",null,"Shape",m("wkt","POINT Z (1 2 3)","srid",4326L,"axisOrder","xyz","dimensions",3L),"File",m("resourceId",resource));
        for(String[] field:new String[][]{{"Count","Integer"},{"Unknown","String"},{"Shape","DGeometry"},{"File","DResource"},{"Fraction","Float"},{"Flag","Boolean"},{"Date","Date"},{"Time","Time"},{"LocalTime","LocalTime"},{"DateTime","DateTime"},{"LocalDateTime","LocalDateTime"},{"Duration","Duration"},{"Point","Point"},{"List","List"},{"Dict","DDict"}})declarations.add(m("name",field[0],"type",field[1]));
        values.putAll(m("Fraction",1.25,"Flag",true,"Date","2026-10-10","Time","12:34:56+02:00","LocalTime","12:34:56","DateTime","2026-10-10T12:34:56+02:00","LocalDateTime","2026-10-10T12:34:56","Duration",m("months",1L,"days",2L,"seconds",3L,"nanoseconds",4L),"Point",m("srid",4326L,"coordinates",List.of(1.0,2.0)),"List",m("itemType","Integer","items",Arrays.asList(Long.MIN_VALUE,null,Long.MAX_VALUE)),"Dict",m("resourceId",m("type","String","value","ordinary dictionary field"),"nested",m("type","List","value",m("itemType","Integer","items",List.of(Long.MAX_VALUE))),"null",m("type","String","value",null))));
        call("deposit.create.class","Scientific",m("descriptors",declarations),token);call("deposit.create.object","Scientific",m("descriptors",values),token);
        call("deposit.create.pattern","ScientificPattern",m("objects",m("sample",m("className","Scientific","root",true))),m("comment","retained"),token);
        call("deposit.create.userTool","Opaque",m("data","  {not json} ?\n"),token);
        var outcome=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").asMap();assertEquals("ready",outcome.get("state"));var result=call("deposit.load.data",outcome.get("resourceId"),token).get("data").asMap();assertEquals(true,result.get("valid"));assertEquals("namespace",result.get("scope"));retain(outcome,"");
    }
    @Test void matchingCandidateLimitAndMoreThanOneThousandCompleteBindings(){
        call("deposit.create.class","Thing",m(),token);
        var commands=new ArrayList<Map<String,Object>>();for(int i=0;i<33;i++)commands.add(m("key","o"+i,"procedure","deposit.create.object","arguments",List.of("Thing",m())));
        call("deposit.apply.batch",commands,token);
        query("MATCH (a:Object {namespaceId:$ns}),(b:Object {namespaceId:$ns}) CREATE (a)-[:RELATED_TO {id:randomUUID(),namespaceId:$ns,label:'all'}]->(b)",m("ns",namespace));
        String pattern=id(call("deposit.create.pattern","Pairs",m("objects",m("a",m("className","Thing","root",true,"relationships",List.of(m("label","all","targetName","b"))),"b",m("className","Thing"))),m(),token));
        assertEquals(1089,query("CALL deposit.get.objectsByPattern({id:$p},{},{},$t)",m("p",pattern,"t",token)).size());
        int total=0;String cursor=null;do{var options=m("pageSize",200L);if(cursor!=null)options.put("cursor",cursor);var page=call("deposit.get.objectsByPatternPage",m("id",pattern),m(),m(),options,token).get("data");total+=page.get("items").size();cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asString();}while(cursor!=null);assertEquals(1089,total);
        String clazz=id(call("deposit.get.class","Thing",token));
        query("MATCH (c:Class {id:$c}) UNWIND range(1,968) AS i CREATE (o:Object {id:randomUUID(),namespaceId:$ns,position:[0.0,0.0]})-[:MEMBER_OF {id:randomUUID(),namespaceId:$ns}]->(c)",m("c",clazz,"ns",namespace));
        fails("LIMIT_EXCEEDED",()->call("deposit.get.objectsByPattern",m("id",pattern),m(),m(),token));
        fails("VALIDATION_FAILED",()->call("deposit.get.objectsByPattern",m("id",pattern),m(),m("candidateLimit",2000L),token));
    }
    @Test void generatedGrantIsActorBoundRoleCheckedAndDoesNotRenewOnValidation(){
        var generated=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").asMap();String resource=(String)generated.get("resourceId");
        String username="Pattern-other-"+key();call("deposit.create.user",username,PASSWORD,m(),token);call("deposit.set.namespaceRole",username,"owner",token);
        String other=call("deposit.utils.login",username,PASSWORD).get("value").asString();call("deposit.set.activeNamespace",m("id",namespace),other);
        fails("NOT_FOUND",()->call("deposit.load.data",resource,other));
        long expires=query("MATCH (g:DepositExportGrant {resourceId:$r}) RETURN g.expires AS n",m("r",resource)).getFirst().get("n").asLong();
        assertTrue(call("deposit.load.data",resource,token).get("data").get("valid").asBoolean());assertEquals(expires,query("MATCH (g:DepositExportGrant {resourceId:$r}) RETURN g.expires AS n",m("r",resource)).getFirst().get("n").asLong());
        call("deposit.set.namespaceRole",USER,"viewer",other);fails("FORBIDDEN",()->call("deposit.load.data",resource,token));fails("NOT_FOUND",()->call("deposit.resource.downloadChunk",resource,0L,1L,token));
        call("deposit.set.namespaceRole",USER,"owner",other);query("MATCH (g:DepositExportGrant {resourceId:$r}) SET g.expires=0",m("r",resource));fails("NOT_FOUND",()->call("deposit.resource.downloadChunk",resource,0L,1L,token));
    }
    @Test void concurrentDataSchemaAndPatternEditCannotProduceMixedSnapshot()throws Exception{
        call("deposit.create.class","Before",m("descriptors",List.of(m("name","Count","type","Integer"))),token);String object=id(call("deposit.create.object","Before",m("descriptors",m("Count",0L)),token));String pattern=id(call("deposit.create.pattern","Race",m("objects",m("o",m("className","Before","root",true))),m(),token));
        String exporter=call("deposit.utils.login",USER,PASSWORD).get("value").asString();call("deposit.set.activeNamespace",m("id",namespace),exporter);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var export=pool.submit(()->{start.await();try{return call("deposit.get.namespaceData",m(),exporter).get("data").asMap();}catch(org.neo4j.driver.exceptions.Neo4jException e){assertTrue(e.getMessage().contains("REVISION_CONFLICT"),e.getMessage());return null;}});
            var edit=pool.submit(()->{start.await();return call("deposit.apply.batch",List.of(m("key","value","procedure","deposit.set.object","arguments",List.of(object,m("descriptors",m("Count",1L)))),m("key","schema","procedure","deposit.set.class","arguments",List.of("Before",m("name","After"))),m("key","pattern","procedure","deposit.set.pattern","arguments",List.of(m("id",pattern),m("comment","after")))),token);});start.countDown();edit.get();var result=export.get();
            if(result!=null){var graph=(Map<?,?>)result.get("graph");var clazz=(Map<?,?>)((List<?>)graph.get("classes")).getFirst();boolean after="After".equals(clazz.get("name"));var descriptor=(Map<?,?>)((List<?>)graph.get("descriptors")).getFirst();assertEquals(after?"1":"0",descriptor.get("value"));var definition=(Map<?,?>)((Map<?,?>)((List<?>)graph.get("patterns")).getFirst()).get("definition");assertEquals(after?"After":"Before",((Map<?,?>)((Map<?,?>)definition.get("objects")).get("o")).get("className"));}
        }finally{pool.shutdownNow();}
    }

    @Test void largeInlineSnapshotStagesPackageAndCorruptSourceCannotPublish()throws Exception{
        call("deposit.create.userTool","Large",m("data","opaque ? ".repeat(150_000)),token);long before=sequence();var fallback=call("deposit.get.namespaceData",m(),token).get("data").asMap();assertEquals("ready",fallback.get("state"));assertFalse(fallback.containsKey("graph"));assertEquals(before,sequence());assertTrue(call("deposit.load.data",fallback.get("resourceId"),token).get("data").get("valid").asBoolean());
        String resource=upload("source integrity bytes");call("deposit.create.class","File",m("descriptors",List.of(m("name","File","type","DResource"))),token);call("deposit.create.object","File",m("descriptors",m("File",m("resourceId",resource))),token);
        String stored=query("MATCH (l:DepositResourceLocation {id:$r}) RETURN l.storedFilename AS f",m("r",resource)).getFirst().get("f").asString();Path path=ResourceAndGeometryIT.objectRoot(resource).resolve("v1/content").resolve(stored);byte[] original=Files.readAllBytes(path);long sequence=sequence(),grants=query("MATCH (g:DepositExportGrant {namespaceId:$ns}) RETURN count(g) AS n",m("ns",namespace)).getFirst().get("n").asLong();
        try{byte[] corrupt=original.clone();corrupt[0]^=1;Files.write(path,corrupt);fails("RESOURCE_CORRUPT",()->call("deposit.save.data",m(),m(),token));assertEquals(sequence,sequence());assertEquals(grants,query("MATCH (g:DepositExportGrant {namespaceId:$ns}) RETURN count(g) AS n",m("ns",namespace)).getFirst().get("n").asLong());}finally{Files.write(path,original);}
        assertTrue(call("deposit.load.data",call("deposit.save.data",m(),m(),token).get("data").get("resourceId").asString(),token).get("data").get("valid").asBoolean());
    }

}
