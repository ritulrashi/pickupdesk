package pickupdesk.inventory.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    /** One row of reservation_lines joined with its reservation's store. */
    public record StoreLine(String storeId, String sku, int qty) {
    }

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a HELD reservation unless one already exists for the order. A concurrent insert for the same
     * order blocks on the unique index until the first transaction finishes, then becomes a no-op.
     *
     * @return true if this call created the row
     */
    public boolean insertHeld(UUID id, UUID orderId, String storeId, int ttlSeconds) {
        return jdbc.sql("""
                INSERT INTO reservations (id, order_id, store_id, status, expires_at)
                VALUES (:id, :orderId, :store, 'HELD', now() + make_interval(secs => :ttl))
                ON CONFLICT (order_id) DO NOTHING
                """)
                .param("id", id)
                .param("orderId", orderId)
                .param("store", storeId)
                .param("ttl", ttlSeconds)
                .update() == 1;
    }

    /** Records that the order was released before any reservation existed, so a late reserve becomes a no-op. */
    public boolean insertTombstone(UUID id, UUID orderId) {
        return jdbc.sql("""
                INSERT INTO reservations (id, order_id, store_id, status, expires_at)
                VALUES (:id, :orderId, NULL, 'RELEASED', NULL)
                ON CONFLICT (order_id) DO NOTHING
                """)
                .param("id", id)
                .param("orderId", orderId)
                .update() == 1;
    }

    public void insertLine(UUID reservationId, String sku, int qty) {
        jdbc.sql("INSERT INTO reservation_lines (reservation_id, sku, qty) VALUES (:id, :sku, :qty)")
                .param("id", reservationId)
                .param("sku", sku)
                .param("qty", qty)
                .update();
    }

    public Optional<Reservation> findByOrderId(UUID orderId) {
        return jdbc.sql("""
                SELECT id, order_id, store_id, status, expires_at FROM reservations WHERE order_id = :orderId
                """)
                .param("orderId", orderId)
                .query(this::mapHeader)
                .optional()
                .map(r -> new Reservation(r.id(), r.orderId(), r.storeId(), r.status(), r.expiresAt(), lines(r.id())));
    }

    /**
     * Atomically moves the order's reservation into {@code target} if its current status is a legal source.
     * The row lock taken by the UPDATE serializes this against the expiry job and other transitions; a
     * waiting UPDATE re-checks the status after the lock is released, so each transition happens once.
     *
     * @return the reservation after the transition, or empty if no legal transition happened
     */
    public Optional<Reservation> transition(UUID orderId, ReservationStatus target) {
        List<String> sources = target.legalSources().stream().map(Enum::name).toList();
        return jdbc.sql("""
                UPDATE reservations
                SET status = :target, expires_at = NULL, updated_at = now()
                WHERE order_id = :orderId AND status IN (:sources)
                RETURNING id, order_id, store_id, status, expires_at
                """)
                .param("target", target.name())
                .param("orderId", orderId)
                .param("sources", sources)
                .query(this::mapHeader)
                .optional()
                .map(r -> new Reservation(r.id(), r.orderId(), r.storeId(), r.status(), r.expiresAt(), lines(r.id())));
    }

    /**
     * Claims up to {@code limit} HELD reservations whose expiry has passed and marks them EXPIRED.
     * SKIP LOCKED means two concurrent runs (two instances, or a run overlapping the next tick) claim
     * disjoint rows instead of blocking or double-processing.
     */
    public List<UUID> expireDue(int limit) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM reservations
                    WHERE status = 'HELD' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE reservations r
                SET status = 'EXPIRED', updated_at = now()
                FROM due
                WHERE r.id = due.id
                RETURNING r.id
                """)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Lines of the given reservations, ordered by (store, sku) so stock rows are always locked in the same order. */
    public List<StoreLine> linesOf(Collection<UUID> reservationIds) {
        if (reservationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT r.store_id, l.sku, l.qty
                FROM reservation_lines l JOIN reservations r ON r.id = l.reservation_id
                WHERE r.id IN (:ids)
                ORDER BY r.store_id, l.sku
                """)
                .param("ids", reservationIds)
                .query(StoreLine.class)
                .list();
    }

    private List<Reservation.Line> lines(UUID reservationId) {
        return jdbc.sql("SELECT sku, qty FROM reservation_lines WHERE reservation_id = :id ORDER BY sku")
                .param("id", reservationId)
                .query(Reservation.Line.class)
                .list();
    }

    private Reservation mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("store_id"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getObject("expires_at", OffsetDateTime.class),
                List.of());
    }
}
