package pickupdesk.order.domain;

/** Why an order left the happy path. Stored on the order and used as the saga-failure metric tag. */
public enum FailureReason {
    OUT_OF_STOCK,
    INVENTORY_UNAVAILABLE,
    PAYMENT_DECLINED,
    PAYMENT_UNAVAILABLE,
    RESERVATION_LOST,
    CUSTOMER_CANCELLED,
    SAGA_TIMEOUT,
    PICKUP_WINDOW_EXPIRED
}
