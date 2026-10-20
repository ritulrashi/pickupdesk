package pickupdesk.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import pickupdesk.order.OrderITBase;

/**
 * Transactional outbox + relay. "Kill the relay mid-publish": the crash hook throws after N sends, before the
 * batch commits, which is what a process kill at that point does to the database. The rows stay unpublished,
 * the next run resends them, and the topic ends up with every event at least once and some twice.
 */
class OutboxRelayIT extends OrderITBase {

    @Autowired
    OutboxRelay relay;
    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrap;

    @AfterEach
    void disarm() {
        relay.crashHook.disarm();
    }

    private int unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Integer.class).single();
    }

    private List<String> outboxIds() {
        return jdbc.sql("SELECT id::text FROM outbox ORDER BY created_at").query(String.class).list();
    }

    /** Reads the whole topic from the beginning with a throwaway consumer group, returning eventId headers. */
    private List<String> eventIdsOnTopic(int atLeast) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> ids = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of("order-events"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (ids.size() < atLeast && System.currentTimeMillis() < deadline) {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    ids.add(new String(r.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
                }
            }
            // drain whatever else is there
            for (var r : consumer.poll(Duration.ofSeconds(2))) {
                ids.add(new String(r.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
            }
        }
        return ids;
    }

    @Test
    void stateChangeAndItsEventCommitTogetherAndRelayPublishesAll() {
        allHappy();
        relay.publishAll();   // flush rows left by other tests
        create();
        create();
        assertThat(unpublished()).isEqualTo(8);

        relay.publishAll();

        assertThat(unpublished()).isZero();
        assertThat(eventIdsOnTopic(outboxIds().size())).containsAll(outboxIds());
    }

    @Test
    void relayKilledMidBatchResendsEverythingAndLosesNothing() {
        allHappy();
        relay.publishAll();
        Set<String> before = new HashSet<>(outboxIds());
        for (int i = 0; i < 3; i++) {
            create();
        }
        List<String> fresh = outboxIds().stream().filter(id -> !before.contains(id)).toList();
        assertThat(fresh).hasSize(12);

        relay.crashHook.arm(5);   // die after the 5th send of the batch, before marking anything published
        assertThatThrownBy(relay::publishBatch).isInstanceOf(OutboxRelay.SimulatedCrash.class);
        assertThat(unpublished()).as("crash rolled back published_at").isEqualTo(12);

        relay.crashHook.disarm();
        relay.publishAll();
        assertThat(unpublished()).isZero();

        List<String> onTopic = eventIdsOnTopic(outboxIds().size() + 5);
        assertThat(onTopic).containsAll(fresh);
        long duplicates = fresh.stream().filter(id -> onTopic.stream().filter(id::equals).count() > 1).count();
        assertThat(duplicates).as("at-least-once: the first 5 were sent twice").isEqualTo(5);
    }
}
