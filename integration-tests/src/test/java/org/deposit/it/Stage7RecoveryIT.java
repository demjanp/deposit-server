package org.deposit.it;
import static org.deposit.it.Stage7CoreIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;
import org.junit.jupiter.api.*;

/** Run verification after both graceful restart and forced process termination. */
class Stage7RecoveryIT {
    static final Path FILE=Stage0Paths.runRoot().resolve("stage7-recovery.properties");
    @BeforeAll static void open()throws Exception{connect();}
    @AfterAll static void close(){disconnect();}
    @Test void seed()throws Exception{
        String token=call("deposit.utils.login",USER,PASSWORD).get("value").asString();String ns=id(call("deposit.create.namespace","Stage7Recovery-"+key(),m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",ns),token);
        call("deposit.create.class","Thing",m(),token);call("deposit.create.object","Thing",m(),token);
        String pattern=id(call("deposit.create.pattern","Recovery",m("objects",m("o",m("className","Thing","root",true))),m("comment","original"),token));
        String operation=key();call("deposit.action.prepare",operation,"deposit.save.data",List.of(m(),m("includeHistory",true)),token);var generated=call("deposit.save.data",m(),m("includeHistory",true),token).get("data");call("deposit.action.release",token);
        var properties=new Properties();properties.setProperty("namespace",ns);properties.setProperty("pattern",pattern);properties.setProperty("operation",operation);properties.setProperty("resource",generated.get("resourceId").asString());properties.setProperty("checksum",generated.get("checksum").asString());properties.setProperty("bytes",Long.toString(generated.get("bytes").asLong()));
        try(var output=Files.newOutputStream(FILE)){properties.store(output,"Public Stage 7 recovery identities; no credentials");}
    }
    @Test void verify()throws Exception{
        var properties=new Properties();try(var input=Files.newInputStream(FILE)){properties.load(input);}
        String token=call("deposit.utils.login",USER,PASSWORD).get("value").asString();call("deposit.set.activeNamespace",m("id",properties.getProperty("namespace")),token);
        assertEquals(1,query("CALL deposit.get.objectsByPattern('Recovery',{},{},$t)",m("t",token)).size());
        call("deposit.action.resume",properties.getProperty("operation"),token);var replay=call("deposit.save.data",m(),m("includeHistory",true),token).get("data");assertEquals(properties.getProperty("resource"),replay.get("resourceId").asString());call("deposit.action.release",token);
        assertTrue(call("deposit.load.data",properties.getProperty("resource"),token).get("data").get("valid").asBoolean());
        byte[] bytes=Base64.getDecoder().decode(call("deposit.resource.downloadChunk",properties.getProperty("resource"),0L,Long.parseLong(properties.getProperty("bytes")),token).get("data").get("bytesBase64").asString());assertEquals(properties.getProperty("checksum"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        call("deposit.set.pattern",m("id",properties.getProperty("pattern")),m("comment","changed"),token);call("deposit.utils.undo",null,token);assertEquals("original",call("deposit.get.pattern",m("id",properties.getProperty("pattern")),token).get("node").asNode().get("comment").asString());
    }
}
