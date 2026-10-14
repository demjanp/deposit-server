package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.service.BrowserDownloadService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResourceDownloadDrainTest {
    @TempDir Path temporary;
    static Object field(Object target,String name)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @Test @SuppressWarnings("unchecked") void shutdownWaitsForReaderCloseAndHandlerCleanup()throws Exception{
        Path config=temporary.resolve("deposit.properties");Files.writeString(config,"download.listenAddress=127.0.0.1\ndownload.port=0\ndownload.publicBaseUrl=http://127.0.0.1:1\n");
        String previous=System.getProperty("deposit.config");System.setProperty("deposit.config",config.toString());
        UUID database=UUID.randomUUID();ServerContext server=ServerContext.attach(database);CountDownLatch closing=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        ResourceDownloadServer downloads=null;Thread shutdown=null;
        try{
            downloads=new ResourceDownloadServer(server,null);downloads.start();
            var downloadField=ServerContext.class.getDeclaredField("downloads");downloadField.setAccessible(true);downloadField.set(server,downloads);
            InputStream stream=new ByteArrayInputStream(new byte[1]){@Override public void close(){closing.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}}};
            var constructor=BrowserDownloadService.ResourceRead.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
            var read=(BrowserDownloadService.ResourceRead)constructor.newInstance(stream,server.pinResource("resource"),"file","application/octet-stream","checksum",1L,new BrowserDownloadService.Range(0,1,false));
            var reads=(Set<BrowserDownloadService.ResourceRead>)field(downloads,"activeReads");reads.add(read);Object monitor=field(downloads,"drainMonitor");
            var executor=(ExecutorService)field(downloads,"executor");Future<?> handler=executor.submit(()->{try{read.close();}catch(IOException e){throw new UncheckedIOException(e);}finally{reads.remove(read);synchronized(monitor){monitor.notifyAll();}}});
            assertTrue(closing.await(2,TimeUnit.SECONDS));assertTrue(server.hasReaderPin("resource"));
            shutdown=Thread.ofPlatform().start(()->{try{server.shutdown();}catch(Throwable e){failure.set(e);}});
            Thread.sleep(100);assertTrue(shutdown.isAlive(),"storage cleanup cannot finish during reader/handler cleanup");assertTrue(server.hasReaderPin("resource"));
            release.countDown();handler.get(2,TimeUnit.SECONDS);shutdown.join(2_000);assertFalse(shutdown.isAlive());assertNull(failure.get());assertFalse(server.hasReaderPin("resource"));assertEquals(0,downloads.activeReaderCount());
        }finally{release.countDown();if(shutdown!=null)shutdown.join(2_000);if(downloads!=null)downloads.close();ServerContext.detach(database);if(previous==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previous);}
    }
}
