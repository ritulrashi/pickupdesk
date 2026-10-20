package pickupdesk.order.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Internal representation. Never serialized directly: the API uses {@code OrderView}, which omits secrets. */
public record Order(
        UUID id,
        String customerRef,
        String storeId,
        OrderStatus status,
        long totalCents,
        String paymentToken,
        UUID paymentId,
        String pickupCodeHash,
        int pickupAttempts,
        int captureFailures,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version,
        List<Item> items) {

    public record Item(String sku, int qty, long unitPriceCents) {
    }

    public Order withItems(List<Item> items) {
        return new Order(id, customerRef, storeId, status, totalCents, paymentToken, paymentId, pickupCodeHash,
                pickupAttempts, captureFailures, failureReason, createdAt, updatedAt, version, items);
    }

    @Override
    public String toString() {
        return "Order[id=" + id + ", status=" + status + ", storeId=" + storeId + "]";
    }
}
