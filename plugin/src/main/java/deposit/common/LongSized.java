package deposit.common;

/** Long cardinality for streamed collections whose Java Collection.size() must saturate at int max. */
public interface LongSized {
    long longSize();
}
