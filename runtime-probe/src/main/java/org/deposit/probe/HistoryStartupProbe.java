package org.deposit.probe;
import deposit.common.*;
import java.nio.file.*;

/** Service-loaded before history initialization; absent from production distribution. */
public final class HistoryStartupProbe implements HistoryLifecycle.Observer {
    @Override public void point(ServerContext server,String point,String namespace){
        Path markers=server.storage.files.root.resolve("history-startup-test"),arm=markers.resolve("arm.txt");
        try{if(!Files.exists(arm)||!Files.readString(arm).trim().equals(point+"/"+namespace))return;Files.delete(arm);Files.writeString(markers.resolve("paused.txt"),point+"/"+namespace);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);while(System.nanoTime()<until){Thread.sleep(50);if(Files.exists(markers.resolve("release.txt")))return;}throw new IllegalStateException("Startup probe timed out");}catch(Exception e){throw new IllegalStateException("Startup probe barrier failed",e);}
    }
}
