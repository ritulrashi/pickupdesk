package pickupdesk.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static pickupdesk.payment.PaymentStatus.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            AUTHORIZED, EnumSet.of(CAPTURED, VOIDED),
            CAPTURED, EnumSet.of(REFUNDED),
            DECLINED, EnumSet.noneOf(PaymentStatus.class),
            VOIDED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void everyPairIsLegalExactlyWhenListed() {
        for (PaymentStatus from : values()) {
            for (PaymentStatus to : values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void refundOnlyAfterCaptureAndVoidOnlyBeforeIt() {
        assertThat(REFUNDED.legalSources()).containsExactly(CAPTURED);
        assertThat(VOIDED.legalSources()).containsExactly(AUTHORIZED);
        assertThat(CAPTURED.legalSources()).containsExactly(AUTHORIZED);
    }
}
