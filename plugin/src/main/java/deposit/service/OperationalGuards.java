package deposit.service;

import deposit.common.RequestContext;

/** Narrow checks shared by operational callers; callers retain lookup and sequencing ownership. */
final class OperationalGuards {
    private OperationalGuards() {}

    static void operationalContext(RequestContext c) {
        if(c.operationId==null||c.expectedRevision!=null)
            c.fail("VALIDATION_FAILED","Operational writes require operationId and no expectedRevision");
    }

}
