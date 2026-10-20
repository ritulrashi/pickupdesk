package pickupdesk.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pickupdesk.order.domain.OrderStatus.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    /** The spec's state machine plus the two edges from ADR-013. */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            CREATED, EnumSet.of(RESERVED, CANCELLED),
            RESERVED, EnumSet.of(PAYMENT_AUTHORIZED, PAYMENT_FAILED, CANCELLED),
            PAYMENT_AUTHORIZED, EnumSet.of(CONFIRMED, PAYMENT_FAILED),
            CONFIRMED, EnumSet.of(READY_FOR_PICKUP, EXPIRED),
            READY_FOR_PICKUP, EnumSet.of(PICKED_UP, EXPIRED),
            PICKED_UP, EnumSet.of(RETURNED),
            PAYMENT_FAILED, EnumSet.of(CANCELLED),
            EXPIRED, EnumSet.of(CANCELLED),
            RETURNED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    @Test
    void everyPairIsLegalExactlyWhenListed() {
        for (OrderStatus from : values()) {
            for (OrderStatus to : values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void nothingReturnsToCreatedAndTerminalStatesAreFinal() {
        assertThat(CREATED.legalSources()).isEmpty();
        assertThat(RETURNED.allowedNext()).isEmpty();
        assertThat(CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void unfinishedStatesAreExactlyThoseWithPendingSagaWork() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isUnfinished))
                .containsExactlyInAnyOrder(CREATED, RESERVED, PAYMENT_AUTHORIZED, PAYMENT_FAILED, EXPIRED);
    }

    @Test
    void eventTypesAreCamelCased() {
        assertThat(READY_FOR_PICKUP.eventType()).isEqualTo("OrderReadyForPickup");
        assertThat(PICKED_UP.eventType()).isEqualTo("OrderPickedUp");
    }
}
