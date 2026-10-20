package pickupdesk.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * (authorize) -> AUTHORIZED | DECLINED
 * AUTHORIZED -> CAPTURED | VOIDED
 * CAPTURED   -> REFUNDED
 * </pre>
 * Full-amount operations only: partial capture and partial refund are out of scope.
 */
public enum PaymentStatus {
    AUTHORIZED,
    DECLINED,
    CAPTURED,
    VOIDED,
    REFUNDED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case AUTHORIZED -> EnumSet.of(CAPTURED, VOIDED);
            case CAPTURED -> EnumSet.of(REFUNDED);
            case DECLINED, VOIDED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedNext().contains(target);
    }

    public Set<PaymentStatus> legalSources() {
        Set<PaymentStatus> sources = EnumSet.noneOf(PaymentStatus.class);
        for (PaymentStatus s : values()) {
            if (s.canTransitionTo(this)) {
                sources.add(s);
            }
        }
        return sources;
    }
}
