package pickupdesk.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String COLUMNS =
            "id, order_id, token, amount_cents, status, decline_reason, request_hash, created_at";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the payment unless the idempotency key was already used. A concurrent insert with the same key
     * waits on the unique index for the first transaction, then becomes a no-op.
     *
     * @return the new row, or empty if the key already existed
     */
    public Optional<Payment> insertIfNewKey(UUID id, UUID orderId, String token, long amountCents,
                                            PaymentStatus status, String declineReason,
                                            String idempotencyKey, String requestHash) {
        return jdbc.sql("""
                INSERT INTO payments (id, order_id, token, amount_cents, status, decline_reason, idempotency_key, request_hash)
                VALUES (:id, :orderId, :token, :amount, :status, :reason, :key, :hash)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", id)
                .param("orderId", orderId)
                .param("token", token)
                .param("amount", amountCents)
                .param("status", status.name())
                .param("reason", declineReason)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .query(this::map)
                .optional();
    }

    public Optional<Payment> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT %s FROM payments WHERE idempotency_key = :key".formatted(COLUMNS))
                .param("key", key)
                .query(this::map)
                .optional();
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.sql("SELECT %s FROM payments WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Atomic, race-free state change: only succeeds from a legal source status. */
    public Optional<Payment> transition(UUID id, PaymentStatus target) {
        if (target.legalSources().isEmpty()) {
            return Optional.empty();   // AUTHORIZED/DECLINED are only entered by authorize; avoids an empty IN ()
        }
        return jdbc.sql("""
                UPDATE payments SET status = :target, updated_at = now()
                WHERE id = :id AND status IN (:sources)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("target", target.name())
                .param("id", id)
                .param("sources", target.legalSources().stream().map(Enum::name).toList())
                .query(this::map)
                .optional();
    }

    /**
     * Serializes authorize and cancel-order for one order (transaction-scoped advisory lock). Without it an
     * authorize could check the fence, a cancel could then fence and find nothing to void, and the authorize
     * would commit a live charge for a cancelled order.
     */
    public void lockOrder(UUID orderId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:id, 0))) AS locked")
                .param("id", orderId.toString())
                .query(Integer.class)
                .single();
    }

    public boolean isFenced(UUID orderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM order_fences WHERE order_id = :id)")
                .param("id", orderId)
                .query(Boolean.class)
                .single();
    }

    public void insertFence(UUID orderId) {
        jdbc.sql("INSERT INTO order_fences (order_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", orderId)
                .update();
    }

    /** @return ids of the authorizations that were voided */
    public java.util.List<UUID> voidLiveAuthorizations(UUID orderId) {
        return jdbc.sql("""
                UPDATE payments SET status = 'VOIDED', updated_at = now()
                WHERE order_id = :id AND status = 'AUTHORIZED'
                RETURNING id
                """)
                .param("id", orderId)
                .query(UUID.class)
                .list();
    }

    public boolean hasCapture(UUID orderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments WHERE order_id = :id AND status = 'CAPTURED')")
                .param("id", orderId)
                .query(Boolean.class)
                .single();
    }

    public void appendEvent(UUID paymentId, PaymentStatus type) {
        jdbc.sql("INSERT INTO payment_events (payment_id, type) VALUES (:id, :type)")
                .param("id", paymentId)
                .param("type", type.name())
                .update();
    }

    private Payment map(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("token"),
                rs.getLong("amount_cents"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("decline_reason"),
                rs.getString("request_hash"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
