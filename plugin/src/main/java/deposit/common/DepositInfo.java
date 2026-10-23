package deposit.common;

import java.util.Properties;

public final class DepositInfo {
    private DepositInfo() {}
    public static String version() {
        try(var stream=DepositInfo.class.getResourceAsStream("/deposit-build.properties")) {
            if(stream==null) throw new IllegalStateException("Build metadata missing");
            var p=new Properties();p.load(stream);var version=p.getProperty("version");
            if(version==null||version.isBlank()||version.contains("${")||!version.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")) throw new IllegalStateException("Invalid Deposit build version metadata");
            return version;
        }
        catch(Exception e) {throw new IllegalStateException("Build metadata missing",e);}
    }
}
