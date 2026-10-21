package deposit.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-database owner for immutable-resource read pins and their idempotent release handles. */
final class ResourceReaderPins {
    private final ConcurrentMap<String,AtomicInteger> readers = new ConcurrentHashMap<>();

    AutoCloseable pin(String resourceId) {
        AtomicInteger count = readers.compute(resourceId, (key,current) -> {
            if (current == null) current = new AtomicInteger();
            current.incrementAndGet();
            return current;
        });
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false,true)) readers.computeIfPresent(resourceId,
                (key,current) -> current.decrementAndGet() == 0 ? null : current);
        };
    }

    boolean hasPin(String resourceId) {
        AtomicInteger value = readers.get(resourceId);
        return value != null && value.get() > 0;
    }
}
