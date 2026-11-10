package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageVolumeIdentityTest {
    @TempDir Path temp;

    @Test void aliasesAndMultipleGuidsOnOneNtDeviceShareThePhysicalGroup()throws Exception {
        Path first=Files.createDirectory(temp.resolve("first")),alias=Files.createDirectory(temp.resolve("alias"));
        var api=new FakeApi();api.set(first,"11111111-1111-1111-1111-111111111111","HarddiskVolume7");api.set(alias,"22222222-2222-2222-2222-222222222222","HarddiskVolume7");
        withWindows(()->{
            var identity=new StorageVolumeIdentity(api);var a=identity.resolve(first);var b=identity.resolve(alias);
            assertEquals(a.deviceId(),b.deviceId());assertNotEquals(a.volumeGuid(),b.volumeGuid());
        });
    }

    @Test void sameVolumeLabelOnDifferentNtDevicesNeverGroups()throws Exception {
        Path first=Files.createDirectory(temp.resolve("first")),second=Files.createDirectory(temp.resolve("second"));
        var api=new FakeApi();api.set(first,"11111111-1111-1111-1111-111111111111","HarddiskVolume7");api.set(second,"11111111-1111-1111-1111-111111111111","HarddiskVolume8");
        withWindows(()->assertNotEquals(new StorageVolumeIdentity(api).resolve(first).deviceId(),new StorageVolumeIdentity(api).resolve(second).deviceId()));
    }

    @Test void nestedMountIsResolvedFromTheActualOwnerRootAndBindingIsRechecked()throws Exception {
        Path root=Files.createDirectories(temp.resolve("mount\\nested\\root"));var api=new FakeApi();api.set(root,"11111111-1111-1111-1111-111111111111","HarddiskVolume7");
        withWindows(()->{
            var resolver=new StorageVolumeIdentity(api);var original=resolver.resolve(root.resolve("not-created-yet"));
            assertEquals(root.toRealPath().toString(),original.ownerRoot());
            assertDoesNotThrow(()->resolver.reResolve(original.deviceId(),original.volumeGuid(),root));
            api.set(root,original.volumeGuid(),"HarddiskVolume9");
            assertThrows(IOException.class,()->resolver.reResolve(original.deviceId(),original.volumeGuid(),root));
        });
    }

    @Test void nativeHandlesCloseWhenPathResolutionFails()throws Exception {
        Path root=Files.createDirectory(temp.resolve("broken"));var api=new FakeApi();api.set(root,"11111111-1111-1111-1111-111111111111","HarddiskVolume7");api.failOnFinalPath=true;
        withWindows(()->assertThrows(IOException.class,()->new StorageVolumeIdentity(api).resolve(root)));
        assertEquals(1,api.closed);
    }

    @Test void jnaRuntimeIsAvailableOnThePluginClasspath() {
        assertNotNull(com.sun.jna.Native.class.getProtectionDomain().getCodeSource());
    }
    @Test void nativeVolumeResolverReadsTheActualWindowsVolume()throws Exception {
        if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).startsWith("windows"))return;
        var identity=new StorageVolumeIdentity().resolve(temp);
        assertTrue(identity.deviceId().startsWith("windows:\\device\\"));assertNotNull(identity.volumeGuid());
    }

    private static void withWindows(Throwing action)throws Exception {
        String previous=System.getProperty("os.name");System.setProperty("os.name","Windows 11");
        try{action.run();}finally{if(previous==null)System.clearProperty("os.name");else System.setProperty("os.name",previous);}
    }
    @FunctionalInterface private interface Throwing {void run()throws Exception;}
    private static final class FakeApi implements StorageVolumeIdentity.WindowsApi {
        private final Map<String,String[]> values=new HashMap<>();private final Map<Long,String[]> handles=new HashMap<>();private int next=1,closed;boolean failOnFinalPath;
        void set(Path path,String guid,String device)throws IOException{values.put(path.toRealPath().toString(),new String[]{guid,device});}
        @Override public StorageVolumeIdentity.Handle openDirectory(String path)throws IOException {String[] binding=values.get(path);if(binding==null)throw new IOException("unmapped path");long id=next++;handles.put(id,binding);return new StorageVolumeIdentity.Handle(new Pointer(id));}
        @Override public String finalPath(StorageVolumeIdentity.Handle handle,int mode)throws IOException {if(failOnFinalPath)throw new IOException("resolution failed");String[] binding=handles.get(Pointer.nativeValue(handle.value()));return mode==1?"\\\\?\\Volume{"+binding[0]+"}\\":"\\Device\\"+binding[1]+"\\nested";}
        @Override public void close(StorageVolumeIdentity.Handle handle){closed++;}
    }
}
