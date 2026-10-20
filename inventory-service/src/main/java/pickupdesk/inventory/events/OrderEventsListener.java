package pickupdesk.inventory.events;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.inventory.reservation.ReservationService;
import pickupdesk.inventory.reservation.ReservationStatus;

/**
 * Consumes order-service's outbox stream. Delivery is at least once (the relay may resend after a crash), so
 * each event id is recorded in {@code processed_events} in the same transaction as its effect: a redelivered
 * event finds its row and does nothing. The reservation transitions are themselves idempotent by status, so
 * the effect is applied once even before dedupe; the table makes that explicit and auditable.
 */
@Component
@ConditionalOnProperty(name = "inventory.events.enabled", havingValue = "true")
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final ReservationService reservations;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public OrderEventsListener(ReservationService reservations, JdbcClient jdbc, TransactionTemplate tx,
                               ObjectMapper json, MeterRegistry meters) {
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    @KafkaListener(topics = "${inventory.events.topic}", groupId = "inventory-service")
    public void on(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "type");
        ReservationStatus target = switch (type == null ? "" : type) {
            case "OrderPickedUp" -> ReservationStatus.CONSUMED;
            case "OrderReturned" -> ReservationStatus.RETURNED;
            default -> null;
        };
        if (target == null) {
            return;   // not an event inventory acts on
        }
        JsonNode payload = json.readTree(record.value());
        UUID eventId = UUID.fromString(header(record, "eventId"));
        UUID orderId = UUID.fromString(payload.path("orderId").asText());

        String outcome = tx.execute(status -> {
            boolean first = jdbc.sql("""
                    INSERT INTO processed_events (event_id, type, order_id, applied) VALUES (:id, :type, :order, false)
                    ON CONFLICT (event_id) DO NOTHING
                    """)
                    .param("id", eventId).param("type", type).param("order", orderId)
                    .update() == 1;
            if (!first) {
                return "duplicate";
            }
            boolean applied = reservations.applyIfLegal(orderId, target);
            if (applied) {
                jdbc.sql("UPDATE processed_events SET applied = true WHERE event_id = :id").param("id", eventId).update();
            }
            return applied ? "applied" : "not_applicable";
        });
        meters.counter("inventory.events", "type", type, "outcome", outcome).increment();
        if (!"applied".equals(outcome)) {
            log.info("order event {} {} for order {}: {}", eventId, type, orderId, outcome);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /**
     * Transient failures (database down) are retried 5 times, 1 s apart, then the record is logged and skipped.
     * There is deliberately no dead-letter topic here (out of scope, see spec section 1).
     */
    @Bean
    static DefaultErrorHandler orderEventsErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000, 5));
    }
}
