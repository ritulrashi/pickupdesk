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
