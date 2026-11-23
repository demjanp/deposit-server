package deposit.common;

import deposit.repository.CoreRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.neo4j.graphdb.GraphDatabaseService;

/** Seeds validated final-format intake for tests that exercise downstream materialization. */
public final class ExchangeTestFixture {
    private ExchangeTestFixture() {}

    public static Map<String,Object> receive(ServerContext server, GraphDatabaseService database,
            String actor, String scope, Path archive) throws IOException {
        String id=UUID.randomUUID().toString(), grant=UUID.randomUUID().toString();
        String relative="staging/exchange/"+id;
        Path directory=server.storage.files.path(relative);
        long size=Files.size(archive);
        var owner=server.storage.admission.acquire("exchange-stage",id,UUID.randomUUID().toString(),
            "exchange-stage",Map.of(directory,size),Json.map("phase","exchange-stage","actorId",actor,
                "receiptScope",scope,"stagingPath",relative));
        Files.createDirectories(directory);
        Path staged=directory.resolve("package.zip");
        Files.copy(archive,staged);
        ExchangePackageIndex.build(staged,directory.resolve("content"),null,()->{});
        server.storage.admission.materialized(owner,server.storage.files.root,size);
        long expires=server.sessionTime()+86_400_000L;
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now());
            core.createRestored("DepositExchangeStage",null,actor,Json.map("actorId",actor,"scope",scope,
                "admissionId",owner.id,"grant",grant,"grantKind","receiving-upload","history",false,"state","ready",
                "bytes",size,"sha256",ResourceFiles.checksum(staged),"expiresAt",expires,
                "cleanupAfter",expires+900_000L,"removeAfter",expires+900_000L),id);
            tx.commit();
        }
        return Map.of("packageId",id,"grant",grant);
    }
}
