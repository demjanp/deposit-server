package deposit.common;

import deposit.repository.ImportResultStore;
import deposit.repository.OperationReceiptRepository;

/** Shared semantic discriminator for receipt-owned immutable import results. */
public final class ImportResultContract {
    public static final String FORMAT = "deposit.import-result";
    public static final long RETENTION = 24L * 60 * 60 * 1000;
    public static final long READER = 15L * 60 * 1000;
    public static final int TEXT_BYTES = 16 * 1024;
    private ImportResultContract() {}

    /** Validates staging ownership before releasing a reservation. Collection leaves an atomic proof. */
    static boolean verifiesResult(ImportResultStore store,OperationReceiptRepository.Receipt receipt,String namespace) {
        if(!namespace.equals(receipt.resultNamespace()) || receipt.importResultId()==null)return false;
        String scope="server".equals(receipt.scope())?"server":"namespace";
        String kind=receipt.resultKind();
        if(!(scope.equals("server")?"clone".equals(kind):"apply".equals(kind)||"analysis".equals(kind)))
            return false;
        var result=store.root(receipt.importResultId(),null);
        if(result==null)return receipt.resultCollected();
        return scope.equals(result.scope()) && namespace.equals(result.namespace())
            && receipt.actor().equals(result.actor()) && receipt.operation().equals(result.operation())
            && kind.equals(result.kind());
    }
}
