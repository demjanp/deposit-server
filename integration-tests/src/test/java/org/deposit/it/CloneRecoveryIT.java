package org.deposit.it;
import static org.deposit.it.NamespaceCloneAnalysisIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Persists an immutable analyzed plan across restart and applies it after recovery. */
class CloneRecoveryIT {
    static final Path FILE=TestInstallationPaths.runRoot().resolve("clone-recovery.properties");
    @BeforeAll static void open()throws Exception{NamespaceCloneAnalysisIT.connect();}
    @AfterAll static void close(){NamespaceCloneAnalysisIT.disconnect();}
    @Test void seed()throws Exception{String token=login();String source=id(call("deposit.create.namespace","CloneRecovery-source-"+key(),m("historyMode","persistent"),token));call("deposit.set.activeNamespace",m("id",source),token);call("deposit.create.class","Recovered",m(),token);call("deposit.create.object","Recovered",m(),token);String packageId=call("deposit.save.data",m(),m("includeHistory",true),token).get("data").get("resourceId").asString();String target=id(call("deposit.create.namespace","CloneRecovery-target-"+key(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",target),token);String plan=call("deposit.import.analyze",m("packageResourceId",packageId),m(),token).get("data").get("planId").asString();Properties p=new Properties();p.setProperty("source",source);p.setProperty("target",target);p.setProperty("package",packageId);p.setProperty("plan",plan);try(var out=Files.newOutputStream(FILE)){p.store(out,"clone recovery identities; no credentials");}}
    @Test void verify()throws Exception{Properties p=new Properties();try(var in=Files.newInputStream(FILE)){p.load(in);}String token=login();call("deposit.set.activeNamespace",m("id",p.getProperty("target")),token);Map<?,?> plan=(Map<?,?>)call("deposit.get.importPlan",p.getProperty("plan"),token).get("data").asMap();assertEquals("ready",plan.get("state"));call("deposit.import.apply",p.getProperty("plan"),token);assertEquals(1,query("MATCH (o:Object {namespaceId:$ns}) RETURN count(o) AS n",m("ns",p.getProperty("target"))).getFirst().get("n").asLong());}
}
