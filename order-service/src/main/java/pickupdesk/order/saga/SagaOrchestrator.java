package pickupdesk.order.saga;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.order.clients.DownstreamUnavailable;
import pickupdesk.order.clients.InventoryClient;
import pickupdesk.order.clients.InventoryClient.ConfirmOutcome;
import pickupdesk.order.clients.InventoryClient.ReserveOutcome;
import pickupdesk.order.clients.PaymentClient;
import pickupdesk.order.clients.PaymentClient.AuthorizeOutcome;
import pickupdesk.order.domain.FailureReason;
import pickupdesk.order.domain.Order;
import pickupdesk.order.domain.OrderRepository;
import pickupdesk.order.domain.OrderStatus;
import pickupdesk.order.domain.OrderTransitions;

/**
 * Orchestrated saga. {@link #advance} looks at the order's persisted state and performs the next step, until no
 * further progress is possible. Because the next step is derived from the database, not from memory, the same
 * method serves the original request, a client retry, and crash recovery: the saga state is the order row.
 *
 * <p>Every remote step is idempotent (reserve by orderId, authorize with a key derived from orderId, confirm and
 * release by orderId, cancel-order by orderId), so re-running a step after a crash is safe. The caller must
 * hold the order's lease.
 *
 * <pre>
 * CREATED            -> reserve             -> RESERVED | CANCELLED(OUT_OF_STOCK, RESERVATION_LOST)
 *                       unknown outcome     -> failure recorded, then release -> CANCELLED(INVENTORY_UNAVAILABLE)
 * RESERVED           -> authorize           -> PAYMENT_AUTHORIZED | PAYMENT_FAILED(DECLINED | UNAVAILABLE)
 * PAYMENT_AUTHORIZED -> confirm reservation -> CONFIRMED | PAYMENT_FAILED(RESERVATION_LOST)
 * PAYMENT_FAILED, EXPIRED, or CREATED/RESERVED with a recorded failure
 *                    -> cancel-order at payment (void + fence), release at inventory -> CANCELLED
 * </pre>
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private static final int MAX_STEPS = 10;

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final InventoryClient inventory;
    private final PaymentClient payment;
    private final MeterRegistry meters;

    public SagaOrchestrator(OrderRepository orders, OrderTransitions transitions, InventoryClient inventory,
                            PaymentClient payment, MeterRegistry meters) {
        this.orders = orders;
        this.transitions = transitions;
        this.inventory = inventory;
        this.payment = payment;
        this.meters = meters;
    }

    /** Drives the order forward (or through compensation) as far as it can go right now. */
    public Order advance(UUID orderId) {
        for (int i = 0; i < MAX_STEPS; i++) {
            Order o = orders.find(orderId).orElseThrow();
            boolean progressed = switch (o.status()) {
                case CREATED -> o.failureReason() == null ? reserve(o) : compensate(o, false);
                case RESERVED -> o.failureReason() == null ? authorize(o) : compensate(o, true);
                case PAYMENT_AUTHORIZED -> confirm(o);
                case PAYMENT_FAILED, EXPIRED -> compensate(o, true);
                default -> false;
            };
            if (!progressed) {
                return orders.find(orderId).orElseThrow();
            }
        }
        return orders.find(orderId).orElseThrow();
    }

    private boolean reserve(Order o) {
        try {
            return switch (inventory.reserve(o)) {
                case ReserveOutcome.Held h -> moved(transitions.apply(o.id(), OrderStatus.RESERVED));
                case ReserveOutcome.OutOfStock s -> fail(o, OrderStatus.CANCELLED, FailureReason.OUT_OF_STOCK);
                case ReserveOutcome.Lost l -> fail(o, OrderStatus.CANCELLED, FailureReason.RESERVATION_LOST);
            };
        } catch (DownstreamUnavailable e) {
            // We do not know whether the hold was taken; release (idempotent, tombstones if absent) before cancelling.
            log.warn("order {} reserve outcome unknown: {}", o.id(), e.getMessage());
            countFailure(FailureReason.INVENTORY_UNAVAILABLE);
            return transitions.recordFailure(o.id(), OrderStatus.CREATED, FailureReason.INVENTORY_UNAVAILABLE);
        }
    }

    private boolean authorize(Order o) {
        try {
            return switch (payment.authorize(o.id(), o.paymentToken(), o.totalCents())) {
                case AuthorizeOutcome.Authorized a ->
                        moved(transitions.apply(o.id(), OrderStatus.PAYMENT_AUTHORIZED, null, a.paymentId()));
                case AuthorizeOutcome.Declined d -> fail(o, OrderStatus.PAYMENT_FAILED, FailureReason.PAYMENT_DECLINED);
            };
        } catch (DownstreamUnavailable e) {
            // The charge may exist (timeout after commit). Compensation's cancel-order voids it if so.
            log.warn("order {} authorize outcome unknown: {}", o.id(), e.getMessage());
            return fail(o, OrderStatus.PAYMENT_FAILED, FailureReason.PAYMENT_UNAVAILABLE);
        }
    }

    private boolean confirm(Order o) {
        try {
            return switch (inventory.confirm(o.id())) {
                case ConfirmOutcome.Confirmed c -> moved(transitions.apply(o.id(), OrderStatus.CONFIRMED));
                case ConfirmOutcome.Lost l -> fail(o, OrderStatus.PAYMENT_FAILED, FailureReason.RESERVATION_LOST);
            };
        } catch (DownstreamUnavailable e) {
            log.warn("order {} confirm deferred: {}", o.id(), e.getMessage());
            return false;   // retried by recovery; the hold is still HELD and the authorization still live
        }
    }

    /**
     * Undo everything that might have happened. Both calls are idempotent and safe when nothing happened.
     * If either downstream is unavailable the order stays where it is and recovery retries later.
     */
    private boolean compensate(Order o, boolean paymentMayExist) {
        try {
            if (paymentMayExist) {
                payment.cancelOrder(o.id());
            }
            inventory.release(o.id());
        } catch (DownstreamUnavailable e) {
            log.warn("order {} compensation deferred: {}", o.id(), e.getMessage());
            return false;
        }
        return moved(transitions.apply(o.id(), OrderStatus.CANCELLED));
    }

    private boolean fail(Order o, OrderStatus target, FailureReason reason) {
        countFailure(reason);
        return moved(transitions.apply(o.id(), target, reason, null));
    }

    private void countFailure(FailureReason reason) {
        Counter.builder("order.saga.failures").tag("reason", reason.name()).register(meters).increment();
    }

    private static boolean moved(java.util.Optional<Order> result) {
        return result.isPresent();
    }
}
