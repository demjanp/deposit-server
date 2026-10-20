package deposit.service;

import deposit.common.ExceptionHandler;
import deposit.common.ServerContext;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.neo4j.graphdb.Node;

/** Shared password policy and BCrypt operations for Bolt and browser authentication. */
final class AccountCredentials {
    private AccountCredentials() {}

    static String password(Object value) {
        String password=BaseService.string(value);
        if(password.codePointCount(0,password.length())<12||password.getBytes(StandardCharsets.UTF_8).length>72)
            throw ExceptionHandler.fail("VALIDATION_FAILED","Password requires at least 12 characters and at most 72 UTF-8 bytes");
        return password;
    }
    static String hash(String password) {
        char[] chars=password.toCharArray();
        try {return OpenBSDBCrypt.generate("2y",chars,ServerContext.randomBytes(16),12);}
        finally {Arrays.fill(chars,'\0');}
    }
    static boolean matches(Node user,String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72)return false;
        char[] chars=password.toCharArray();
        try {return OpenBSDBCrypt.checkPassword((String)user.getProperty("passwordHash"),chars);}
        finally {Arrays.fill(chars,'\0');}
    }
    static boolean validLogin(Node user,String password) {
        return user!=null&&Boolean.TRUE.equals(user.getProperty("enabled",false))&&matches(user,password);
    }
}
