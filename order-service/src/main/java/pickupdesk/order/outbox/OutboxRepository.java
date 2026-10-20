package pickupdesk.order.outbox;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    public record OutboxRow(UUID id, UUID aggregateId, String type, String payload) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the transaction that made the state change: that is the whole point. */
    public void append(UUID id, UUID aggregateId, String type, String payloadJson) {
        jdbc.sql("INSERT INTO outbox (id, aggregate_id, type, payload) VALUES (:id, :agg, :type, CAST(:payload AS jsonb))")
                .param("id", id)
                .param("agg", aggregateId)
                .param("type", type)
                .param("payload", payloadJson)
                .update();
    }

    /** Oldest unpublished rows, locked so that a second relay instance skips them instead of double-sending. */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, type, payload::text AS payload FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(OutboxRow.class)
                .list();
    }

    public void markPublished(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)").param("ids", ids).update();
        }
    }

    /** Age in seconds of the oldest unpublished row, 0 if none. Exposed as the outbox-lag metric. */
    public double oldestUnpublishedAgeSeconds() {
        Double age = jdbc.sql("""
                SELECT extract(epoch FROM now() - min(created_at)) FROM outbox WHERE published_at IS NULL
                """)
                .query(Double.class)
                .optional()
                .orElse(null);
        return age == null ? 0 : age;
    }
}
