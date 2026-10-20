package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
class ResourceRecoveryIT {
    @Test void geometryAndReferencedBytesSurviveRestart()throws Exception {
        connect();try{var p=new Properties();try(var in=Files.newInputStream(ROOT.resolve("resource-fixture.properties"))){p.load(in);}String token=BatchMutationIT.login();BatchMutationIT.select(token,p.getProperty("namespace"));
            var geometry=call("deposit.get.descriptor",m("objectId",p.getProperty("object"),"name","G"),ctx(token)).get("data");assertEquals(p.getProperty("geometry"),geometry.get("geometryId").asString());assertEquals("POINT Z(12 34 56)",geometry.get("value").get("wkt").asString().replace("Z (","Z("));
            var bytes=call("deposit.resource.downloadChunk",m("resourceId",p.getProperty("resource"),"offset",0L,"length",3L),ctx(token)).get("data");assertEquals("YWJj",bytes.get("bytesBase64").asString());assertEquals(Long.parseLong(p.getProperty("sequence")),revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
        }finally{close();}
    }
}
