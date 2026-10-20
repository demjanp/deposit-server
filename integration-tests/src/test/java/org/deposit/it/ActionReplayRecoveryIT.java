package org.deposit.it;

import static org.deposit.it.PreparedActionReplayIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Runs unchanged after graceful restart and actual termination on the authorized host. */
class ActionReplayRecoveryIT {
    @BeforeAll static void start() throws Exception {PreparedActionReplayIT.connect();}
    @AfterAll static void stop(){PreparedActionReplayIT.disconnect();}
    @Test void seed() throws Exception {
        String token=login(),namespace=id(call("deposit.create.namespace","Recovery65-"+key(),m(),token));
        call("deposit.set.activeNamespace",m("id",namespace),token);
        String committed=key(),pending=key(),server=key();
        prepare(committed,"deposit.create.class",List.of("Committed",m()),token);
        String clazz=id(call("deposit.create.class","Committed",m(),token));release(token);
        prepare(pending,"deposit.create.class",List.of("Pending",m()),token);release(token);
        prepare(server,"deposit.create.namespace",List.of("Never sent "+server,m()),token);release(token);
        var properties=new Properties();properties.setProperty("namespace",namespace);properties.setProperty("class",clazz);
        properties.setProperty("committed",committed);properties.setProperty("pending",pending);properties.setProperty("server",server);
        try(var out=Files.newOutputStream(ROOT.resolve("prepared-action-recovery.properties"))){properties.store(out,"Public recovery identities only; no tokens or credentials");}
    }
    @Test void verify() throws Exception {
        var properties=new Properties();try(var in=Files.newInputStream(ROOT.resolve("prepared-action-recovery.properties"))){properties.load(in);}
        String token=login();call("deposit.set.activeNamespace",m("id",properties.getProperty("namespace")),token);
        String committed=properties.getProperty("committed"),pending=properties.getProperty("pending"),server=properties.getProperty("server");
        assertEquals("committed",call("deposit.action.status",committed,token).get("data").get("state").asString());
        call("deposit.action.resume",committed,token);assertEquals(properties.getProperty("class"),id(call("deposit.create.class","Committed",m(),token)));release(token);
        call("deposit.action.resume",pending,token);call("deposit.create.class","Pending",m(),token);release(token);
        assertEquals("committed",call("deposit.action.status",pending,token).get("data").get("state").asString());
        call("deposit.action.resume",server,token);
        error("REVISION_CONFLICT",()->call("deposit.create.namespace","Never sent "+server,m(),token));release(token);
        assertEquals("prepared",call("deposit.action.status",server,token).get("data").get("state").asString());
        assertEquals(2,query("CALL deposit.get.class(null,$t)",m("t",token)).size());
    }
}
