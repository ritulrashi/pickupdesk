package pickupdesk.order.clients;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import pickupdesk.order.OrderProperties;

/** payment-service gateway API. */
@Component
public class PaymentClient {

    public sealed interface AuthorizeOutcome {
        record Authorized(UUID paymentId) implements AuthorizeOutcome { }
        record Declined(String reason) implements AuthorizeOutcome { }
    }

    public sealed interface OpOutcome {
        record Done() implements OpOutcome { }
        record Illegal(String currentStatus) implements OpOutcome { }
    }

    private static final String NAME = "payment";

    private final Downstream downstream;
    private final RestClient http;

    public PaymentClient(Downstream downstream, OrderProperties props) {
        this.downstream = downstream;
        this.http = downstream.client(props.paymentUrl());
    }

    /** The key is derived from the order id, so every retry and every recovery attempt reuses it. */
    public static String authorizeKey(UUID orderId) {
        return "order-" + orderId + "-authorize";
    }

    public AuthorizeOutcome authorize(UUID orderId, String token, long amountCents) {
        var body = Map.of("orderId", orderId, "token", token, "amountCents", amountCents);
        var r = downstream.call(NAME, () -> downstream.exchange(http.post()
                .uri("/internal/payments/authorize")
                .header("Idempotency-Key", authorizeKey(orderId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)));
        if (r.is(201)) {
            return new AuthorizeOutcome.Authorized(UUID.fromString(r.body().path("id").asText()));
        }
        if (r.is(402)) {
            return new AuthorizeOutcome.Declined(r.body().path("declineReason").asText("declined"));
        }
        // 409 (another live authorization) or 422 cannot happen with a deterministic key; treat as unknown.
        throw new DownstreamUnavailable(NAME, "unexpected authorize response " + r.status());
    }

    public OpOutcome capture(UUID paymentId) {
        return op("/internal/payments/capture", paymentId);
    }

    public OpOutcome refund(UUID paymentId) {
        return op("/internal/payments/refund", paymentId);
    }

    /** Compensation: void any live authorization for the order and fence out late authorizes. */
    public void cancelOrder(UUID orderId) {
        var r = downstream.call(NAME, () -> downstream.exchange(http.post()
                .uri("/internal/payments/cancel-order")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId))));
        if (!r.is(200)) {
            throw new DownstreamUnavailable(NAME, "unexpected cancel-order response " + r.status());
        }
    }

    private OpOutcome op(String path, UUID paymentId) {
        var r = downstream.call(NAME, () -> downstream.exchange(http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentId", paymentId))));
        if (r.is(200)) {
            return new OpOutcome.Done();
        }
        if (r.is(409)) {
            return new OpOutcome.Illegal(r.body().path("currentStatus").asText());
        }
        throw new DownstreamUnavailable(NAME, "unexpected response " + r.status() + " from " + path);
    }
}
