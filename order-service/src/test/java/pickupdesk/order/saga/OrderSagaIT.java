package pickupdesk.order.saga;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import pickupdesk.order.OrderITBase;
import pickupdesk.order.clients.PaymentClient;

/** Every failure path from spec section 2 that the order saga owns, with downstreams scripted by WireMock. */
class OrderSagaIT extends OrderITBase {

    @Test
    void happyPathConfirmsAndShowsPickupCodeOnce() {
        allHappy();
        var res = create();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = id(res);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(res.getBody().get("totalCents").asLong()).isEqualTo(79_900 + 2 * 2_900);
        assertThat(res.getBody().get("pickupCode").asText()).hasSize(8);
        assertThat(res.getBody().has("paymentToken")).isFalse();
        assertThat(auditTrail(id)).containsExactly("CREATED", "RESERVED", "PAYMENT_AUTHORIZED", "CONFIRMED");
        assertThat(outboxTypes(id)).containsExactly("OrderCreated", "OrderReserved", "OrderPaymentAuthorized", "OrderConfirmed");
        assertThat(http.getForObject("/api/orders/" + id, com.fasterxml.jackson.databind.JsonNode.class).has("pickupCode"))
                .as("GET never shows the code").isFalse();
        downstream.verify(postRequestedFor(urlPathMatching("/internal/payments/authorize"))
                .withHeader("Idempotency-Key", equalTo(PaymentClient.authorizeKey(id))));
    }

    @Test
    void outOfStockCancelsWithoutTouchingPayment() {
        allHappy();
        stub("POST", "/internal/reservations", 409, "{\"type\":\"urn:pickupdesk:insufficient-stock\",\"sku\":\"PHONE-128-BLK\"}");

        var res = create();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(calls("POST", "/internal/payments/.*")).isZero();
        assertThat(auditTrail(id(res))).containsExactly("CREATED", "CANCELLED");
    }

    @Test
    void declinedPaymentReleasesTheReservation() {
        allHappy();
        stub("POST", "/internal/payments/authorize", 402, "{\"status\":\"DECLINED\",\"declineReason\":\"card_declined\"}");

        var res = create();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        UUID id = id(res);
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(auditTrail(id)).containsExactly("CREATED", "RESERVED", "PAYMENT_FAILED", "CANCELLED");
        assertThat(calls("DELETE", "/internal/reservations/" + id)).as("compensation released the hold").isEqualTo(1);
        assertThat(calls("POST", "/internal/payments/authorize")).as("a 4xx answer is not retried").isEqualTo(1);
    }

    /** Gateway timeout on every attempt: the charge may exist, so compensation voids-and-fences it at payment. */
    @Test
    void paymentTimeoutRetriesWithSameKeyThenCompensatesBothSides() {
        allHappy();
        stubSlow("/internal/payments/authorize", 2_000);

        var res = create();

        UUID id = id(res);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("PAYMENT_UNAVAILABLE");
        assertThat(calls("POST", "/internal/payments/authorize")).as("bounded retries").isEqualTo(3);
        downstream.verify(3, postRequestedFor(urlPathMatching("/internal/payments/authorize"))
                .withHeader("Idempotency-Key", equalTo(PaymentClient.authorizeKey(id))));
        assertThat(calls("POST", "/internal/payments/cancel-order")).isEqualTo(1);
        assertThat(calls("DELETE", "/internal/reservations/" + id)).isEqualTo(1);
    }

