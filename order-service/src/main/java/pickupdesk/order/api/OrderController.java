package pickupdesk.order.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import pickupdesk.order.OrderService;

/** Public API. Authentication and the store-scoping (IDOR) checks for associate actions arrive in Phase 5. */
@RestController
public class OrderController {

    public record PickupRequest(@NotBlank String pickupCode) {
    }

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<OrderView> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_:.-]{8,128}") String key,
            @Valid @RequestBody CreateOrderRequest req) {
        var result = service.create(key, req);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/api/orders/{id}")
    public OrderView get(@PathVariable UUID id) {
        return OrderView.of(service.get(id), null);
    }

    @PostMapping("/api/orders/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id) {
        return OrderView.of(service.cancel(id), null);
    }

    @PostMapping("/api/orders/{id}/pickup-code")
    public Map<String, String> reissuePickupCode(@PathVariable UUID id) {
        return Map.of("pickupCode", service.reissuePickupCode(id));
    }

    @GetMapping("/api/stores/{storeId}/pickup-queue")
    public List<OrderView> pickupQueue(@PathVariable @Pattern(regexp = "STORE-\\d{4}") String storeId) {
        return service.pickupQueue(storeId).stream().map(o -> OrderView.of(o, null)).toList();
    }

    @PostMapping("/api/orders/{id}/ready")
    public OrderView ready(@PathVariable UUID id) {
        return OrderView.of(service.markReady(id), null);
    }

    @PostMapping("/api/orders/{id}/pickup")
    public OrderView pickup(@PathVariable UUID id, @Valid @RequestBody PickupRequest req) {
        return OrderView.of(service.pickup(id, req.pickupCode()), null);
    }

    @PostMapping("/api/orders/{id}/return")
    public OrderView returnOrder(@PathVariable UUID id) {
        return OrderView.of(service.returnOrder(id), null);
    }
}
