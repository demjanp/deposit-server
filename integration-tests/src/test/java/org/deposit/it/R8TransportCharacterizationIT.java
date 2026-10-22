package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;

/** Independent characterization of the R8 fixture mechanics before extraction. */
class R8TransportCharacterizationIT {
    private static final Object ROW = proxy(org.neo4j.driver.Record.class, (p,m,a) -> defaultValue(m.getReturnType()));
    private static final Map<String,Object> NESTED = Map.of("inner", Arrays.asList("hostile $p0 ; CALL x()", 2L));

    @Test void orderedMapFixturesRetainNullDuplicateMutationAndInvalidInputBehavior() throws Exception {
        List<Map<String,Object>> fixtures = List.of(
            NamespaceCloneAnalysisIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            PatternMatchingIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            PreparedActionReplayIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            TabularAndSpatialFormatsIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            ShutdownDrainIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            DataImportExportIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            BrowserSessionIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            AuthenticationNamespaceLifecycleIT.m("first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null),
            (Map<String,Object>) invoke(ScientificWorkflowIT.class, "m", new Class<?>[]{Object[].class}, (Object)new Object[]{"first", null, "nested", NESTED, "duplicate", 1L, "middle", "v", "duplicate", null})
        );
        List<Map<String,Object>> nullKeyFixtures = List.of(
            NamespaceCloneAnalysisIT.m(null, "null-key", "first", null),
            PatternMatchingIT.m(null, "null-key", "first", null),
            PreparedActionReplayIT.m(null, "null-key", "first", null),
            TabularAndSpatialFormatsIT.m(null, "null-key", "first", null),
            ShutdownDrainIT.m(null, "null-key", "first", null),
            DataImportExportIT.m(null, "null-key", "first", null),
            BrowserSessionIT.m(null, "null-key", "first", null),
            AuthenticationNamespaceLifecycleIT.m(null, "null-key", "first", null),
            (Map<String,Object>) invoke(ScientificWorkflowIT.class, "m", new Class<?>[]{Object[].class}, (Object)new Object[]{null, "null-key", "first", null})
        );
        for (Map<String,Object> fixture : nullKeyFixtures) {
            assertInstanceOf(LinkedHashMap.class, fixture);
            assertEquals(Arrays.asList(null, "first"), new ArrayList<>(fixture.keySet()));
            assertEquals("null-key", fixture.get(null));
            assertTrue(fixture.containsKey("first"));
            assertNull(fixture.get("first"));
        }
        var expected = new LinkedHashMap<String,Object>();
        expected.put("first", null); expected.put("nested", NESTED); expected.put("duplicate", null); expected.put("middle", "v");
        for (Map<String,Object> fixture : fixtures) {
            assertInstanceOf(LinkedHashMap.class, fixture);
            assertEquals(List.of("first", "nested", "duplicate", "middle"), new ArrayList<>(fixture.keySet()));
            assertEquals(expected, fixture);
            fixture.put("mutable", "yes");
            assertEquals("yes", fixture.get("mutable"));
            assertSame(NESTED, fixture.get("nested"));
        }
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> NamespaceCloneAnalysisIT.m("odd"));
        assertThrows(ClassCastException.class, () -> PatternMatchingIT.m(1, "value"));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> TabularAndSpatialFormatsIT.m("odd"));
        assertThrows(ClassCastException.class, () -> ShutdownDrainIT.m(1, "value"));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> AuthenticationNamespaceLifecycleIT.m("odd"));
        assertThrows(ClassCastException.class, () -> AuthenticationNamespaceLifecycleIT.m(1, "value"));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> invokeUnchecked(DataImportExportIT.class, "m", new Class<?>[]{Object[].class}, (Object)new Object[]{"odd"}));
        assertThrows(ClassCastException.class, () -> invokeUnchecked(ScientificWorkflowIT.class, "m", new Class<?>[]{Object[].class}, (Object)new Object[]{1, "value"}));
    }

    @Test void positionalBoltWrappersKeepExactPrefixTimeoutParametersAndSessionOwnership() throws Exception {
        Object[] args = {null, NESTED, "x '$p2' ) ; RETURN 9", Arrays.asList(1L, null)};
        List<CallCase> cases = List.of(
            new CallCase("NamespaceCloneAnalysisIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 45, true),
            new CallCase("PatternMatchingIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 45, true),
            new CallCase("PreparedActionReplayIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 45, true),
            new CallCase("DataImportExportIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 45, true),
            new CallCase("BrowserSessionIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 60, true),
            new CallCase("TabularAndSpatialFormatsIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", null, true),
            new CallCase("ShutdownDrainIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", null, true)
        );
        for (CallCase c : cases) {
            Capture capture = installDriver(c.owner());
            assertSame(ROW, invokeCall(c, args));
            assertCallCapture(capture, c.query(), c.timeoutSeconds(), args);

            Capture emptyArgs = installDriver(c.owner());
            assertSame(ROW, invokeCall(c, new Object[0]));
            assertCallCapture(emptyArgs, "CYPHER 25 CALL fixture.proc()", c.timeoutSeconds(), new Object[0]);

            Capture multiple = installDriver(c.owner());
            Object secondRow = proxy(org.neo4j.driver.Record.class, (p,m,a) -> defaultValue(m.getReturnType()));
            multiple.rows = List.of((org.neo4j.driver.Record)ROW, (org.neo4j.driver.Record)secondRow);
            assertSame(ROW, invokeCall(c, args));
            assertEquals(1, multiple.listCalls);
            assertEquals(1, multiple.sessionCloses);

            Capture emptyResult = installDriver(c.owner());
            emptyResult.rows = List.of();
            assertThrows(NoSuchElementException.class, () -> invokeCallUnchecked(c, args));
            assertEquals(1, emptyResult.listCalls);
            assertEquals(1, emptyResult.sessionCloses);

            Capture runFailure = installDriver(c.owner());
            runFailure.failRun = true;
            IllegalStateException original = new IllegalStateException("run failure identity");
            runFailure.runFailure = original;
            assertSame(original, assertThrows(IllegalStateException.class, () -> invokeCallUnchecked(c, args)));
            assertEquals(0, runFailure.listCalls);
            assertEquals(1, runFailure.sessionCloses);
        }
        Capture scientific = installDriver("ScientificWorkflowIT");
        CallCase scientificCase = new CallCase("ScientificWorkflowIT", "call", "CYPHER 25 CALL fixture.proc($p0,$p1,$p2,$p3)", 60, true);
        assertSame(ROW, invokeCall(scientificCase, args));
        assertCallCapture(scientific, scientificCase.query(), scientificCase.timeoutSeconds(), args);

        Capture scientificEmpty = installDriver("ScientificWorkflowIT");
        assertSame(ROW, invokeCall(scientificCase, new Object[0]));
        assertCallCapture(scientificEmpty, "CYPHER 25 CALL fixture.proc()", 60, new Object[0]);

        Capture scientificMultiple = installDriver("ScientificWorkflowIT");
        scientificMultiple.rows = List.of((org.neo4j.driver.Record)ROW, (org.neo4j.driver.Record)proxy(org.neo4j.driver.Record.class, (p,m,a) -> defaultValue(m.getReturnType())));
        assertSame(ROW, invokeCall(scientificCase, args));
        assertEquals(1, scientificMultiple.sessionCloses);

        Capture scientificEmptyResult = installDriver("ScientificWorkflowIT"); scientificEmptyResult.rows = List.of();
        assertThrows(NoSuchElementException.class, () -> invokeCallUnchecked(scientificCase, args));
        assertEquals(1, scientificEmptyResult.sessionCloses);

        Capture scientificRunFailure = installDriver("ScientificWorkflowIT"); scientificRunFailure.failRun = true;
        IllegalStateException scientificOriginal = new IllegalStateException("scientific run failure identity");
        scientificRunFailure.runFailure = scientificOriginal;
        assertSame(scientificOriginal, assertThrows(IllegalStateException.class, () -> invokeCallUnchecked(scientificCase, args)));
        assertEquals(1, scientificRunFailure.sessionCloses);

        Capture failure = installDriver("BrowserSessionIT"); failure.failList = true;
        failure.listFailure = new IllegalStateException("result materialization failed");
        assertSame(failure.listFailure, assertThrows(IllegalStateException.class, () -> BrowserSessionIT.call("fixture.proc", args)));
        assertEquals(1, failure.sessionCloses, "session closes when eager result materialization fails");
    }

    @Test void authenticationQueryKeepsPrefixTimeoutEagerMaterializationAndOwnership() throws Exception {
        Capture capture = installDriver("AuthenticationNamespaceLifecycleIT");
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("value", NESTED);
        assertSame(capture.rows, AuthenticationNamespaceLifecycleIT.query("RETURN $value AS value", parameters));
        assertEquals("CYPHER 25 RETURN $value AS value", capture.query);
        assertSame(parameters, capture.parameters);
        assertEquals(Duration.ofSeconds(30), capture.timeout.timeout());
        assertSame(capture.suppliedDriver, capture.driverForSession);
        assertEquals(1, capture.driverSessionCalls);
        assertEquals(0, capture.driverCloseCalls);
        assertEquals(1, capture.sessionCloses);
        assertEquals(1, capture.listCalls);

        Capture listFailure = installDriver("AuthenticationNamespaceLifecycleIT");
        IllegalStateException original = new IllegalStateException("authentication result failure identity");
        listFailure.failList = true;
        listFailure.listFailure = original;
        assertSame(original, assertThrows(IllegalStateException.class,
            () -> AuthenticationNamespaceLifecycleIT.query("RETURN 1", Map.of())));
        assertEquals(1, listFailure.sessionCloses);
        assertEquals(1, listFailure.listCalls);

        Capture runFailure = installDriver("AuthenticationNamespaceLifecycleIT");
        runFailure.failRun = true;
        runFailure.runFailure = original;
        assertSame(original, assertThrows(IllegalStateException.class,
            () -> AuthenticationNamespaceLifecycleIT.query("RETURN 1", Map.of())));
        assertEquals(1, runFailure.sessionCloses);
        assertEquals(0, runFailure.listCalls);
    }

    @Test void cookieClientsAreFreshAndUriOriginPreserveRedirectTimeoutAndBasePathRules() throws Exception {
        BrowserSessionIT.base = URI.create("http://example.test:8088/context/");
        BrowserSessionRecoveryIT.base = BrowserSessionIT.base;
        HttpClient a = BrowserSessionIT.client(), b = BrowserSessionIT.client(), recovery = BrowserSessionRecoveryIT.client();
        assertNotSame(a,b); assertNotSame(a.cookieHandler().orElseThrow(), b.cookieHandler().orElseThrow());
        assertNotSame(a.cookieHandler().orElseThrow(), recovery.cookieHandler().orElseThrow());
        var cookies = (CookieManager)a.cookieHandler().orElseThrow();
        cookies.put(URI.create("http://example.test:8088/"), Map.of("Set-Cookie", List.of("session=hostile; Path=/")));
        assertEquals(1, cookies.getCookieStore().getCookies().size());
        assertTrue(((CookieManager)b.cookieHandler().orElseThrow()).getCookieStore().getCookies().isEmpty());
        for (HttpClient c : List.of(a,b,recovery)) {
            assertEquals(HttpClient.Redirect.NEVER, c.followRedirects());
            assertEquals(Duration.ofSeconds(5), c.connectTimeout().orElseThrow());
        }
        assertEquals("http://example.test:8088/context/login", BrowserSessionIT.uri("login").toASCIIString());
        assertEquals("http://example.test:8088", BrowserSessionIT.origin());
        assertEquals("http://example.test:8088/context/login", BrowserSessionRecoveryIT.uri("login").toASCIIString());
        assertEquals("http://example.test:8088", BrowserSessionRecoveryIT.origin());
        for (String base : List.of("https://example.test/path/", "https://example.test:443/path/", "https://example.test:9443/path/")) {
            BrowserSessionIT.base=URI.create(base); BrowserSessionRecoveryIT.base=BrowserSessionIT.base;
            String origin=base.contains(":9443")?"https://example.test:9443":"https://example.test";
            assertEquals(origin, BrowserSessionIT.origin()); assertEquals(origin, BrowserSessionRecoveryIT.origin());
            assertTrue(BrowserSessionIT.uri("/login").toASCIIString().endsWith("/path//login"));
        }
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method=type.getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(null,args);
    }
    private static Object invokeCall(CallCase c, Object[] args) throws Exception {
        return invoke(Class.forName("org.deposit.it." + c.owner()), c.method(), new Class<?>[]{String.class, Object[].class}, "fixture.proc", (Object)args);
    }
    private static Object invokeCallUnchecked(CallCase c, Object[] args) {
        try { return invokeCall(c, args); }
        catch (InvocationTargetException e) { throw sneaky(e.getCause()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void assertCallCapture(Capture capture, String expectedQuery, Integer timeoutSeconds, Object[] args) {
        assertEquals(expectedQuery, capture.query);
        if (timeoutSeconds == null) assertNull(capture.timeout);
        else assertEquals(timeoutSeconds.intValue(), (int)capture.timeout.timeout().getSeconds());
        List<String> expectedNames = new ArrayList<>();
        for (int i=0; i<args.length; i++) expectedNames.add("p"+i);
        assertEquals(expectedNames, new ArrayList<>(capture.parameters.keySet()));
        for (int i=0; i<args.length; i++) assertSame(args[i], capture.parameters.get("p"+i));
        assertSame(capture.suppliedDriver, capture.driverForSession, "session comes from the exact supplied Driver");
        assertEquals(1, capture.driverSessionCalls, "caller-supplied Driver supplies the session");
        assertEquals(0, capture.driverCloseCalls, "call wrapper does not close caller-owned Driver");
        assertEquals(1, capture.sessionCloses);
        assertEquals(1, capture.listCalls);
    }
    private static Object invokeUnchecked(Class<?> type,String name,Class<?>[] signature,Object...args){try{return invoke(type,name,signature,args);}catch(InvocationTargetException e){throw sneaky(e.getCause());}catch(Exception e){throw new AssertionError(e);}}
    private static RuntimeException sneaky(Throwable t){if(t instanceof RuntimeException r)return r;if(t instanceof Error e)throw e;return new RuntimeException(t);}
    private static Object proxy(Class<?> type, InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static Object defaultValue(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==byte.class)return (byte)0;if(type==short.class)return (short)0;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==char.class)return '\0';return null;}

    private static Capture installDriver(String owner) throws Exception {
        Capture capture=new Capture();
        Result result=(Result)proxy(Result.class,(p,m,a)->{if(m.getName().equals("list")){capture.listCalls++;if(capture.failList)throw capture.listFailure;return capture.rows;}return defaultValue(m.getReturnType());});
        Session session=(Session)proxy(Session.class,(p,m,a)->{if(m.getName().equals("run")){capture.query=(String)a[0];capture.parameters=(Map<String,Object>)a[1];if(a.length>2)capture.timeout=(TransactionConfig)a[2];if(capture.failRun)throw capture.runFailure;return result;}if(m.getName().equals("close")){capture.sessionCloses++;return null;}return defaultValue(m.getReturnType());});
        Driver driver=(Driver)proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){capture.driverSessionCalls++;capture.driverForSession=(Driver)p;return session;}if(m.getName().equals("close")){capture.driverCloseCalls++;return null;}return defaultValue(m.getReturnType());});
        capture.suppliedDriver=driver;
        Field field=Class.forName("org.deposit.it."+owner).getDeclaredField("driver");field.setAccessible(true);field.set(null,driver);
        return capture;
    }
    private record CallCase(String owner,String method,String query,Integer timeoutSeconds,boolean sessionClose){}
    private static final class Capture {String query;Map<String,Object> parameters;TransactionConfig timeout;Driver suppliedDriver,driverForSession;int driverSessionCalls,driverCloseCalls,sessionCloses,listCalls;boolean failList,failRun;RuntimeException runFailure,listFailure;List<org.neo4j.driver.Record> rows=List.of((org.neo4j.driver.Record)ROW);}
}
