package org.deposit.it;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class QueryRecoveryIT {
    @BeforeAll static void start(){connect();QueryLanguageIT.token=BatchMutationIT.login();QueryLanguageIT.second=BatchMutationIT.login();}
    @AfterAll static void stop(){close();}
    @Test void seed()throws Exception{new QueryLanguageIT().seed();var p=new Properties();p.setProperty("namespace",QueryLanguageIT.ns);p.setProperty("cursor",QueryLanguageIT.page(m("query","SELECT Specimen.Code AS code ORDER BY code","pageSize",1L)).get("meta").get("nextCursor").asString());try(var out=Files.newOutputStream(ROOT.resolve("query-recovery.properties"))){p.store(out,"query durable query fixture; no session credentials");}}
    @Test void verify()throws Exception{var p=new Properties();try(var in=Files.newInputStream(ROOT.resolve("query-recovery.properties"))){p.load(in);}BatchMutationIT.select(QueryLanguageIT.token,p.getProperty("namespace"));assertEquals(30.0,QueryLanguageIT.q("SELECT SUM(Specimen.Mass) AS total").getFirst().get("data").get("total").asDouble());var page=QueryLanguageIT.page(m("query","SELECT Specimen.Code AS code ORDER BY code","pageSize",1L,"cursor",p.getProperty("cursor")));assertEquals("S2",page.get("data").get("rows").get(0).get("values").get("code").asString());}
}
