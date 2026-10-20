package pickupdesk.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Clients send skus and quantities only; prices come from the server-side catalog. */
public record CreateOrderRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9-]{3,64}") String customerRef,
        @NotNull @Pattern(regexp = "STORE-\\d{4}") String storeId,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull Item> items,
        @NotNull @Pattern(regexp = "tok_[A-Za-z0-9_]{8,64}") String paymentToken) {

    public record Item(
            @NotNull @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,39}") String sku,
            @Min(1) @Max(10) int qty) {
    }
}
