package deposit.common;

import java.util.Properties;

public final class DepositInfo {
    private DepositInfo() {}
    public static String version() {
        try(var stream=DepositInfo.class.getResourceAsStream("/deposit-build.properties")) {var p=new Properties();p.load(stream);return p.getProperty("version");}
        catch(Exception e) {throw new IllegalStateException("Build metadata missing",e);}
    }
}
