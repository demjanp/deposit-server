package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.driver.*;

/** Independent Bolt client: no Deposit implementation or embedded harness dependencies. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value=60, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class RuntimeIT {
    static Driver driver;
    static String namespace;
    static String otherNamespace;
    static final String PASSWORD = Objects.requireNonNull(System.getenv("STAGE0_PASSWORD"), "STAGE0_PASSWORD required");
    static final String URI = Objects.requireNonNull(System.getenv("STAGE0_URI"), "STAGE0_URI required");
    static final boolean VERIFY_ONLY = "verify".equals(System.getenv("STAGE0_TEST_MODE"));
    static final Config CONFIG = Config.builder().withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build();

    @BeforeAll static void connect() {
        driver = GraphDatabase.driver(URI, AuthTokens.basic("neo4j", PASSWORD), CONFIG);
        driver.verifyConnectivity();
    }
    @AfterAll static void close() { if (driver != null) driver.close(); }
    static List<org.neo4j.driver.Record> query(String cypher, Map<String,Object> params) {
        try (var session=driver.session()) {return session.run("CYPHER 25 " + cypher, params,
            TransactionConfig.builder().withTimeout(Duration.ofSeconds(20)).build()).list();}
    }
    static List<org.neo4j.driver.Record> query(String cypher) {return query(cypher, Map.of());}
    static Map<String,Object> args(String ns, String id, String wkt, Long srid, String failure) {
        var args=new HashMap<String,Object>();
        args.put("ns",ns); args.put("id",id); args.put("wkt",wkt); args.put("srid",srid); args.put("failure",failure);
        return args;
    }
    static void write(String ns,String id,String wkt,Long srid,String failure) {
        query("CALL stage0.geometry.write($ns,$id,$wkt,$srid,$failure)",args(ns,id,wkt,srid,failure));
    }
    static Map<String,Object> inspect(String ns, Long srid) {
        var args=new HashMap<String,Object>();args.put("ns",ns);args.put("srid",srid);
        return query("CALL stage0.geometry.inspect($ns,$srid) YIELD data RETURN data",args).getFirst().get("data").asMap();
    }

    @Test @Order(1) void discoveryAndRuntime() {
        var rows=query("CALL deposit.get.info()"); assertEquals(1,rows.size());
        assertEquals(Map.of("version","0.0.0-stage0"), rows.getFirst().get("data").asMap());
        var meta=rows.getFirst().get("meta").asMap();
        assertEquals(Set.of("namespaceId","revision","operationId","requestId","nextCursor"),meta.keySet());
        for (String key:List.of("namespaceId","revision","operationId","nextCursor")) assertNull(meta.get(key));
        assertNotNull(UUID.fromString((String)meta.get("requestId")));
        assertEquals(System.getenv("STAGE0_NEO4J_VERSION"),query("RETURN apoc.version() AS version").getFirst().get("version").asString());
        assertEquals(System.getenv("STAGE0_NEO4J_VERSION"),query("CALL dbms.components() YIELD versions RETURN versions[0] AS version").getFirst().get("version").asString());
        assertEquals("CYPHER_25",query("SHOW SETTINGS 'db.query.default_language' YIELD value RETURN value").getFirst().get("value").asString());
    }

    @Test @Order(2) void rejectsInvalidAuthentication() {
        for (AuthToken token:List.of(AuthTokens.none(),AuthTokens.basic("neo4j","incorrect-stage0-password"))) {
            try(var rejected=GraphDatabase.driver(URI,token,CONFIG)) {
                assertThrows(org.neo4j.driver.exceptions.AuthenticationException.class,rejected::verifyConnectivity);
            }
        }
    }

    @Test @Order(3) void fixtureAndTransactionPersistence() throws Exception {
        var existing=query("MATCH (n:Stage0Namespace) RETURN n.name AS name,n.id AS id ORDER BY name");
        if (existing.isEmpty()) {
            assertFalse(VERIFY_ONLY,"Restart verification must not reseed missing state");
            query("CREATE (:Stage0Lock {id:'registry'})");
            query("UNWIND ['N1','N2'] AS name CREATE (:Stage0Namespace {name:name,id:randomUUID()})");
            query("UNWIND ['C1','C2','S1','S2','S3'] AS name CREATE (:Stage0Fixture {name:name,id:randomUUID()})");
            query("MATCH (s:Stage0Fixture) WHERE s.name IN ['S1','S2'] SET s.mass=CASE s.name WHEN 'S1' THEN 10 ELSE 20 END WITH s MATCH (c:Stage0Fixture {name:'C1'}) CREATE (s)-[:STAGE0_FOUND_IN]->(c)");
            existing=query("MATCH (n:Stage0Namespace) RETURN n.name AS name,n.id AS id ORDER BY name");
        }
        assertEquals(2,existing.size()); namespace=existing.get(0).get("id").asString(); otherNamespace=existing.get(1).get("id").asString();
        var fixtures=query("MATCH (n:Stage0Fixture) RETURN n.name AS name,n.id AS id,n.mass AS mass ORDER BY name");
        assertEquals(List.of("C1","C2","S1","S2","S3"),fixtures.stream().map(r->r.get("name").asString()).toList());
        assertEquals(10,fixtures.get(2).get("mass").asInt()); assertEquals(20,fixtures.get(3).get("mass").asInt()); assertTrue(fixtures.get(4).get("mass").isNull());
        assertEquals(2,query("MATCH (:Stage0Fixture)-[:STAGE0_FOUND_IN]->(:Stage0Fixture {name:'C1'}) RETURN count(*) AS n").getFirst().get("n").asInt());
        assertEquals(0,query("MATCH (:Stage0Fixture {name:'C2'})--() RETURN count(*) AS n").getFirst().get("n").asInt());
        try(var session=driver.session();var tx=session.beginTransaction()) {tx.run("CREATE (:Stage0Uncommitted {name:'must-not-persist'})").consume(); tx.rollback();}
        assertEquals(0,query("MATCH (n:Stage0Uncommitted) RETURN count(n) AS n").getFirst().get("n").asInt());
        var evidence=Path.of(System.getenv("STAGE0_EVIDENCE")); Files.createDirectories(evidence);
        var mapping=Stream.concat(existing.stream(),fixtures.stream()).map(r->r.get("name").asString()+"\t"+r.get("id").asString()).toList();
        var baseline=Path.of(System.getenv("STAGE0_BASELINE_MAP"));
        if(Files.exists(baseline)) assertEquals(Files.readAllLines(baseline),mapping,"Fixture UUIDs must survive restart unchanged");
        else {
            assertFalse(VERIFY_ONLY,"Persistence verification requires the original seed UUID map");
            Files.write(baseline,mapping,java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        Files.write(evidence.resolve("fixture-uuid-map.tsv"),mapping);
        assertIndependentSessions();
    }
    static void assertIndependentSessions() {
        try(var a=driver.session(); var b=driver.session();var tx=a.beginTransaction()) {
            tx.run("CREATE (:Stage0Uncommitted)").consume();
            assertEquals(0,b.run("MATCH (n:Stage0Uncommitted) RETURN count(n) AS n").single().get("n").asInt());
            tx.rollback();
        }
    }

    record Geometry(String name,String wkt,List<Double> bounds,List<Double> first) {}
    static Stream<Geometry> geometries() {
        List<Geometry> fixtures=new ArrayList<>();
        for(boolean xyz:List.of(false,true)) {
            String z=xyz?" Z":"";
            String a=xyz?"1 2 3":"1 2", b=xyz?"5 2 4":"5 2", c=xyz?"5 6 5":"5 6", d=xyz?"1 6 6":"1 6";
            String h=xyz?"2 3 7,2 4 8,3 4 9,3 3 10,2 3 7":"2 3,2 4,3 4,3 3,2 3";
            String ring=a+","+b+","+c+","+d+","+a;
            String[] wkts={"POINT"+z+" ("+a+")","LINESTRING"+z+" ("+a+","+c+")",
                "POLYGON"+z+" (("+ring+"),("+h+"))","MULTIPOINT"+z+" (("+a+"),("+c+"))",
                "MULTILINESTRING"+z+" (("+a+","+b+"),("+c+","+d+"))",
                "MULTIPOLYGON"+z+" ((("+ring+"),("+h+")),(("+(xyz?"10 10 11,12 10 12,12 12 13,10 12 14,10 10 11":"10 10,12 10,12 12,10 12,10 10")+")))"};
            for(int i=0;i<wkts.length;i++) fixtures.add(new Geometry((xyz?"xyz-":"xy-")+i,wkts[i],
                i==0?List.of(1d,2d,1d,2d):i==5?List.of(1d,2d,12d,12d):List.of(1d,2d,5d,6d),xyz?List.of(1d,2d,3d):List.of(1d,2d)));
        }
        return fixtures.stream();
    }

    @ParameterizedTest @MethodSource("geometries") @Order(4)
    void geometryRoundTrip(Geometry fixture) {
        String identity;
        if(!VERIFY_ONLY) {
            var existing=query("MERGE (g:Stage0Geometry {namespace:$ns,id:$id}) ON CREATE SET g.wkt=$wkt RETURN elementId(g) AS id",Map.of("ns",namespace,"id",fixture.name(),"wkt",fixture.wkt()));
            identity=existing.getFirst().get("id").asString();
            write(namespace,fixture.name(),fixture.wkt(),null,"");
            write(namespace,fixture.name(),fixture.wkt(),null,""); // repeated edits must retain identity and one membership
        } else identity=query("MATCH (g:Stage0Geometry {namespace:$ns,id:$id}) RETURN elementId(g) AS id",Map.of("ns",namespace,"id",fixture.name())).getFirst().get("id").asString();
        var state=inspect(namespace,null);
        @SuppressWarnings("unchecked") var entries=(List<Map<String,Object>>)state.get("entries");
        var matches=entries.stream().filter(e->fixture.name().equals(e.get("id"))).toList();
        assertEquals(1,matches.size()); var item=matches.getFirst();
        assertEquals(fixture.wkt(),item.get("wkt")); assertEquals(fixture.bounds(),item.get("bbox"));
        assertEquals(identity,item.get("elementId"));
        assertEquals(expectedCoordinates(fixture.wkt()),item.get("coordinates")); assertNull(state.get("crs"));
    }

    // Independent decoder for the fixed numeric WKT fixtures; no Spatial/JTS implementation helpers.
    static List<List<Double>> expectedCoordinates(String wkt) {
        int dimensions=wkt.contains(" Z ")?3:2;
        var numbers=Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(wkt).results().map(m->Double.valueOf(m.group())).toList();
        var coordinates=new ArrayList<List<Double>>();
        for(int offset=0;offset<numbers.size();offset+=dimensions) coordinates.add(numbers.subList(offset,offset+dimensions));
        return coordinates;
    }

    @Test @Order(5) void rollbackAtEachSpatialBoundary() {
        for(String point:List.of("layer","removal","update","registration")) {
            String fresh=UUID.randomUUID().toString();
            var creationError=assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->write(fresh,"fail-new-"+point,"POINT Z (9 8 7)",null,point));
            assertTrue(creationError.getMessage().contains("STAGE0_INJECTED_"+point),"Must reach the requested injection point, not fail during linkage");
            assertEquals(Map.of("exists",false),inspect(fresh,null));
            assertEquals(0,query("MATCH (g:Stage0Geometry {id:$id}) RETURN count(g) AS n",Map.of("id","fail-new-"+point)).getFirst().get("n").asInt());
            Map<String,Object> before=inspect(namespace,null);
            var editError=assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->write(namespace,"xyz-0","POINT Z (90 80 70)",null,point));
            assertTrue(editError.getMessage().contains("STAGE0_INJECTED_"+point),"Must reach the requested edit injection point");
            assertEquals(before,inspect(namespace,null));
        }
    }

    @Test @Order(6) void editsLayerMovementAndNamespaceIsolation() {
        if(!VERIFY_ONLY) {
            write(namespace,"moving","POINT Z (11 12 13)",4326L,"");
            assertNotNull(inspect(namespace,4326L).get("crs"),"Known EPSG CRS must resolve");
            String identity=query("MATCH (g:Stage0Geometry {namespace:$ns,id:'moving'}) RETURN elementId(g) AS id",Map.of("ns",namespace)).getFirst().get("id").asString();
            write(namespace,"moving","POINT Z (21 22 23)",999999999L,"");
            assertEquals(identity,query("MATCH (g:Stage0Geometry {namespace:$ns,id:'moving'}) RETURN elementId(g) AS id",Map.of("ns",namespace)).getFirst().get("id").asString());
            write(otherNamespace,"moving","POINT Z (31 32 33)",999999999L,"");
            write(namespace,"moving","POINT Z (21 22 23)",999999999L,"");
        }
        assertEquals(0L,inspect(namespace,4326L).get("count"));
        assertNotNull(inspect(namespace,4326L).get("crs"));
        assertNull(inspect(namespace,4326L).get("bounds"));
        assertEquals(1L,inspect(namespace,999999999L).get("count"));
        assertEquals(1L,inspect(otherNamespace,999999999L).get("count"));
        assertNull(inspect(namespace,999999999L).get("crs"));
        assertEquals(List.of(21d,22d,21d,22d),inspect(namespace,999999999L).get("bounds"));
        assertEquals(List.of(31d,32d,31d,32d),inspect(otherNamespace,999999999L).get("bounds"));
        for(String point:List.of("layer","removal","update","registration")) {
            var before=inspect(namespace,999999999L);
            var empty=inspect(namespace,4326L);
            var error=assertThrows(org.neo4j.driver.exceptions.ClientException.class,()->write(namespace,"moving","POINT Z (90 80 70)",4326L,point));
            assertTrue(error.getMessage().contains("STAGE0_INJECTED_"+point),error.getMessage());
            assertEquals(before,inspect(namespace,999999999L));
            assertEquals(empty,inspect(namespace,4326L));
        }
    }

    @Test @Order(7) void concurrentRegistrationAndPersistentGeometryIdentity() throws Exception {
        if(!VERIFY_ONLY) {
            var executor=Executors.newFixedThreadPool(2);
            var barrier=new CyclicBarrier(2);
            try {
                var tasks=executor.invokeAll(List.of(
                    ()->{barrier.await(10,TimeUnit.SECONDS);write(namespace,"concurrent","POINT Z (41 42 43)",null,"");return true;},
                    ()->{barrier.await(10,TimeUnit.SECONDS);write(namespace,"concurrent","POINT Z (41 42 43)",null,"");return true;}
                ));
                for(var task:tasks) task.get(30,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();}
        }
        @SuppressWarnings("unchecked") var entries=(List<Map<String,Object>>)inspect(namespace,null).get("entries");
        assertEquals(1,entries.stream().filter(e->"concurrent".equals(e.get("id"))).count());
        assertEquals(List.of(1d,2d,41d,42d),inspect(namespace,null).get("bounds"));
        var rows=query("MATCH (g:Stage0Geometry) RETURN g.namespace AS ns,g.id AS id,elementId(g) AS elementId,g.wkt AS wkt,g.srid AS srid ORDER BY ns,id");
        var state=rows.stream().map(r->r.get("ns").asString()+"\t"+r.get("id").asString()+"\t"+r.get("elementId").asString()+"\t"+r.get("wkt").asString()+"\t"+r.get("srid")).toList();
        var baseline=Path.of(System.getenv("STAGE0_BASELINE_MAP")+".geometry");
        if(Files.exists(baseline)) assertEquals(Files.readAllLines(baseline),state,"Geometry identity and coordinates must survive restart");
        else {assertFalse(VERIFY_ONLY,"Geometry baseline is required");Files.write(baseline,state,java.nio.file.StandardOpenOption.CREATE_NEW);}
        Files.write(Path.of(System.getenv("STAGE0_EVIDENCE"),"geometry-state.tsv"),state);
        Files.writeString(Path.of(System.getenv("STAGE0_EVIDENCE"),"spatial-index-state.txt"),inspect(namespace,null)+"\n"+inspect(namespace,4326L)+"\n"+inspect(namespace,999999999L)+"\n"+inspect(otherNamespace,999999999L));
    }

    @Test @Order(8) void callerRollbackIncludesSpatialGraphAndIndex() {
        String fresh=UUID.randomUUID().toString();
        try(var session=driver.session();var transaction=session.beginTransaction()) {
            transaction.run("CYPHER 25 CREATE (:Stage0Geometry {namespace:$ns,id:'client-rollback',wkt:'POINT Z (51 52 53)'})",Map.of("ns",fresh)).consume();
            transaction.run("CYPHER 25 CALL stage0.geometry.write($ns,'client-rollback','POINT Z (51 52 53)',null,'')",Map.of("ns",fresh)).consume();
            transaction.rollback();
        }
        assertEquals(Map.of("exists",false),inspect(fresh,null));
        assertEquals(0,query("MATCH (g:Stage0Geometry {namespace:$ns}) RETURN count(g) AS n",Map.of("ns",fresh)).getFirst().get("n").asInt());
    }
}
