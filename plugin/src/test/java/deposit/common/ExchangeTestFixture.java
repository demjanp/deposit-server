package deposit.common;

import deposit.repository.ExchangeRepository.Stage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        var stage=Stage.open(id,actor,scope,"creating",owner.id,null,false,false,Long.MAX_VALUE)
            .ready(grant,"receiving-upload",false,size,ResourceFiles.checksum(staged),expires,expires+900_000L);
        server.storage.exchange.save(stage,"");
        return Map.of("packageId",id,"grant",grant);
    }
}
