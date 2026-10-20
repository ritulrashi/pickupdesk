package pickupdesk.inventory.reservation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Reservation lifecycle. This enum is the single source of truth for legal transitions and for
 * what each transition does to the stock row; the SQL in {@link ReservationRepository} is derived from it.
 *
 * <pre>
 * HELD -> CONFIRMED -> CONSUMED -> RETURNED
 * HELD -> RELEASED | EXPIRED
 * CONFIRMED -> RELEASED
 * </pre>
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    CONSUMED,
    RETURNED,
    RELEASED,
    EXPIRED;

    public Set<ReservationStatus> allowedNext() {
        return switch (this) {
            case HELD -> EnumSet.of(CONFIRMED, RELEASED, EXPIRED);
            case CONFIRMED -> EnumSet.of(CONSUMED, RELEASED);
            case CONSUMED -> EnumSet.of(RETURNED);
            case RETURNED, RELEASED, EXPIRED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return allowedNext().contains(target);
    }

    /** Statuses from which a transition into {@code this} is legal. */
    public Set<ReservationStatus> legalSources() {
        Set<ReservationStatus> sources = EnumSet.noneOf(ReservationStatus.class);
        for (ReservationStatus s : values()) {
            if (s.canTransitionTo(this)) {
                sources.add(s);
            }
        }
        return sources;
    }

    /** Change to stock.on_hand per reserved unit when a reservation enters this status. */
    public int onHandDeltaPerUnit() {
        return switch (this) {
            case CONSUMED -> -1;   // handed to the customer: leaves the shelf
            case RETURNED -> +1;   // back on the shelf
            default -> 0;
        };
    }

    /** Change to stock.reserved per reserved unit when a reservation enters this status. */
    public int reservedDeltaPerUnit() {
        return switch (this) {
            case HELD -> +1;
            case CONSUMED, RELEASED, EXPIRED -> -1;
            default -> 0;          // CONFIRMED keeps the hold; RETURNED was already unreserved at CONSUMED
        };
    }
}
