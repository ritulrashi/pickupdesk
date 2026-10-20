package pickupdesk.inventory.reservation.strategy;

/** How a single (store, sku, qty) hold is taken against the stock row. See ADR-007. */
public enum ReserveStrategy {
    /** One {@code UPDATE ... WHERE on_hand - reserved >= qty}. Production choice. */
    CONDITIONAL,
    /** {@code SELECT ... FOR UPDATE}, check in Java, then {@code UPDATE}. */
    FOR_UPDATE,
    /** Read without lock, {@code UPDATE ... WHERE version = ?}, retry on conflict. */
    OPTIMISTIC
}
