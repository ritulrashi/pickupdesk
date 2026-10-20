package pickupdesk.inventory.stock;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import pickupdesk.inventory.reservation.Patterns;

@RestController
public class StockController {

    public record Availability(String storeId, String sku, int available) {
    }

    public record SetStockRequest(
            @NotNull @Pattern(regexp = Patterns.STORE_ID) String storeId,
            @NotNull @Pattern(regexp = Patterns.SKU) String sku,
            @Min(0) @Max(1_000_000) int onHand) {
    }

    private final StockRepository stock;

    public StockController(StockRepository stock) {
        this.stock = stock;
    }

    /** Customer-facing availability. Reads Postgres directly for now; Redis caching arrives in Phase 5. */
    @GetMapping("/api/inventory/{storeId}/{sku}")
    public Availability availability(@PathVariable String storeId, @PathVariable String sku) {
        StockLevel s = find(storeId, sku);
        return new Availability(s.storeId(), s.sku(), s.available());
    }

    /** Seed, restock, or cycle-count correction. ADMIN only once security lands in Phase 5. */
    @PostMapping("/admin/stock")
    public StockLevel set(@Valid @RequestBody SetStockRequest req) {
        return stock.upsertOnHand(req.storeId(), req.sku(), req.onHand());
    }

    @GetMapping("/admin/stock/{storeId}/{sku}")
    public StockLevel get(@PathVariable String storeId, @PathVariable String sku) {
        return find(storeId, sku);
    }

    private StockLevel find(String storeId, String sku) {
        return stock.find(storeId, sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no stock row for " + storeId + "/" + sku));
    }
}
