package pickupdesk.inventory.reservation.strategy;

public class ContentionException extends RuntimeException {

    public ContentionException(String storeId, String sku, int attempts) {
        super("gave up on " + storeId + "/" + sku + " after " + attempts + " optimistic attempts");
    }
}
