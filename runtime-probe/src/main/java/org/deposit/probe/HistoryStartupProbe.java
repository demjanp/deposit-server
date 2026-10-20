package org.deposit.probe;
import deposit.common.*;
import java.nio.file.*;

/** Service-loaded before history initialization; absent from production distribution. */
public final class HistoryStartupProbe implements HistoryLifecycle.Observer {
    @Override public void point(ServerContext server,String point,String namespace){
        Path root=server.storage.files.root.getParent(),arm=root.resolve("history-startup-arm.txt");
        try{if(!Files.exists(arm)||!Files.readString(arm).trim().equals(point+"/"+namespace))return;Files.delete(arm);Files.writeString(root.resolve("history-startup-paused.txt"),point+"/"+namespace);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);while(System.nanoTime()<until){Thread.sleep(50);if(Files.exists(root.resolve("history-startup-release.txt")))return;}throw new IllegalStateException("Startup probe timed out");}catch(Exception e){throw new IllegalStateException("Startup probe barrier failed",e);}
    }
}
