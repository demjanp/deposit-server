package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import static org.deposit.it.Stage2CoreIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class Stage2RecoveryIT {
    @Test void deletionReceiptSurvivesRestartWithoutSelectionAndNeverRetargets() throws Exception {
        connect();
        try {
            Properties fixture=new Properties();try(var input=Files.newInputStream(ROOT.resolve("stage2-fixture.properties"))){fixture.load(input);}
            String token=login(fixture.getProperty("username"),PASS);assertEquals(0,sr(token));assertFalse(call("deposit.get.administrativeMode",Map.of(),ctx(token)).get("value").asBoolean());assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());
            var context=write(token,Long.parseLong(fixture.getProperty("server")));context.put("operationId",fixture.getProperty("operation"));var request=m("confirmNamespaceName",fixture.getProperty("name"),"expectedNamespaceRevision",Long.parseLong(fixture.getProperty("namespaceRevision")));
            var receipt=call("deposit.get.operation",m("operationId",fixture.getProperty("operation"),"scope","server"),ctx(token));
            var result=call("deposit.delete.namespace",request,context);assertEquals(0,revision(result));assertEquals(receipt.get("data").get("outcome").asMap(),result.get("data").asMap());assertTrue(result.get("data").get("deletedIds").asList().contains(fixture.getProperty("deleted")));
            select(token,fixture.getProperty("survivor"));call("deposit.delete.namespace",request,context);assertEquals(fixture.getProperty("survivor"),id(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
        }finally{close();}
    }
}
