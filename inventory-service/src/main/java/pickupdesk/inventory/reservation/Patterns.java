package pickupdesk.inventory.reservation;

/** Input formats shared by request validation. */
public final class Patterns {

    public static final String STORE_ID = "STORE-\\d{4}";
    public static final String SKU = "[A-Z0-9][A-Z0-9-]{0,39}";

    private Patterns() {
    }
}
