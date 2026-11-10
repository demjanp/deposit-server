package deposit.common;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable physical-volume identity for admission grouping and restart reconciliation. */
final class StorageVolumeIdentity {
    private static final Pattern GUID = Pattern.compile("(?i)^\\\\\\\\\\?\\\\Volume\\{([0-9a-f-]{36})}\\\\.*$");
    private static final Pattern NT_DEVICE = Pattern.compile("(?i)^(\\\\Device\\\\[^\\\\]+)(?:\\\\.*)?$");
    private static final int FILE_SHARE_READ=1, FILE_SHARE_WRITE=2, FILE_SHARE_DELETE=4;
    private static final int OPEN_EXISTING=3, FILE_FLAG_BACKUP_SEMANTICS=0x02000000;
    private static final int VOLUME_NAME_GUID=1, VOLUME_NAME_NT=2;

    record Identity(String deviceId,String volumeGuid,String ownerRoot) {
        Identity { Objects.requireNonNull(deviceId);Objects.requireNonNull(ownerRoot); }
    }
    interface WindowsApi {
        Handle openDirectory(String path)throws IOException;
        String finalPath(Handle handle,int volumeMode)throws IOException;
        void close(Handle handle)throws IOException;
    }
    record Handle(Pointer value) {}
    private final WindowsApi windows;

    StorageVolumeIdentity(){this(new JnaWindowsApi());}
    StorageVolumeIdentity(WindowsApi windows){this.windows=Objects.requireNonNull(windows);}

    Identity resolve(Path root)throws IOException {
        Path ancestor=existingAncestor(root);String owner=ancestor.toString();
        if(isWindows()) {
            Handle handle=windows.openDirectory(owner);
            try {
                String guidPath=windows.finalPath(handle,VOLUME_NAME_GUID),ntPath=windows.finalPath(handle,VOLUME_NAME_NT);
                Matcher guid=GUID.matcher(guidPath);Matcher nt=NT_DEVICE.matcher(ntPath);
                if(!guid.matches()||!nt.matches())throw new IOException("Cannot resolve stable Windows volume identity");
                return new Identity("windows:"+nt.group(1).toLowerCase(Locale.ROOT),guid.group(1).toLowerCase(Locale.ROOT),owner);
            } finally { windows.close(handle); }
        }
        try {
            Object raw=Files.getAttribute(ancestor,"unix:dev",LinkOption.NOFOLLOW_LINKS);
            if(!(raw instanceof Number number))throw new IOException("Unix volume device identity is unavailable");
            return new Identity("unix:"+Long.toUnsignedString(number.longValue()),null,owner);
        }catch(UnsupportedOperationException|IllegalArgumentException failure){throw new IOException("Stable volume identity is unavailable",failure);}
    }

    Identity reResolve(String savedDeviceId,String savedGuid,Path ownerRoot)throws IOException {
        Identity current=resolve(ownerRoot);
        if(!current.deviceId().equals(savedDeviceId)||!Objects.equals(normalize(current.volumeGuid()),normalize(savedGuid)))throw new IOException("Storage volume binding changed since reservation");
        return current;
    }

    private static String normalize(String value){return value==null?null:value.toLowerCase(Locale.ROOT);}
    private static Path existingAncestor(Path root)throws IOException {
        Path candidate=root.toAbsolutePath().normalize();
        while(candidate!=null&&!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))candidate=candidate.getParent();
        if(candidate==null)throw new IOException("Storage volume has no existing ancestor");
        return candidate.toRealPath();
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");}

    private static final class JnaWindowsApi implements WindowsApi {
        private interface Kernel32 extends StdCallLibrary {
            Pointer CreateFileW(WString fileName,int desiredAccess,int shareMode,Pointer securityAttributes,int creationDisposition,int flags,Pointer templateFile);
            int GetFinalPathNameByHandleW(Pointer file,char[] path,int pathLength,int flags);
            boolean CloseHandle(Pointer object);
        }
        private Kernel32 kernel(){return Native.load("kernel32",Kernel32.class);}
        @Override public Handle openDirectory(String path)throws IOException {
            Pointer handle=kernel().CreateFileW(new WString(path),0,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,null,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS,null);
            if(handle==null||Pointer.nativeValue(handle)==-1L)throw new IOException("Cannot open storage volume directory");return new Handle(handle);
        }
        @Override public String finalPath(Handle handle,int volumeMode)throws IOException {
            char[] buffer=new char[32768];int length=kernel().GetFinalPathNameByHandleW(handle.value(),buffer,buffer.length,volumeMode);
            if(length<=0||length>=buffer.length)throw new IOException("Cannot resolve storage volume final path");return new String(buffer,0,length);
        }
        @Override public void close(Handle handle)throws IOException {if(!kernel().CloseHandle(handle.value()))throw new IOException("Cannot close storage volume handle");}
    }
}
