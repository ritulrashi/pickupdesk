package pickupdesk.inventory.reservation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID orderId,
        String storeId,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        List<Line> lines) {

    public record Line(String sku, int qty) {
    }
}
