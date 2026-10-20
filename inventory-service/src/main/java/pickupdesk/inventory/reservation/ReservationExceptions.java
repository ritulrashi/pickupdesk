package pickupdesk.inventory.reservation;

import java.util.UUID;

/** Domain failures, each mapped to one HTTP status by {@code ApiExceptionHandler}. */
public final class ReservationExceptions {

    private ReservationExceptions() {
    }

    /** 409: the hold could not be taken. Not retryable without a restock. */
    public static class InsufficientStock extends RuntimeException {
        public final String sku;
        public final boolean skuKnown;

        public InsufficientStock(String storeId, String sku, boolean skuKnown) {
            super((skuKnown ? "insufficient stock for " : "unknown sku ") + sku + " at " + storeId);
            this.sku = sku;
            this.skuKnown = skuKnown;
        }
    }

    /** 422: same orderId reused with a different store or different lines. */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(UUID orderId) {
            super("reservation for order " + orderId + " already exists with different content");
        }
    }

    /** 409: the reservation is in a status from which the requested transition is not legal. */
    public static class IllegalTransition extends RuntimeException {
        public final ReservationStatus current;

        public IllegalTransition(UUID orderId, ReservationStatus current, ReservationStatus target) {
            super("reservation for order " + orderId + " is " + current + ", cannot move to " + target);
            this.current = current;
        }
    }

    /** 404 */
    public static class NotFound extends RuntimeException {
        public NotFound(UUID orderId) {
            super("no reservation for order " + orderId);
        }
    }

    /** 400: well-formed but semantically invalid input. */
    public static class InvalidRequest extends RuntimeException {
        public InvalidRequest(String message) {
            super(message);
        }
    }
}
