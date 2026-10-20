package pickupdesk.order.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record Record(String key, String requestHash, UUID orderId, String status, Integer responseStatus,
                         String responseBody) {
        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for a new order. Concurrent claims of the same key serialize on the primary key: the
     * second waits for the first transaction and then gets false.
     */
    public boolean claim(String key, String requestHash, UUID orderId) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (key, request_hash, order_id, status)
                VALUES (:key, :hash, :orderId, 'IN_PROGRESS')
                ON CONFLICT (key) DO NOTHING
                """)
                .param("key", key)
                .param("hash", requestHash)
                .param("orderId", orderId)
                .update() == 1;
    }

    public Optional<Record> find(String key) {
        return jdbc.sql("""
                SELECT key, request_hash, order_id, status, response_status, response_body::text AS response_body
                FROM idempotency_keys WHERE key = :key
                """)
                .param("key", key)
                .query(Record.class)
                .optional();
    }

    public void complete(String key, int responseStatus, String responseBody) {
        jdbc.sql("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb), updated_at = now()
                WHERE key = :key AND status = 'IN_PROGRESS'
                """)
                .param("status", responseStatus)
                .param("body", responseBody)
                .param("key", key)
                .update();
    }
}
