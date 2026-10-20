package pickupdesk.inventory.reservation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** Service-to-service API called by order-service. Authentication is added in Phase 5. */
@RestController
@RequestMapping("/internal/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /** 201 when this call created the hold, 200 when a reservation for the order already existed. */
    @PostMapping
    public ResponseEntity<Reservation> reserve(@Valid @RequestBody ReserveRequest req) {
        var result = service.reserve(req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.reservation());
    }

    @GetMapping("/{orderId}")
    public Reservation get(@PathVariable UUID orderId) {
        return service.get(orderId);
    }

    /** Idempotent compensation. Returns the reservation (or tombstone) in its final RELEASED state. */
    @DeleteMapping("/{orderId}")
    public Reservation release(@PathVariable UUID orderId) {
        return service.release(orderId);
    }

    @PostMapping("/{orderId}/confirm")
    public Reservation confirm(@PathVariable UUID orderId) {
        return service.confirm(orderId);
    }

    @PostMapping("/{orderId}/consume")
    public Reservation consume(@PathVariable UUID orderId) {
        return service.consume(orderId);
    }

    @PostMapping("/{orderId}/return")
    public Reservation markReturned(@PathVariable UUID orderId) {
        return service.markReturned(orderId);
    }
}
