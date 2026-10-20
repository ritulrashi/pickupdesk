package pickupdesk.inventory.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The check and the write are one statement, so there is no read-then-write window. Postgres takes the
 * row lock for the UPDATE; a concurrent UPDATE on the same row waits, then re-evaluates the WHERE clause
 * against the newly committed row (READ COMMITTED re-check), so the predicate always sees fresh counts.
 */
@Component
public class ConditionalUpdateReserver implements StockReserver {

    private final JdbcClient jdbc;

    public ConditionalUpdateReserver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReserveStrategy strategy() {
        return ReserveStrategy.CONDITIONAL;
    }

    @Override
    public boolean tryReserve(String storeId, String sku, int qty) {
        return jdbc.sql("""
                UPDATE stock
                SET reserved = reserved + :qty, version = version + 1, updated_at = now()
                WHERE store_id = :store AND sku = :sku AND on_hand - reserved >= :qty
                """)
                .param("qty", qty)
                .param("store", storeId)
                .param("sku", sku)
                .update() == 1;
    }
}
