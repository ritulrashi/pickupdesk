package pickupdesk.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order state machine. Every state change in the service goes through {@link OrderTransitions}, which only
 * updates a row whose current status is one of {@link #legalSources()}.
 *
 * <pre>
 * Happy path: CREATED -> RESERVED -> PAYMENT_AUTHORIZED -> CONFIRMED -> READY_FOR_PICKUP -> PICKED_UP -> RETURNED
 * Failures:   CREATED | RESERVED -> CANCELLED
 *             RESERVED | PAYMENT_AUTHORIZED -> PAYMENT_FAILED -> CANCELLED
 *             CONFIRMED | READY_FOR_PICKUP -> EXPIRED -> CANCELLED
 * </pre>
 *
 * Two edges extend the spec's machine (ADR-013): PAYMENT_AUTHORIZED -> PAYMENT_FAILED (the reservation was lost
 * between authorize and confirm, so the authorization must be voided), and READY_FOR_PICKUP -> EXPIRED (orders
 * that were made ready but never collected also expire).
 */
public enum OrderStatus {
    CREATED,
    RESERVED,
    PAYMENT_AUTHORIZED,
    CONFIRMED,
    READY_FOR_PICKUP,
    PICKED_UP,
    RETURNED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(RESERVED, CANCELLED);
            case RESERVED -> EnumSet.of(PAYMENT_AUTHORIZED, PAYMENT_FAILED, CANCELLED);
            case PAYMENT_AUTHORIZED -> EnumSet.of(CONFIRMED, PAYMENT_FAILED);
            case CONFIRMED -> EnumSet.of(READY_FOR_PICKUP, EXPIRED);
            case READY_FOR_PICKUP -> EnumSet.of(PICKED_UP, EXPIRED);
            case PICKED_UP -> EnumSet.of(RETURNED);
            case PAYMENT_FAILED, EXPIRED -> EnumSet.of(CANCELLED);
            case RETURNED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedNext().contains(target);
    }

    public Set<OrderStatus> legalSources() {
        Set<OrderStatus> sources = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus s : values()) {
            if (s.canTransitionTo(this)) {
                sources.add(s);
            }
        }
        return sources;
    }

    /** States the saga or a compensation still has work to do in; recovery picks these up. */
    public boolean isUnfinished() {
        return this == CREATED || this == RESERVED || this == PAYMENT_AUTHORIZED
                || this == PAYMENT_FAILED || this == EXPIRED;
    }

    /** Event type written to the outbox and audit trail when an order enters this status. */
    public String eventType() {
        StringBuilder sb = new StringBuilder("Order");
        for (String part : name().split("_")) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
