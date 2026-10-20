package pickupdesk.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import pickupdesk.inventory.reservation.ReserveRequest;
import pickupdesk.inventory.stock.StockLevel;
import pickupdesk.inventory.stock.StockRepository;

/**
 * Shared setup for inventory ITs: real Postgres via Testcontainers, the scheduled expiry job disabled so
 * tests drive it explicitly, and helpers that give every test its own store/sku so tests never interfere.
 */
@Import(TestcontainersConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.expiry.enabled=false")
public abstract class InventoryITBase {

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected StockRepository stockRepo;

    protected static String newStore() {
        return "STORE-%04d".formatted(ThreadLocalRandom.current().nextInt(10_000));
    }

    protected static String newSku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected void seed(String store, String sku, int onHand) {
        stockRepo.upsertOnHand(store, sku, onHand);
    }

    protected StockLevel stock(String store, String sku) {
        return stockRepo.find(store, sku).orElseThrow();
    }

    protected static ReserveRequest request(UUID orderId, String store, String sku, int qty) {
        return new ReserveRequest(orderId, store, List.of(new ReserveRequest.Line(sku, qty)), null);
    }

    /** Units held by HELD/CONFIRMED reservation lines for this row: must always equal stock.reserved. */
    protected int heldUnits(String store, String sku) {
        return jdbc.sql("""
                SELECT coalesce(sum(l.qty), 0) FROM reservation_lines l
                JOIN reservations r ON r.id = l.reservation_id
                WHERE r.store_id = :store AND l.sku = :sku AND r.status IN ('HELD', 'CONFIRMED')
                """)
                .params(Map.of("store", store, "sku", sku))
                .query(Integer.class)
                .single();
    }

    protected String statusOf(UUID orderId) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
