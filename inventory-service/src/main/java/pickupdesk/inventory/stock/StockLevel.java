package pickupdesk.inventory.stock;

public record StockLevel(String storeId, String sku, int onHand, int reserved, long version) {

    public int available() {
        return onHand - reserved;
    }
}
