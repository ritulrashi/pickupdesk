package pickupdesk.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.order.OrderITBase;

class PickupFlowIT extends OrderITBase {

    @Autowired
    MeterRegistry meters;

    private record Created(UUID id, String code) {
    }

    private Created confirmed() {
        allHappy();
        var res = create();
        return new Created(id(res), res.getBody().get("pickupCode").asText());
    }

    @Test
    void readyThenPickupWithCodeCapturesAndEmitsPickedUp() {
        var o = confirmed();
        assertThat(action(o.id(), "ready", null).getBody().get("status").asText()).isEqualTo("READY_FOR_PICKUP");

        var res = action(o.id(), "pickup", Map.of("pickupCode", o.code().toLowerCase()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PICKED_UP");
        assertThat(calls("POST", "/internal/payments/capture")).isEqualTo(1);
        assertThat(outboxTypes(o.id())).endsWith("OrderReadyForPickup", "OrderPickedUp");
    }

    @Test
    void pickupBeforeReadyIs409() {
        var o = confirmed();
        assertThat(action(o.id(), "pickup", Map.of("pickupCode", o.code())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(calls("POST", "/internal/payments/capture")).isZero();
    }

    @Test
    void wrongCodesAreCountedAndLockAfterFive() {
        var o = confirmed();
        action(o.id(), "ready", null);
        for (int i = 0; i < 5; i++) {
            var wrong = action(o.id(), "pickup", Map.of("pickupCode", "WRONGCODE"));
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var locked = action(o.id(), "pickup", Map.of("pickupCode", o.code()));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(calls("POST", "/internal/payments/capture")).isZero();

        String fresh = http.postForObject("/api/orders/" + o.id() + "/pickup-code", null, JsonNode.class)
                .get("pickupCode").asText();
        assertThat(action(o.id(), "pickup", Map.of("pickupCode", o.code())).getStatusCode())
                .as("old code no longer works").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(action(o.id(), "pickup", Map.of("pickupCode", fresh)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Spec section 3 step 4: capture fails, order stays READY_FOR_PICKUP, capture is retried, alert after N. */
    @Test
    void captureFailureKeepsOrderReadyAndAlertsAfterThreshold() {
        var o = confirmed();
        action(o.id(), "ready", null);
        stub("POST", "/internal/payments/capture", 503, null);
        double alertsBefore = meters.counter("order.capture.alerts").count();

        for (int i = 0; i < 3; i++) {
            assertThat(action(o.id(), "pickup", Map.of("pickupCode", o.code())).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(status(o.id())).isEqualTo("READY_FOR_PICKUP");
        }
        assertThat(meters.counter("order.capture.alerts").count() - alertsBefore).isEqualTo(1);

        paymentHappy();
        downstreamCallsReset();
        assertThat(action(o.id(), "pickup", Map.of("pickupCode", o.code())).getBody().get("status").asText())
                .isEqualTo("PICKED_UP");
    }

    @Test
    void returnRefundsAndEmitsReturned() {
        var o = confirmed();
        action(o.id(), "ready", null);
        action(o.id(), "pickup", Map.of("pickupCode", o.code()));

        var res = action(o.id(), "return", null);

        assertThat(res.getBody().get("status").asText()).isEqualTo("RETURNED");
        assertThat(calls("POST", "/internal/payments/refund")).isEqualTo(1);
        assertThat(outboxTypes(o.id())).endsWith("OrderReturned");
        assertThat(action(o.id(), "return", null).getStatusCode()).as("second return").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pickupQueueListsOnlyOpenOrdersOfThatStore() {
        var o = confirmed();
        var queue = http.getForObject("/api/stores/STORE-0042/pickup-queue", JsonNode.class);
        assertThat(queue.findValuesAsText("id")).contains(o.id().toString());
        assertThat(queue.findValues("pickupCode")).isEmpty();
        var other = http.getForObject("/api/stores/STORE-9999/pickup-queue", JsonNode.class);
        assertThat(other.findValuesAsText("id")).doesNotContain(o.id().toString());
    }

    /** Three failed captures with 3 attempts each would open the payment breaker; reset it like a recovered service. */
    private void downstreamCallsReset() {
        downstreamCalls.breakers().getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }
}
