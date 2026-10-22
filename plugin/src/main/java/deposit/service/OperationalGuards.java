package deposit.service;

import deposit.common.RequestContext;

/** Narrow checks shared by operational callers; callers retain lookup and sequencing ownership. */
final class OperationalGuards {
    private OperationalGuards() {}

    static void operationalContext(RequestContext c) {
        if(c.operationId==null||c.expectedRevision!=null)
            c.fail("VALIDATION_FAILED","Operational writes require operationId and no expectedRevision");
    }

    static void bindingScope(RequestContext c,String scope,Object bindingScope) {
        if(!scope.equals(bindingScope))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
    }

    static void receiptActor(RequestContext c,Object actorId) {
        if(!c.actorId.equals(actorId))c.fail("FORBIDDEN","Operation belongs to another actor");
    }

    static void receiptHash(RequestContext c,String hash,Object requestHash) {
        if(!hash.equals(requestHash))c.fail("OPERATION_MISMATCH","Operation input changed");
    }
}
