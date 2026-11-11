package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Retains separate physical growth observations for the pinned Neo4j store and transaction log. */
class ImportWriteBudgetCalibrationTest {
    @TempDir Path temporary;

    @Test void recordsTinyLongTextAndLargeHistoryImageGrowthByStoreArea()throws Exception {
        DatabaseManagementService management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();
        try {
            GraphDatabaseAPI database=(GraphDatabaseAPI)management.database("neo4j");
            Path store=database.databaseLayout().databaseDirectory(),logs=database.databaseLayout().getTransactionLogsDirectory();
            long storeBefore=size(store),logsBefore=size(logs),estimatedStore=0,estimatedLogs=0;
            try(var tx=database.beginTx()) {
                for(int i=0;i<20_000;i++) {
                    Map<String,Object> properties=Json.map("id","tiny-"+i,"name","x","state","ready");
                    Node node=tx.createNode(Label.label("CalibrationTiny"));properties.forEach(node::setProperty);
                    long bytes=Json.write(properties).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;estimatedStore+=bytes+512;estimatedLogs+=2*bytes+128;
                }
                String longText="λ🙂".repeat(24_000);Map<String,Object> longValue=Json.map("id","long-text","value",longText);
                Node longNode=tx.createNode(Label.label("CalibrationLongText"));longNode.setProperty("id","long-text");longNode.setProperty("value",longText);
                long bytes=Json.write(longValue).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;estimatedStore+=bytes+512;estimatedLogs+=2*bytes+128;
                String before="before-image:"+"b".repeat(512*1024),after="after-image:"+"a".repeat(512*1024);
                Node history=tx.createNode(Label.label("CalibrationHistoryImage"));history.setProperty("beforeJson",before);history.setProperty("afterJson",after);
                Map<String,Object> historyProperties=Json.map("beforeJson",before,"afterJson",after);bytes=Json.write(historyProperties).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;estimatedStore+=bytes+512;estimatedLogs+=2*bytes+128;
                tx.commit();
            }
            long storeAfter=size(store),logsAfter=size(logs);
            long logEstimate=estimatedLogs;Map<String,Object> sample=Json.map("neo4j", "2026.05.0", "tinyRecords",20_000L,"storeBytesBefore",storeBefore,"storeBytesAfter",storeAfter,"storeBytesGrowth",storeAfter-storeBefore,"storeEstimateBytes",estimatedStore,"transactionLogBytesBefore",logsBefore,"transactionLogBytesAfter",logsAfter,"transactionLogBytesGrowth",logsAfter-logsBefore,"transactionLogEstimateBytes",logEstimate);
            System.out.println("IMPORT_WRITE_CALIBRATION="+Json.write(sample));
            assertTrue(storeAfter>=storeBefore&&logsAfter>=logsBefore);
            assertTrue(estimatedStore>=storeAfter-storeBefore,"database planning estimate must cover observed page-store growth");
            assertTrue(logEstimate>=logsAfter-logsBefore,"transaction-log image estimate must cover observed log growth");
        } finally {management.shutdown();}
    }

    private static long size(Path directory)throws Exception {try(var files=Files.walk(directory)){long total=0;for(Path file:files.filter(Files::isRegularFile).toList())total=Math.addExact(total,Files.size(file));return total;}}
}
