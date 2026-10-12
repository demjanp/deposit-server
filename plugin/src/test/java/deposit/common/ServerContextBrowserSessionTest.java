package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ServerContextBrowserSessionTest {
    private static final Field CLOCK;
    static {try {CLOCK=ServerContext.class.getDeclaredField("sessionClock");CLOCK.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}

    private static ServerContext context(AtomicLong time,java.util.UUID id)throws Exception {
        ServerContext server=ServerContext.attach(id);
        CLOCK.set(server,(LongSupplier)time::get);
        return server;
    }

    @Test void browserIdleExpiryIsMeasuredFromLastSuccessfulTouch()throws Exception {
        AtomicLong time=new AtomicLong(1_000_000);java.util.UUID id=java.util.UUID.randomUUID();ServerContext server=context(time,id);
        try {
            String token=ServerContext.randomToken();ServerContext.Session session=new ServerContext.Session("actor",null,time.get(),ServerContext.Transport.BROWSER);
            server.sessions.put(Json.hash(token),session);
            time.addAndGet(1_799_999);assertTrue(server.touchBrowserSession(token,session));assertEquals(time.get(),session.touched);
            time.addAndGet(1_800_000);assertFalse(server.touchBrowserSession(token,session));assertFalse(server.sessions.containsKey(Json.hash(token)));
        } finally {ServerContext.detach(id);}
    }

    @Test void browserAbsoluteExpiryCannotBeExtendedBySuccessfulTouches()throws Exception {
        AtomicLong time=new AtomicLong(1_000_000);java.util.UUID id=java.util.UUID.randomUUID();ServerContext server=context(time,id);
        try {
            String token=ServerContext.randomToken();ServerContext.Session session=new ServerContext.Session("actor",null,time.get(),ServerContext.Transport.BROWSER);
            server.sessions.put(Json.hash(token),session);
            for(int i=1;i<=17;i++){time.addAndGet(1_690_000);assertTrue(server.touchBrowserSession(token,session));}
            time.addAndGet(70_000);assertFalse(server.touchBrowserSession(token,session));assertFalse(server.sessions.containsKey(Json.hash(token)));
        } finally {ServerContext.detach(id);}
    }
}
