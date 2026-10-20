package pickupdesk.inventory.reservation.strategy;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.inventory.InventoryProperties;
import pickupdesk.inventory.stock.StockLevel;

/**
 * Optimistic concurrency: read without locking, write only if the version is unchanged, retry otherwise.
 * Kept for the ADR-007 comparison. Under contention a request can run out of attempts even though stock
 * remains, which shows up as a {@link ContentionException} (a false rejection, not an oversell).
 */
@Component
public class OptimisticReserver implements StockReserver {

    private final JdbcClient jdbc;
    private final int maxAttempts;
    private final Counter conflicts;

    public OptimisticReserver(JdbcClient jdbc, InventoryProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.maxAttempts = props.optimisticMaxAttempts();
        this.conflicts = Counter.builder("inventory.reserve.optimistic.conflicts")
                .description("version conflicts that forced an optimistic retry")
                .register(meters);
    }

    @Override
    public ReserveStrategy strategy() {
        return ReserveStrategy.OPTIMISTIC;
    }

    @Override
    public boolean tryReserve(String storeId, String sku, int qty) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            StockLevel row = jdbc.sql("""
                    SELECT store_id, sku, on_hand, reserved, version FROM stock
                    WHERE store_id = :store AND sku = :sku
                    """)
                    .param("store", storeId)
                    .param("sku", sku)
                    .query(StockLevel.class)
                    .optional()
                    .orElse(null);
            if (row == null || row.available() < qty) {
                return false;
            }
            int updated = jdbc.sql("""
                    UPDATE stock
                    SET reserved = reserved + :qty, version = version + 1, updated_at = now()
                    WHERE store_id = :store AND sku = :sku AND version = :version
                    """)
                    .param("qty", qty)
                    .param("store", storeId)
                    .param("sku", sku)
                    .param("version", row.version())
                    .update();
            if (updated == 1) {
                return true;
            }
            conflicts.increment();
            backoff(attempt);
        }
        throw new ContentionException(storeId, sku, maxAttempts);
    }

    /** Exponential backoff with full jitter: sleep uniformly in [0, 2^attempt) ms. */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1L << Math.min(attempt, 6)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
