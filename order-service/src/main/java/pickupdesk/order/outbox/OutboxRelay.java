package pickupdesk.order.outbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.order.OrderProperties;

/**
 * Publishes outbox rows to Kafka, at least once. One batch is one DB transaction: lock unpublished rows
 * (SKIP LOCKED, so several instances can relay in parallel), send each and wait for the broker ack, then mark
 * them published. A crash anywhere before commit leaves the rows unpublished, and they are sent again on the
 * next run: duplicates are possible, loss is not. Consumers dedupe on the {@code eventId} header.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Test hook that simulates the process dying after N sends, before the batch commits. */
    public static class CrashAfterSends {
        private final AtomicInteger remaining = new AtomicInteger(-1);

        public void arm(int sends) {
            remaining.set(sends);
        }

        public void disarm() {
            remaining.set(-1);
        }

        /** Throws on the N-th send after {@link #arm(int) arm(N)}. */
        void afterSend() {
            if (remaining.get() > 0 && remaining.decrementAndGet() == 0) {
                remaining.set(-1);
                throw new SimulatedCrash();
            }
        }
    }

    public static class SimulatedCrash extends RuntimeException {
        SimulatedCrash() {
            super("simulated relay crash mid-batch");
        }
    }

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final int batchSize;
    private final Counter published;
    public final CrashAfterSends crashHook = new CrashAfterSends();

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OrderProperties props, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = props.topic();
        this.batchSize = props.jobs().relayBatchSize();
        this.published = Counter.builder("order.outbox.published").register(meters);
        Gauge.builder("order.outbox.lag.seconds", outbox, OutboxRepository::oldestUnpublishedAgeSeconds)
                .description("age of the oldest unpublished outbox row")
                .register(meters);
    }

    /** @return number of rows published in this batch */
    public int publishBatch() {
        Integer n = tx.execute(status -> {
            List<OutboxRepository.OutboxRow> rows = outbox.lockUnpublished(batchSize);
            List<UUID> sent = new ArrayList<>(rows.size());
            for (var row : rows) {
                // Keyed by order id: all events of one order land on one partition, in order.
                var record = new ProducerRecord<>(topic, row.aggregateId().toString(), row.payload());
                record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("type", row.type().getBytes(StandardCharsets.UTF_8));
                try {
                    kafka.send(record).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("kafka send failed for outbox row " + row.id(), e);
                }
                sent.add(row.id());
                crashHook.afterSend();
            }
            outbox.markPublished(sent);
            return sent.size();
        });
        int count = n == null ? 0 : n;
        published.increment(count);
        return count;
    }

    /** Drains the outbox; stops at the first failure and leaves the rest for the next tick. */
    public int publishAll() {
        int total = 0;
        int n;
        try {
            do {
                n = publishBatch();
                total += n;
            } while (n == batchSize);
        } catch (RuntimeException e) {
            log.warn("outbox relay batch failed, will retry: {}", e.getMessage());
        }
        return total;
    }
}
