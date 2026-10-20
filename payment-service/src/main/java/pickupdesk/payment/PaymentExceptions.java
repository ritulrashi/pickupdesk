package pickupdesk.payment;

import java.util.UUID;

public final class PaymentExceptions {

    private PaymentExceptions() {
    }

    /** 422: idempotency key reused with a different request body. */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict() {
            super("idempotency key already used with a different request");
        }
    }

    /** 409: a second live authorization for the same order under a different key. */
    public static class OrderAlreadyAuthorized extends RuntimeException {
        public OrderAlreadyAuthorized(UUID orderId) {
            super("order " + orderId + " already has a live authorization");
        }
    }

    /** 409 */
    public static class IllegalTransition extends RuntimeException {
        public final PaymentStatus current;

        public IllegalTransition(UUID paymentId, PaymentStatus current, PaymentStatus target) {
            super("payment " + paymentId + " is " + current + ", cannot move to " + target);
            this.current = current;
        }
    }

    /** 404 */
    public static class NotFound extends RuntimeException {
        public NotFound(UUID paymentId) {
            super("no payment " + paymentId);
        }
    }

    /** 503: transient gateway failure; nothing changed, safe to retry. */
    public static class GatewayUnavailable extends RuntimeException {
        public GatewayUnavailable(String message) {
            super(message);
        }
    }
}