    /** First attempt times out (the charge happened), the retry with the same key gets the replay: one charge. */
    @Test
    void paymentTimeoutThenSuccessfulRetryConfirms() {
        allHappy();
        downstream.stubFor(post(urlPathMatching("/internal/payments/authorize")).inScenario("flaky")
                .whenScenarioStateIs(STARTED).willSetStateTo("second")
                .willReturn(aResponse().withStatus(201).withFixedDelay(2_000).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"AUTHORIZED\"}")));
        downstream.stubFor(post(urlPathMatching("/internal/payments/authorize")).inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withHeader("Idempotent-Replayed", "true")
                        .withBody("{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"AUTHORIZED\"}")));

        var res = create();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(calls("POST", "/internal/payments/authorize")).isEqualTo(2);
        assertThat(calls("POST", "/internal/payments/cancel-order")).isZero();
    }

    @Test
    void inventoryUnavailableReleasesDefensivelyAndCancels() {
        allHappy();
        stub("POST", "/internal/reservations", 503, null);

        var res = create();

        UUID id = id(res);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("INVENTORY_UNAVAILABLE");
        assertThat(calls("POST", "/internal/reservations")).isEqualTo(3);
        assertThat(calls("DELETE", "/internal/reservations/" + id)).as("release in case a hold was taken").isEqualTo(1);
        assertThat(calls("POST", "/internal/payments/.*")).isZero();
    }

    @Test
    void reservationLostBeforeConfirmVoidsTheAuthorization() {
        allHappy();
        stub("POST", "/internal/reservations/.*/confirm", 409, "{\"currentStatus\":\"EXPIRED\"}");

        var res = create();

        UUID id = id(res);
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("RESERVATION_LOST");
        assertThat(auditTrail(id)).containsExactly("CREATED", "RESERVED", "PAYMENT_AUTHORIZED", "PAYMENT_FAILED", "CANCELLED");
        assertThat(calls("POST", "/internal/payments/cancel-order")).isEqualTo(1);
    }

    /**
     * Kill-payment-service, at the saga level: payment drops connections during authorize and during
     * compensation. The order parks in PAYMENT_FAILED (202) and recovery completes the compensation once the
     * service is back.
     */
    @Test
    void paymentDownDuringCompensationIsFinishedByRecovery() {
        inventoryHappy();
        stubConnectionReset("POST", "/internal/payments/.*");

        var res = create();

        UUID id = id(res);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(status(id)).isEqualTo("PAYMENT_FAILED");

        paymentHappy();   // payment-service is back
        ageOrder(id, 60);
        jobs.recoverStale();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(calls("POST", "/internal/payments/cancel-order")).isGreaterThanOrEqualTo(1);
        assertThat(calls("DELETE", "/internal/reservations/" + id)).isEqualTo(1);
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndFailsFast() {
        inventoryHappy();
        stub("POST", "/internal/payments/.*", 503, null);
        for (int i = 0; i < 4; i++) {
            create();
        }
        int before = calls("POST", "/internal/payments/authorize");

        var res = create();

        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("PAYMENT_UNAVAILABLE");
        assertThat(calls("POST", "/internal/payments/authorize")).as("open breaker sends nothing").isEqualTo(before);
    }

    /**
     * The orchestrator crashed after reserve, before authorize (spec section 10): the row says RESERVED and the
     * lease has lapsed. Recovery continues forward with the same idempotency key.
     */
    @Test
    void crashAfterReserveIsResumedByRecovery() {
        allHappy();
        UUID id = crashedOrderIn("RESERVED");

        jobs.recoverStale();

        assertThat(status(id)).isEqualTo("CONFIRMED");
        downstream.verify(1, postRequestedFor(urlPathMatching("/internal/payments/authorize"))
                .withHeader("Idempotency-Key", equalTo(PaymentClient.authorizeKey(id))));
        assertThat(calls("POST", "/internal/reservations")).as("reserve not repeated").isZero();
    }

    @Test
    void crashAfterAuthorizeIsResumedWithoutASecondCharge() {
        allHappy();
        UUID id = crashedOrderIn("PAYMENT_AUTHORIZED");

        jobs.recoverStale();

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(calls("POST", "/internal/payments/authorize")).isZero();
    }

    @Test
    void sagaPastDeadlineIsCompensatedInsteadOfResumed() {
        allHappy();
        UUID id = crashedOrderIn("RESERVED");
        ageOrder(id, 3_600);

        jobs.recoverStale();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT failure_reason FROM orders WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("SAGA_TIMEOUT");
        assertThat(calls("POST", "/internal/payments/authorize")).isZero();
        assertThat(calls("POST", "/internal/payments/cancel-order")).isEqualTo(1);
    }

    @Test
    void customerCancelOfReservedOrderCompensates() {
        allHappy();
        UUID id = crashedOrderIn("RESERVED");

        var res = action(id, "cancel", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("failureReason").asText()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(calls("POST", "/internal/payments/cancel-order")).isEqualTo(1);
    }

    @Test
    void customerCannotCancelAConfirmedOrder() {
        allHappy();
        UUID id = id(create());
        assertThat(action(id, "cancel", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void pickupWindowExpiryCompensatesAndCancels() {
        allHappy();
        UUID id = id(create());
        ageOrder(id, 8 * 24 * 3600);

        jobs.expirePastPickupWindow();

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(auditTrail(id)).endsWith("CONFIRMED", "EXPIRED", "CANCELLED");
        assertThat(calls("POST", "/internal/payments/cancel-order")).isEqualTo(1);
        assertThat(calls("DELETE", "/internal/reservations/" + id)).isEqualTo(1);
    }

    /** Creates an order through the API, then rewinds it to {@code status} with an expired lease (a crash). */
    private UUID crashedOrderIn(String status) {
        UUID id = id(create());
        downstream.resetRequests();
        jdbc.sql("UPDATE orders SET status = :s, payment_id = :p, saga_lease_until = now() - interval '1 second' WHERE id = :id")
                .param("s", status)
                .param("p", "PAYMENT_AUTHORIZED".equals(status) ? PAYMENT_ID : null)
                .param("id", id).update();
        ageOrder(id, 30);
        return id;
    }
}
