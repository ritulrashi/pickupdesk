package pickupdesk.order.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import pickupdesk.order.domain.Order;

/** What the API returns. Never includes the payment token or the pickup code hash. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderView(
        UUID id,
        String status,
        String storeId,
        String customerRef,
        long totalCents,
        List<Order.Item> items,
        String failureReason,
        /** Present only in the response that created the order (or reissued the code). Shown once. */
        String pickupCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static OrderView of(Order o, String pickupCode) {
        return new OrderView(o.id(), o.status().name(), o.storeId(), o.customerRef(), o.totalCents(), o.items(),
                o.failureReason(), pickupCode, o.createdAt(), o.updatedAt());
    }

    public OrderView withoutPickupCode() {
        return new OrderView(id, status, storeId, customerRef, totalCents, items, failureReason, null, createdAt,
                updatedAt);
    }
}
