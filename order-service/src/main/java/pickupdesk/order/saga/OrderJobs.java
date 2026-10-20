package pickupdesk.order.saga;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pickupdesk.order.OrderProperties;
import pickupdesk.order.domain.FailureReason;
import pickupdesk.order.domain.Order;
import pickupdesk.order.domain.OrderRepository;
import pickupdesk.order.domain.OrderStatus;
import pickupdesk.order.domain.OrderTransitions;
import pickupdesk.order.outbox.OutboxRelay;

/**
 * Background work that makes the system converge after crashes and outages:
 * <ul>
 *   <li>recovery: resume or compensate unfinished orders nobody is working on (orchestrator crashed, client
 *       gave up, downstream was down during compensation);</li>
 *   <li>pickup-window expiry: CONFIRMED / READY_FOR_PICKUP orders never collected -> EXPIRED -> compensated;</li>
 *   <li>the outbox relay.</li>
 * </ul>
 * Each order is processed only while holding its lease, so jobs on several instances never collide.
 */
@Component
public class OrderJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final SagaOrchestrator saga;
    private final OutboxRelay relay;
    private final OrderProperties props;

    public OrderJobs(OrderRepository orders, OrderTransitions transitions, SagaOrchestrator saga, OutboxRelay relay,
                     OrderProperties props) {
        this.orders = orders;
        this.transitions = transitions;
        this.saga = saga;
        this.relay = relay;
        this.props = props;
    }

    /** @return number of orders it touched */
    public int recoverStale() {
        List<UUID> ids = orders.findStaleUnfinished(props.jobs().recoveryStaleSeconds(), 50);
        int touched = 0;
        for (UUID id : ids) {
            if (!orders.tryLease(id, props.leaseSeconds())) {
                continue;
            }
            try {
                Order o = orders.find(id).orElseThrow();
                giveUpIfPastDeadline(o);
                Order after = saga.advance(id);
                log.info("recovery: order {} {} -> {}", id, o.status(), after.status());
                touched++;
            } catch (RuntimeException e) {
                log.warn("recovery of order {} failed: {}", id, e.getMessage());
            } finally {
                orders.releaseLease(id);
            }
        }
        return touched;
    }

    /** A forward saga that cannot finish within the deadline is turned into a compensation. */
    private void giveUpIfPastDeadline(Order o) {
        boolean pastDeadline = o.createdAt().toInstant()
                .isBefore(java.time.Instant.now().minusSeconds(props.sagaDeadlineSeconds()));
        if (!pastDeadline || o.failureReason() != null) {
            return;
        }
        if (o.status() == OrderStatus.CREATED) {
            transitions.recordFailure(o.id(), OrderStatus.CREATED, FailureReason.SAGA_TIMEOUT);
        } else if (o.status() == OrderStatus.RESERVED) {
            transitions.apply(o.id(), OrderStatus.PAYMENT_FAILED, FailureReason.SAGA_TIMEOUT, null);
        }
    }

    public int expirePastPickupWindow() {
        List<UUID> ids = orders.findPastPickupWindow(props.pickupWindowSeconds(), 50);
        int expired = 0;
        for (UUID id : ids) {
            if (!orders.tryLease(id, props.leaseSeconds())) {
                continue;
            }
            try {
                if (transitions.apply(id, OrderStatus.EXPIRED, FailureReason.PICKUP_WINDOW_EXPIRED, null).isPresent()) {
                    saga.advance(id);   // compensation: void authorization, release hold
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("expiry of order {} failed: {}", id, e.getMessage());
            } finally {
                orders.releaseLease(id);
            }
        }
        return expired;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true")
    static class Schedule {

        private final OrderJobs jobs;

        Schedule(OrderJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(fixedDelayString = "${order.jobs.relay-interval-ms}")
        void relay() {
            jobs.relay.publishAll();
        }

        @Scheduled(fixedDelayString = "${order.jobs.recovery-interval-ms}", initialDelay = 5000)
        void recover() {
            jobs.recoverStale();
        }

        @Scheduled(fixedDelayString = "${order.jobs.expiry-interval-ms}", initialDelay = 10000)
        void expire() {
            jobs.expirePastPickupWindow();
        }
    }
}
