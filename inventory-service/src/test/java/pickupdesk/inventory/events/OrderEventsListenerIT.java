package pickupdesk.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.inventory.TestcontainersConfig;
import pickupdesk.inventory.reservation.ReservationService;
import pickupdesk.inventory.reservation.ReserveRequest;
import pickupdesk.inventory.stock.StockRepository;

/** Redelivered order events (what a relay crash produces) are applied exactly once. */
@Import({TestcontainersConfig.class, OrderEventsListenerIT.Kafka.class})
@SpringBootTest(properties = {"inventory.expiry.enabled=false", "inventory.events.enabled=true"})
class OrderEventsListenerIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Kafka {
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:3.9.1");
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ReservationService reservations;
    @Autowired
    StockRepository stock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry meters;

    private void send(UUID eventId, String type, UUID orderId) throws Exception {
        var record = new ProducerRecord<>("order-events", orderId.toString(),
                "{\"orderId\":\"" + orderId + "\",\"type\":\"" + type + "\"}");
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("type", type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private String status(UUID orderId) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :o").param("o", orderId).query(String.class).single();
    }

    private double count(String outcome) {
        var c = meters.find("inventory.events").tag("outcome", outcome).counters();
        return c.stream().mapToDouble(x -> x.count()).sum();
    }

    @Test
    void duplicatePickedUpAndReturnedEventsApplyOnce() throws Exception {
        String store = "STORE-0777", sku = "EVT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        stock.upsertOnHand(store, sku, 10);
        UUID order = UUID.randomUUID();
        reservations.reserve(new ReserveRequest(order, store, java.util.List.of(new ReserveRequest.Line(sku, 3)), null));
        reservations.confirm(order);
        double dupBefore = count("duplicate");

        UUID picked = UUID.randomUUID();
        send(picked, "OrderPickedUp", order);
        send(picked, "OrderPickedUp", order);   // relay resent after a crash
        send(picked, "OrderPickedUp", order);

        await().atMost(Duration.ofSeconds(30)).until(() -> count("duplicate") - dupBefore >= 2);
        assertThat(status(order)).isEqualTo("CONSUMED");
        assertThat(stock.find(store, sku).orElseThrow()).extracting("onHand", "reserved").containsExactly(7, 0);

        UUID returned = UUID.randomUUID();
        send(returned, "OrderReturned", order);
        send(returned, "OrderReturned", order);
        await().atMost(Duration.ofSeconds(30)).until(() -> count("duplicate") - dupBefore >= 3);
        assertThat(status(order)).isEqualTo("RETURNED");
        assertThat(stock.find(store, sku).orElseThrow().onHand()).isEqualTo(10);

        int rows = jdbc.sql("SELECT count(*) FROM processed_events WHERE order_id = :o").param("o", order)
                .query(Integer.class).single();
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void eventThatIsNotLegalForTheReservationChangesNothing() throws Exception {
        String store = "STORE-0778", sku = "EVT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        stock.upsertOnHand(store, sku, 5);
        UUID order = UUID.randomUUID();
        reservations.reserve(new ReserveRequest(order, store, java.util.List.of(new ReserveRequest.Line(sku, 1)), null));
        UUID event = UUID.randomUUID();

        send(event, "OrderPickedUp", order);   // still HELD, never confirmed

        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :e").param("e", event)
                        .query(Integer.class).single() == 1);
        assertThat(status(order)).isEqualTo("HELD");
        assertThat(stock.find(store, sku).orElseThrow()).extracting("onHand", "reserved").containsExactly(5, 1);
    }
}
