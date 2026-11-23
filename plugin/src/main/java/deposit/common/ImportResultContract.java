package deposit.common;

import deposit.repository.CoreRepository;
import org.neo4j.graphdb.Node;

/** Shared semantic discriminator for receipt-owned immutable import results. */
public final class ImportResultContract {
    public static final String FORMAT = "deposit.import-result";
    public static final String LABEL = "DepositImportResult";
    public static final long RETENTION = 24L * 60 * 60 * 1000;
    public static final long READER = 15L * 60 * 1000;
    public static final int TEXT_BYTES = 16 * 1024;
    private ImportResultContract() {}

    /** Validates staging ownership before releasing a reservation. Collection leaves an atomic proof. */
    static boolean verifiesResult(CoreRepository core,Node receipt,String namespace) {
        if(!namespace.equals(receipt.getProperty("resultNamespaceId",null))
                || !(receipt.getProperty("importResultId",null) instanceof String id))return false;
        Node result=core.find(LABEL,"id",id);
        String scope="server".equals(receipt.getProperty("scope"))?"server":"namespace";
        Object kind=receipt.getProperty("resultKind",null);
        if(result==null)return Boolean.TRUE.equals(receipt.getProperty("importResultCollected",false))
            && (scope.equals("server")?"clone".equals(kind):"apply".equals(kind)||"analysis".equals(kind));
        return scope.equals(result.getProperty("scope",null)) && namespace.equals(result.getProperty("namespaceId",null))
            && receipt.getProperty("actorId").equals(result.getProperty("actorId",null))
            && receipt.getProperty("operationId").equals(result.getProperty("operationId",null))
            && (kind==null || kind.equals(result.getProperty("kind",null)))
            && (!scope.equals("server") || "clone".equals(result.getProperty("kind",null)));
    }

}
