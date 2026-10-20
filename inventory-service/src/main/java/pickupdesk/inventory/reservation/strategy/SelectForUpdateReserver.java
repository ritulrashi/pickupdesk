package pickupdesk.inventory.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Pessimistic: lock the row first, decide in Java, then write. Correct, but the lock is held across two
 * round trips instead of one, so contended rows serialize for longer than with {@link ConditionalUpdateReserver}.
 */
@Component
public class SelectForUpdateReserver implements StockReserver {

    private final JdbcClient jdbc;

    public SelectForUpdateReserver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReserveStrategy strategy() {
        return ReserveStrategy.FOR_UPDATE;
    }

    @Override
    public boolean tryReserve(String storeId, String sku, int qty) {
        Integer available = jdbc.sql("""
                SELECT on_hand - reserved FROM stock
                WHERE store_id = :store AND sku = :sku
                FOR UPDATE
                """)
                .param("store", storeId)
                .param("sku", sku)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (available == null || available < qty) {
            return false;
        }
        jdbc.sql("""
                UPDATE stock
                SET reserved = reserved + :qty, version = version + 1, updated_at = now()
                WHERE store_id = :store AND sku = :sku
                """)
                .param("qty", qty)
                .param("store", storeId)
                .param("sku", sku)
                .update();
        return true;
    }
}
