package org.deposit.it;

import static org.deposit.it.DataImportExportIT.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Verifies that durable tabular staging and its row index survive a server restart. */
class ImportExportRecoveryIT {
    static final Path FILE=TestInstallationPaths.runRoot().resolve("import-export-recovery.properties");
    @BeforeAll static void open()throws Exception{DataImportExportIT.connect();}
    @AfterAll static void close(){DataImportExportIT.close();}

    @Test void seed()throws Exception{
        String token=login();String namespace=id(call("deposit.create.namespace","ImportExportRecovery-"+UUID.randomUUID(),m("historyMode","disabled"),token));call("deposit.set.activeNamespace",m("id",namespace),token);DataImportExportIT helper=new DataImportExportIT();helper.token=token;String resource=helper.upload("code,note\r\nR-1,retained\r\nR-2,indexed\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"recovery.csv");String recovery=UUID.randomUUID().toString();var prepared=call("deposit.action.prepare",recovery,"deposit.load.csv",List.of(resource,m()),token).get("data");String operation=prepared.get("operationId").asString();String table=call("deposit.load.csv",resource,m(),token).get("data").get("tableId").asString();Properties properties=new Properties();properties.setProperty("namespace",namespace);properties.setProperty("table",table);properties.setProperty("resource",resource);properties.setProperty("recovery",recovery);properties.setProperty("operation",operation);try(var output=Files.newOutputStream(FILE)){properties.store(output,"import-export staging identities; no credentials");}
    }

    @Test void verify()throws Exception{
        Properties properties=new Properties();try(var input=Files.newInputStream(FILE)){properties.load(input);}
        String token=login();call("deposit.set.activeNamespace",m("id",properties.getProperty("namespace")),token);
        call("deposit.action.resume",properties.getProperty("recovery"),token);
        var replay=call("deposit.load.csv",properties.getProperty("resource"),m(),token).get("data");assertEquals(properties.getProperty("table"),replay.get("tableId").asString());call("deposit.action.release",token);
        var page=call("deposit.get.tablePage",properties.getProperty("table"),m("pageSize",1L),token).get("data");assertEquals("R-1",page.get("rows").get(0).get("values").get("code").asString());assertNotNull(page.get("nextCursor"));
        var next=call("deposit.get.tablePage",properties.getProperty("table"),m("pageSize",1L,"cursor",page.get("nextCursor").asString()),token).get("data");assertEquals("R-2",next.get("rows").get(0).get("values").get("code").asString());
        try(var session=driver.session()){
            assertEquals(1L,session.run("MATCH (t:DepositTabularTable {id:$table}) RETURN count(t) AS n",m("table",properties.getProperty("table"))).single().get("n").asLong());
            assertEquals(1L,session.run("MATCH (l:DepositResourceLease {tableId:$table,type:'table'}) RETURN count(l) AS n",m("table",properties.getProperty("table"))).single().get("n").asLong());
            assertEquals(1L,session.run("MATCH (r:DepositReceipt {scope:$ns,operationId:$operation}) RETURN count(r) AS n",m("ns",properties.getProperty("namespace"),"operation",properties.getProperty("operation"))).single().get("n").asLong());
        }
    }
}
