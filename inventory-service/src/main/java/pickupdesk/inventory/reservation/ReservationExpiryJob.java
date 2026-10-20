package pickupdesk.inventory.reservation;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.inventory.InventoryProperties;
import pickupdesk.inventory.stock.StockRepository;

/**
 * Releases stock held by reservations whose 15-minute window passed (payment never completed).
 * Safe to run concurrently on any number of instances: each batch claims rows with FOR UPDATE SKIP LOCKED,
 * and a reservation can only move HELD -> EXPIRED once, so its stock is returned exactly once.
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter expired;

    public ReservationExpiryJob(ReservationRepository reservations, StockRepository stock, TransactionTemplate tx,
                                InventoryProperties props, MeterRegistry meters) {
        this.reservations = reservations;
        this.stock = stock;
        this.tx = tx;
        this.batchSize = props.expiry().batchSize();
        this.expired = Counter.builder("inventory.reservations.expired").register(meters);
    }

    /** Expires one batch in one transaction. @return number of reservations expired. */
    public int expireBatch(int limit) {
        Integer n = tx.execute(status -> {
            List<UUID> ids = reservations.expireDue(limit);
            // Lines arrive ordered by (store, sku): same lock order as reserve, so no deadlock with it.
            for (var line : reservations.linesOf(ids)) {
                stock.adjust(line.storeId(), line.sku(),
                        ReservationStatus.EXPIRED.onHandDeltaPerUnit() * line.qty(),
                        ReservationStatus.EXPIRED.reservedDeltaPerUnit() * line.qty());
            }
            return ids.size();
        });
        return n == null ? 0 : n;
    }

    /** Drains everything currently due, one batch per transaction so locks stay short. */
    public int expireAllDue() {
        int total = 0;
        int n;
        do {
            n = expireBatch(batchSize);
            total += n;
        } while (n == batchSize);
        if (total > 0) {
            expired.increment(total);
            log.info("expired {} reservations", total);
        }
        return total;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "inventory.expiry.enabled", havingValue = "true")
    static class Schedule {

        private final ReservationExpiryJob job;

        Schedule(ReservationExpiryJob job) {
            this.job = job;
        }

        @Scheduled(fixedDelayString = "${inventory.expiry.interval-ms}")
        void run() {
            job.expireAllDue();
        }
    }
}
