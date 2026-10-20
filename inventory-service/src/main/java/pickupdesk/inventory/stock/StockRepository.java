package pickupdesk.inventory.stock;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StockLevel> find(String storeId, String sku) {
        return jdbc.sql("""
                SELECT store_id, sku, on_hand, reserved, version FROM stock
                WHERE store_id = :store AND sku = :sku
                """)
                .param("store", storeId)
                .param("sku", sku)
                .query(StockLevel.class)
                .optional();
    }

    /** Sets on_hand absolutely (seed / restock / cycle count). Fails the CHECK if it would drop below reserved. */
    public StockLevel upsertOnHand(String storeId, String sku, int onHand) {
        return jdbc.sql("""
                INSERT INTO stock (store_id, sku, on_hand) VALUES (:store, :sku, :onHand)
                ON CONFLICT (store_id, sku) DO UPDATE
                    SET on_hand = EXCLUDED.on_hand, version = stock.version + 1, updated_at = now()
                RETURNING store_id, sku, on_hand, reserved, version
                """)
                .param("store", storeId)
                .param("sku", sku)
                .param("onHand", onHand)
                .query(StockLevel.class)
                .single();
    }

    /**
     * Applies a lifecycle delta (release, consume, return) to one stock row. Callers must invoke this
     * in (store_id, sku) order so concurrent multi-line transactions always lock rows in the same order.
     */
    public void adjust(String storeId, String sku, int onHandDelta, int reservedDelta) {
        int rows = jdbc.sql("""
                UPDATE stock
                SET on_hand = on_hand + :dOnHand, reserved = reserved + :dReserved,
                    version = version + 1, updated_at = now()
                WHERE store_id = :store AND sku = :sku
                """)
                .param("dOnHand", onHandDelta)
                .param("dReserved", reservedDelta)
                .param("store", storeId)
                .param("sku", sku)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("stock row missing for " + storeId + "/" + sku);
        }
    }
}
