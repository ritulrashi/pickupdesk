package pickupdesk.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static pickupdesk.inventory.reservation.ReservationStatus.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    private static final Map<ReservationStatus, Set<ReservationStatus>> EXPECTED = Map.of(
            HELD, EnumSet.of(CONFIRMED, RELEASED, EXPIRED),
            CONFIRMED, EnumSet.of(CONSUMED, RELEASED),
            CONSUMED, EnumSet.of(RETURNED),
            RETURNED, EnumSet.noneOf(ReservationStatus.class),
            RELEASED, EnumSet.noneOf(ReservationStatus.class),
            EXPIRED, EnumSet.noneOf(ReservationStatus.class));

    @Test
    void everyPairIsLegalExactlyWhenListed() {
        for (ReservationStatus from : values()) {
            for (ReservationStatus to : values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void legalSourcesIsTheInverseOfAllowedNext() {
        for (ReservationStatus to : values()) {
            for (ReservationStatus from : values()) {
                assertThat(to.legalSources().contains(from)).isEqualTo(from.canTransitionTo(to));
            }
        }
    }

    @Test
    void noTransitionIntoHeld() {
        assertThat(HELD.legalSources()).isEmpty();
    }

    /** Walking any legal path from HELD must leave reserved and on_hand unchanged once it ends. */
    @Test
    void stockDeltasBalanceOnEveryPathToATerminalState() {
        assertBalanced(new int[] {0, 0}, HELD);
    }

    private void assertBalanced(int[] onHandAndReserved, ReservationStatus s) {
        int onHand = onHandAndReserved[0] + s.onHandDeltaPerUnit();
        int reserved = onHandAndReserved[1] + s.reservedDeltaPerUnit();
        assertThat(reserved).as("reserved never negative at %s", s).isGreaterThanOrEqualTo(0);
        if (s.allowedNext().isEmpty()) {
            assertThat(reserved).as("reserved released at terminal %s", s).isZero();
            // Every terminal state (RETURNED, RELEASED, EXPIRED) leaves the unit back on the shelf.
            assertThat(onHand).as("on_hand net change at terminal %s", s).isZero();
            return;
        }
        if (s == CONSUMED) {
            assertThat(onHand).as("consumed unit left the shelf").isEqualTo(-1);
        }
        for (ReservationStatus next : s.allowedNext()) {
            assertBalanced(new int[] {onHand, reserved}, next);
        }
    }
}
