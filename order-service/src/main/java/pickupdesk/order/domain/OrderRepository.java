package pickupdesk.order.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String COLUMNS = """
            id, customer_ref, store_id, status, total_cents, payment_token, payment_id, pickup_code_hash,
            pickup_attempts, capture_failures, failure_reason, created_at, updated_at, version""";

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a CREATED order whose saga lease is already held by the caller. */
    public void insert(UUID id, String customerRef, String storeId, long totalCents, String paymentToken,
                       String pickupCodeHash, int leaseSeconds, List<Order.Item> items) {
        jdbc.sql("""
                INSERT INTO orders (id, customer_ref, store_id, status, total_cents, payment_token, pickup_code_hash,
                                    saga_lease_until)
                VALUES (:id, :customer, :store, 'CREATED', :total, :token, :code, now() + make_interval(secs => :lease))
                """)
                .param("id", id)
                .param("customer", customerRef)
                .param("store", storeId)
                .param("total", totalCents)
                .param("token", paymentToken)
                .param("code", pickupCodeHash)
                .param("lease", leaseSeconds)
                .update();
        for (Order.Item item : items) {
            jdbc.sql("INSERT INTO order_items (order_id, sku, qty, unit_price_cents) VALUES (:id, :sku, :qty, :price)")
                    .param("id", id)
                    .param("sku", item.sku())
                    .param("qty", item.qty())
                    .param("price", item.unitPriceCents())
                    .update();
        }
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM orders WHERE id = :id")
                .param("id", id)
                .query(OrderRepository::map)
                .optional()
                .map(o -> o.withItems(items(id)));
    }

    /** Current status with the row locked until the transaction ends, so the audit "from" is exact. */
    public Optional<OrderStatus> lockStatus(UUID id) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class)
                .optional()
                .map(OrderStatus::valueOf);
    }

    /**
     * Atomic, guarded state change: only a row currently in a legal source status moves. Optional fields are
     * set only when non-null.
     */
    public Optional<Order> transition(UUID id, OrderStatus target, String failureReason, UUID paymentId) {
        if (target.legalSources().isEmpty()) {
            return Optional.empty();   // nothing may move into CREATED; also avoids an empty IN ()
        }
        return jdbc.sql("""
                UPDATE orders
                SET status = :target,
                    failure_reason = coalesce(CAST(:reason AS text), failure_reason),
                    payment_id = coalesce(CAST(:paymentId AS uuid), payment_id),
                    version = version + 1,
                    updated_at = now()
                WHERE id = :id AND status IN (:sources)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("target", target.name())
                .param("reason", failureReason)
                .param("paymentId", paymentId)
                .param("id", id)
                .param("sources", target.legalSources().stream().map(Enum::name).toList())
                .query(OrderRepository::map)
                .optional()
                .map(o -> o.withItems(items(id)));
    }

    /** Records why the saga gave up, without changing status; compensation then runs from this state. */
    public boolean recordFailure(UUID id, OrderStatus expectedStatus, String reason) {
        return jdbc.sql("""
                UPDATE orders SET failure_reason = :reason, version = version + 1, updated_at = now()
                WHERE id = :id AND status = :status AND failure_reason IS NULL
                """)
                .param("reason", reason)
                .param("id", id)
                .param("status", expectedStatus.name())
                .update() == 1;
    }

    /**
     * Claims the per-order lease. Only the holder runs saga steps, compensation, pickup or return for this
     * order, so two actors (a client retry and the recovery job, say) never drive the same order at once.
     */
    public boolean tryLease(UUID id, int seconds) {
        return jdbc.sql("""
                UPDATE orders SET saga_lease_until = now() + make_interval(secs => :secs)
                WHERE id = :id AND (saga_lease_until IS NULL OR saga_lease_until < now())
                """)
                .param("secs", seconds)
                .param("id", id)
                .update() == 1;
    }

    public void releaseLease(UUID id) {
        jdbc.sql("UPDATE orders SET saga_lease_until = NULL WHERE id = :id").param("id", id).update();
    }

    public int incrementPickupAttempts(UUID id) {
        return jdbc.sql("UPDATE orders SET pickup_attempts = pickup_attempts + 1 WHERE id = :id RETURNING pickup_attempts")
                .param("id", id).query(Integer.class).single();
    }

    public int incrementCaptureFailures(UUID id) {
        return jdbc.sql("UPDATE orders SET capture_failures = capture_failures + 1 WHERE id = :id RETURNING capture_failures")
                .param("id", id).query(Integer.class).single();
    }

    public void replacePickupCode(UUID id, String hash) {
        jdbc.sql("UPDATE orders SET pickup_code_hash = :h, pickup_attempts = 0 WHERE id = :id")
                .param("h", hash).param("id", id).update();
    }

    /** Unfinished orders nobody has touched for a while and nobody holds a lease on. */
    public List<UUID> findStaleUnfinished(long staleSeconds, int limit) {
        return jdbc.sql("""
                SELECT id FROM orders
                WHERE status IN ('CREATED', 'RESERVED', 'PAYMENT_AUTHORIZED', 'PAYMENT_FAILED', 'EXPIRED')
                  AND updated_at < now() - make_interval(secs => :stale)
                  AND (saga_lease_until IS NULL OR saga_lease_until < now())
                ORDER BY updated_at
                LIMIT :limit
                """)
                .param("stale", staleSeconds)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public List<UUID> findPastPickupWindow(long windowSeconds, int limit) {
        return jdbc.sql("""
                SELECT id FROM orders
                WHERE status IN ('CONFIRMED', 'READY_FOR_PICKUP')
                  AND created_at < now() - make_interval(secs => :window)
                  AND (saga_lease_until IS NULL OR saga_lease_until < now())
                ORDER BY created_at
                LIMIT :limit
                """)
                .param("window", windowSeconds)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public List<Order> pickupQueue(String storeId) {
        return jdbc.sql("""
                SELECT %s FROM orders
                WHERE store_id = :store AND status IN ('CONFIRMED', 'READY_FOR_PICKUP')
                ORDER BY created_at
                LIMIT 200
                """.formatted(COLUMNS))
                .param("store", storeId)
                .query(OrderRepository::map)
                .list()
                .stream()
                .map(o -> o.withItems(items(o.id())))
                .toList();
    }

    private List<Order.Item> items(UUID orderId) {
        return jdbc.sql("SELECT sku, qty, unit_price_cents FROM order_items WHERE order_id = :id ORDER BY sku")
                .param("id", orderId)
                .query(Order.Item.class)
                .list();
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("customer_ref"),
                rs.getString("store_id"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("total_cents"),
                rs.getString("payment_token"),
                rs.getObject("payment_id", UUID.class),
                rs.getString("pickup_code_hash"),
                rs.getInt("pickup_attempts"),
                rs.getInt("capture_failures"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"),
                List.of());
    }
}
