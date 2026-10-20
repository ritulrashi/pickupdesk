package pickupdesk.order.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import pickupdesk.order.outbox.OutboxRepository;

/**
 * The only way an order changes state. In one transaction: the guarded UPDATE, an append-only audit row, and
 * an outbox row. Either all three commit or none do, so Kafka consumers can never see an event for a state
 * change that rolled back, and no committed state change can lack its event (no dual write).
 */
@Component
public class OrderTransitions {

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public OrderTransitions(OrderRepository orders, OutboxRepository outbox, JdbcClient jdbc,
                            TransactionTemplate tx, ObjectMapper json) {
        this.orders = orders;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    /** @return the order after the change, or empty if its current status does not allow {@code target} */
    public Optional<Order> apply(UUID orderId, OrderStatus target, FailureReason reason, UUID paymentId) {
        return tx.execute(t -> {
            OrderStatus from = orders.lockStatus(orderId).orElse(null);
            var moved = orders.transition(orderId, target, reason == null ? null : reason.name(), paymentId);
            moved.ifPresent(o -> record(o, from, target.eventType(), target));
            return moved;
        });
    }

    public Optional<Order> apply(UUID orderId, OrderStatus target) {
        return apply(orderId, target, null, null);
    }

    /** Called inside the creating transaction for the initial CREATED event. */
    public void recordCreated(Order created) {
        record(created, null, OrderStatus.CREATED.eventType(), OrderStatus.CREATED);
    }

    /** Saga gave up but compensation still has to run; the status stays, the reason is recorded and audited. */
    public boolean recordFailure(UUID orderId, OrderStatus expected, FailureReason reason) {
        Boolean done = tx.execute(t -> {
            if (!orders.recordFailure(orderId, expected, reason.name())) {
                return false;
            }
            jdbc.sql("""
                    INSERT INTO order_events (order_id, type, from_status, to_status, payload)
                    VALUES (:id, 'SagaFailureRecorded', :s, :s, CAST(:payload AS jsonb))
                    """)
                    .param("id", orderId)
                    .param("s", expected.name())
                    .param("payload", write(Map.of("failureReason", reason.name())))
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private void record(Order o, OrderStatus from, String type, OrderStatus to) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("type", type);
        payload.put("orderId", o.id());
        payload.put("storeId", o.storeId());
        payload.put("status", to.name());
        payload.put("previousStatus", from == null ? null : from.name());
        payload.put("failureReason", o.failureReason());
        payload.put("totalCents", o.totalCents());
        payload.put("items", o.items().stream().map(i -> Map.of("sku", i.sku(), "qty", i.qty())).toList());
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        String body = write(payload);

        jdbc.sql("""
                INSERT INTO order_events (order_id, type, from_status, to_status, payload)
                VALUES (:id, :type, :from, :to, CAST(:payload AS jsonb))
                """)
                .param("id", o.id())
                .param("type", type)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("payload", body)
                .update();
        outbox.append(eventId, o.id(), type, body);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
