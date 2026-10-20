package pickupdesk.inventory.reservation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReserveRequest(
        @NotNull UUID orderId,
        @NotNull @Pattern(regexp = Patterns.STORE_ID) String storeId,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull Line> lines,
        @Min(1) @Max(3600) Integer ttlSeconds) {

    public record Line(
            @NotNull @Pattern(regexp = Patterns.SKU) String sku,
            @Min(1) @Max(100) int qty) {
    }
}
