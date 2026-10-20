package pickupdesk.inventory.reservation;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pickupdesk.inventory.InventoryProperties;
import pickupdesk.inventory.reservation.ReservationExceptions.IdempotencyConflict;
import pickupdesk.inventory.reservation.ReservationExceptions.IllegalTransition;
import pickupdesk.inventory.reservation.ReservationExceptions.InsufficientStock;
import pickupdesk.inventory.reservation.ReservationExceptions.InvalidRequest;
import pickupdesk.inventory.reservation.ReservationExceptions.NotFound;
import pickupdesk.inventory.reservation.strategy.ReserveStrategy;
import pickupdesk.inventory.reservation.strategy.StockReserver;
import pickupdesk.inventory.stock.StockRepository;

@Service
public class ReservationService {

    /** Result of a reserve call: the reservation, and whether this call created it (201) or found it (200). */
    public record ReserveResult(Reservation reservation, boolean created) {
    }

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final Map<ReserveStrategy, StockReserver> reservers = new EnumMap<>(ReserveStrategy.class);
    private final ReserveStrategy defaultStrategy;
    private final int defaultTtlSeconds;

    public ReservationService(ReservationRepository reservations, StockRepository stock,
                              List<StockReserver> reservers, InventoryProperties props) {
        this.reservations = reservations;
        this.stock = stock;
        reservers.forEach(r -> this.reservers.put(r.strategy(), r));
        this.defaultStrategy = Objects.requireNonNull(props.reserveStrategy(), "inventory.reserve-strategy");
        this.defaultTtlSeconds = props.defaultTtlSeconds();
    }

    @Transactional
    public ReserveResult reserve(ReserveRequest req) {
        return reserve(req, defaultStrategy);
    }

    /**
     * All-or-nothing hold for every line. The reservation row is inserted first so that a retry with the same
     * orderId (concurrent or later) finds it instead of reserving twice. If any line cannot be held, the
     * exception rolls back the reservation row and every line already held.
     */
    @Transactional
    public ReserveResult reserve(ReserveRequest req, ReserveStrategy strategy) {
        rejectDuplicateSkus(req);
        UUID id = UUID.randomUUID();
        int ttl = req.ttlSeconds() != null ? req.ttlSeconds() : defaultTtlSeconds;

        if (!reservations.insertHeld(id, req.orderId(), req.storeId(), ttl)) {
            Reservation existing = reservations.findByOrderId(req.orderId()).orElseThrow();
            if (existing.storeId() != null && !sameContent(existing, req)) {
                throw new IdempotencyConflict(req.orderId());
            }
            return new ReserveResult(existing, false);
        }

        StockReserver reserver = reservers.get(strategy);
        // Sorted by sku: concurrent multi-line reservations lock stock rows in the same order, so no deadlock.
        List<ReserveRequest.Line> lines = req.lines().stream()
                .sorted(Comparator.comparing(ReserveRequest.Line::sku))
                .toList();
        for (ReserveRequest.Line line : lines) {
            if (!reserver.tryReserve(req.storeId(), line.sku(), line.qty())) {
                boolean known = stock.find(req.storeId(), line.sku()).isPresent();
                throw new InsufficientStock(req.storeId(), line.sku(), known);
            }
            reservations.insertLine(id, line.sku(), line.qty());
        }
        return new ReserveResult(reservations.findByOrderId(req.orderId()).orElseThrow(), true);
    }

    /**
     * Compensation for a failed or cancelled order. Idempotent: releasing twice, releasing an expired hold, or
     * releasing an order that never reserved all succeed without touching stock. If no reservation exists yet, a RELEASED tombstone is
     * written so a reserve request that was delayed in flight cannot take stock afterwards (ADR-008).
     */
    @Transactional
    public Reservation release(UUID orderId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var moved = reservations.transition(orderId, ReservationStatus.RELEASED);
            if (moved.isPresent()) {
                applyStockDelta(moved.get(), ReservationStatus.RELEASED);
                return moved.get();
            }
            var current = reservations.findByOrderId(orderId);
            if (current.isPresent()) {
                // EXPIRED already gave the stock back; for a caller compensating, that is the same outcome.
                if (current.get().status() == ReservationStatus.EXPIRED) {
                    return current.get();
                }
                return idempotentOrIllegal(current.get(), ReservationStatus.RELEASED);
            }
            if (reservations.insertTombstone(UUID.randomUUID(), orderId)) {
                return reservations.findByOrderId(orderId).orElseThrow();
            }
            // Lost the race to a concurrent reserve that just committed; its row is visible now, retry once.
        }
        throw new IllegalStateException("release did not converge for order " + orderId);
    }

    /** Payment authorized: keep the hold until pickup and stop the 15-minute expiry clock. */
    @Transactional
    public Reservation confirm(UUID orderId) {
        return move(orderId, ReservationStatus.CONFIRMED);
    }

    /** Handed to the customer: the units leave on_hand. */
    @Transactional
    public Reservation consume(UUID orderId) {
        return move(orderId, ReservationStatus.CONSUMED);
    }

    /** Customer returned the items: the units go back on_hand. */
    @Transactional
    public Reservation markReturned(UUID orderId) {
        return move(orderId, ReservationStatus.RETURNED);
    }

    public Reservation get(UUID orderId) {
        return reservations.findByOrderId(orderId).orElseThrow(() -> new NotFound(orderId));
    }

    private Reservation move(UUID orderId, ReservationStatus target) {
        var moved = reservations.transition(orderId, target);
        if (moved.isPresent()) {
            applyStockDelta(moved.get(), target);
            return moved.get();
        }
        Reservation current = reservations.findByOrderId(orderId).orElseThrow(() -> new NotFound(orderId));
        return idempotentOrIllegal(current, target);
    }

    private static Reservation idempotentOrIllegal(Reservation current, ReservationStatus target) {
        if (current.status() == target) {
            return current;
        }
        throw new IllegalTransition(current.orderId(), current.status(), target);
    }

    /** Lines come back from the repository sorted by sku, preserving the lock order. */
    private void applyStockDelta(Reservation r, ReservationStatus entered) {
        for (Reservation.Line line : r.lines()) {
            stock.adjust(r.storeId(), line.sku(),
                    entered.onHandDeltaPerUnit() * line.qty(),
                    entered.reservedDeltaPerUnit() * line.qty());
        }
    }

    private static void rejectDuplicateSkus(ReserveRequest req) {
        var seen = new HashSet<String>();
        for (ReserveRequest.Line line : req.lines()) {
            if (!seen.add(line.sku())) {
                throw new InvalidRequest("duplicate sku in lines: " + line.sku());
            }
        }
    }

    private static boolean sameContent(Reservation existing, ReserveRequest req) {
        if (!existing.storeId().equals(req.storeId())) {
            return false;
        }
        var requested = req.lines().stream()
                .map(l -> new Reservation.Line(l.sku(), l.qty()))
                .sorted(Comparator.comparing(Reservation.Line::sku))
                .toList();
        return requested.equals(existing.lines());
    }
}
