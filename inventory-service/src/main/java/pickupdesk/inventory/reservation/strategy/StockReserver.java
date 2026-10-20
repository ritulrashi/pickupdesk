package pickupdesk.inventory.reservation.strategy;

/**
 * Takes a hold of {@code qty} units on one stock row. Must be called inside the caller's transaction;
 * if any line of a multi-line reservation fails, the caller rolls back the whole transaction.
 */
public interface StockReserver {

    ReserveStrategy strategy();

    /** @return true if the hold was taken; false if the row is missing or has fewer than qty available. */
    boolean tryReserve(String storeId, String sku, int qty);
}
