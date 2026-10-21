package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ServerContextBrowserSessionTest {
    private static ServerContext context(AtomicLong time,java.util.UUID id)throws Exception {
        ServerContext server=ServerContext.attach(id);
        server.sessionClockForTesting(time::get);
        return server;
    }

    @Test void sessionRegistrySupportsLookupAndConditionalRemoval()throws Exception {
        java.util.UUID id=java.util.UUID.randomUUID();ServerContext server=ServerContext.attach(id);
        try {
            String hash=Json.hash(ServerContext.randomToken());
            ServerContext.Session registered=new ServerContext.Session("actor",null,1,ServerContext.Transport.BROWSER);
            ServerContext.Session replacement=new ServerContext.Session("actor",null,1,ServerContext.Transport.BROWSER);
            server.registerSession(hash,registered);
            assertSame(registered,server.session(hash));
            assertFalse(server.removeSession(hash,replacement));
            assertSame(registered,server.session(hash));
            assertTrue(server.removeSession(hash,registered));
            assertNull(server.session(hash));
        } finally {ServerContext.detach(id);}
    }

    @Test void browserIdleExpiryIsMeasuredFromLastSuccessfulTouch()throws Exception {
        AtomicLong time=new AtomicLong(1_000_000);java.util.UUID id=java.util.UUID.randomUUID();ServerContext server=context(time,id);
        try {
            String token=ServerContext.randomToken();ServerContext.Session session=new ServerContext.Session("actor",null,time.get(),ServerContext.Transport.BROWSER);
            server.registerSession(Json.hash(token),session);
            time.addAndGet(1_799_999);assertTrue(server.touchBrowserSession(token,session));assertEquals(time.get(),session.touched);
            time.addAndGet(1_800_000);assertFalse(server.touchBrowserSession(token,session));assertNull(server.session(Json.hash(token)));
        } finally {ServerContext.detach(id);}
    }

    @Test void browserAbsoluteExpiryCannotBeExtendedBySuccessfulTouches()throws Exception {
        AtomicLong time=new AtomicLong(1_000_000);java.util.UUID id=java.util.UUID.randomUUID();ServerContext server=context(time,id);
        try {
            String token=ServerContext.randomToken();ServerContext.Session session=new ServerContext.Session("actor",null,time.get(),ServerContext.Transport.BROWSER);
            server.registerSession(Json.hash(token),session);
            for(int i=1;i<=17;i++){time.addAndGet(1_690_000);assertTrue(server.touchBrowserSession(token,session));}
            time.addAndGet(70_000);assertFalse(server.touchBrowserSession(token,session));assertNull(server.session(Json.hash(token)));
        } finally {ServerContext.detach(id);}
    }
}
