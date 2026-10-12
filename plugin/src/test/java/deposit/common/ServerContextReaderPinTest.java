package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ServerContextReaderPinTest {
    @Test void readerPinsAreReferenceCountedAndIdempotentlyReleased()throws Exception{
        UUID id=UUID.randomUUID();ServerContext server=ServerContext.attach(id);
        try{
            AutoCloseable first=server.pinResource("resource");AutoCloseable second=server.pinResource("resource");
            assertTrue(server.hasReaderPin("resource"));first.close();first.close();assertTrue(server.hasReaderPin("resource"));
            second.close();assertFalse(server.hasReaderPin("resource"));
        }finally{ServerContext.detach(id);}
    }
    @Test void concurrentReaderPinReleaseCannotLoseAnotherPin()throws Exception{
        UUID id=UUID.randomUUID();ServerContext server=ServerContext.attach(id);
        try(var executor=Executors.newFixedThreadPool(4)){
            var ready=new CountDownLatch(4);var release=new CountDownLatch(1);var work=new Future<?>[4];
            for(int i=0;i<work.length;i++)work[i]=executor.submit(()->{try(var pin=server.pinResource("resource")){ready.countDown();release.await();}return null;});
            assertTrue(ready.await(5,TimeUnit.SECONDS));assertTrue(server.hasReaderPin("resource"));release.countDown();for(var task:work)task.get(5,TimeUnit.SECONDS);assertFalse(server.hasReaderPin("resource"));
        }finally{ServerContext.detach(id);}
    }
}
