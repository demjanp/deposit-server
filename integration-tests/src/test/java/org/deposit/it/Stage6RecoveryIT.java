package org.deposit.it;
import static org.deposit.it.Stage1LifecycleIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class Stage6RecoveryIT {
    @BeforeAll static void start(){connect();Stage6CoreIT.token=Stage3CoreIT.login();Stage6CoreIT.second=Stage3CoreIT.login();}
    @AfterAll static void stop(){close();}
    @Test void seed()throws Exception{new Stage6CoreIT().seed();var p=new Properties();p.setProperty("namespace",Stage6CoreIT.ns);p.setProperty("cursor",Stage6CoreIT.page(m("query","SELECT Specimen.Code AS code ORDER BY code","pageSize",1L)).get("meta").get("nextCursor").asString());try(var out=Files.newOutputStream(ROOT.resolve("stage6-recovery.properties"))){p.store(out,"Stage 6 durable query fixture; no session credentials");}}
    @Test void verify()throws Exception{var p=new Properties();try(var in=Files.newInputStream(ROOT.resolve("stage6-recovery.properties"))){p.load(in);}Stage3CoreIT.select(Stage6CoreIT.token,p.getProperty("namespace"));assertEquals(30.0,Stage6CoreIT.q("SELECT SUM(Specimen.Mass) AS total").getFirst().get("data").get("total").asDouble());var page=Stage6CoreIT.page(m("query","SELECT Specimen.Code AS code ORDER BY code","pageSize",1L,"cursor",p.getProperty("cursor")));assertEquals("S2",page.get("data").get("rows").get(0).get("values").get("code").asString());}
}
