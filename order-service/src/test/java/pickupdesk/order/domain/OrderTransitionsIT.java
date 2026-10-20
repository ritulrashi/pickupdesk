package pickupdesk.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import pickupdesk.order.OrderITBase;

/** Spec section 3: reject every illegal transition. All 100 (from, to) pairs against the real database. */
class OrderTransitionsIT extends OrderITBase {

    @Autowired
    OrderTransitions transitions;

    private int events(UUID id) {
        return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id = :id").param("id", id).query(Integer.class).single();
    }

    @Test
    void everyPairIsAppliedExactlyWhenLegal() {
        allHappy();
        UUID id = id(create());
        int legal = 0, illegal = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                jdbc.sql("UPDATE orders SET status = :s WHERE id = :id").param("s", from.name()).param("id", id).update();
                int eventsBefore = events(id);

                var result = transitions.apply(id, to);

                if (from.canTransitionTo(to)) {
                    assertThat(result).as("%s -> %s", from, to).isPresent();
                    assertThat(status(id)).isEqualTo(to.name());
                    assertThat(events(id)).isEqualTo(eventsBefore + 1);
                    legal++;
                } else {
                    assertThat(result).as("%s -> %s must be rejected", from, to).isEmpty();
                    assertThat(status(id)).isEqualTo(from.name());
                    assertThat(events(id)).as("no audit row for a rejected change").isEqualTo(eventsBefore);
                    illegal++;
                }
            }
        }
        assertThat(legal + illegal).isEqualTo(100);
        assertThat(legal).isEqualTo(14);
    }

    @Test
    void auditTrailIsAppendOnly() {
        allHappy();
        UUID id = id(create());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE order_events SET type = 'x' WHERE order_id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM order_events WHERE order_id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
    }
}
