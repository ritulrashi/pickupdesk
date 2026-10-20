package pickupdesk.order.clients;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import pickupdesk.order.OrderProperties;
import pickupdesk.order.domain.Order;

/** inventory-service reservation API. Every call here is idempotent by orderId, so retries are safe. */
@Component
public class InventoryClient {

    public sealed interface ReserveOutcome {
        record Held() implements ReserveOutcome { }
        record OutOfStock(String sku) implements ReserveOutcome { }
        /** A reservation exists for the order but is no longer holding stock (released tombstone, or expired). */
        record Lost(String status) implements ReserveOutcome { }
    }

    public sealed interface ConfirmOutcome {
        record Confirmed() implements ConfirmOutcome { }
        record Lost(String status) implements ConfirmOutcome { }
    }

    private static final String NAME = "inventory";

    private final Downstream downstream;
    private final RestClient http;

    public InventoryClient(Downstream downstream, OrderProperties props) {
        this.downstream = downstream;
        this.http = downstream.client(props.inventoryUrl());
    }

    public ReserveOutcome reserve(Order order) {
        var body = Map.of(
                "orderId", order.id(),
                "storeId", order.storeId(),
                "lines", order.items().stream().map(i -> Map.of("sku", i.sku(), "qty", i.qty())).toList());
        var r = downstream.call(NAME, () -> downstream.exchange(
                http.post().uri("/internal/reservations").contentType(MediaType.APPLICATION_JSON).body(body)));
        if (r.is(201) || r.is(200)) {
            String status = r.body().path("status").asText();
            return status.equals("HELD") || status.equals("CONFIRMED")
                    ? new ReserveOutcome.Held() : new ReserveOutcome.Lost(status);
        }
        if (r.is(409)) {
            return new ReserveOutcome.OutOfStock(r.body() == null ? null : r.body().path("sku").asText(null));
        }
        throw new DownstreamUnavailable(NAME, "unexpected reserve response " + r.status());
    }

    public ConfirmOutcome confirm(UUID orderId) {
        var r = downstream.call(NAME, () -> downstream.exchange(
                http.post().uri("/internal/reservations/{id}/confirm", orderId)));
        if (r.is(200)) {
            return new ConfirmOutcome.Confirmed();
        }
        if (r.is(409) || r.is(404)) {
            return new ConfirmOutcome.Lost(r.body() == null ? "MISSING" : r.body().path("currentStatus").asText("MISSING"));
        }
        throw new DownstreamUnavailable(NAME, "unexpected confirm response " + r.status());
    }

    /** Compensation. Succeeds for every reservation state, including "never reserved" (tombstone). */
    public void release(UUID orderId) {
        var r = downstream.call(NAME, () -> downstream.exchange(
                http.delete().uri("/internal/reservations/{id}", orderId)));
        if (!r.is(200)) {
            throw new DownstreamUnavailable(NAME, "unexpected release response " + r.status());
        }
    }
}
