package pickupdesk.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.JsonNode;

@Import(TestcontainersConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiIT {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FailureInjection faults;
    @LocalServerPort
    int port;

    @AfterEach
    void clearFaults() {
        faults.reset();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static Map<String, Object> body(UUID order, String token, long cents) {
        return Map.of("orderId", order, "token", token, "amountCents", cents);
    }

    private ResponseEntity<JsonNode> authorize(String key, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        return http.postForEntity("/internal/payments/authorize", new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> op(String op, String paymentId) {
        return http.postForEntity("/internal/payments/" + op, Map.of("paymentId", paymentId), JsonNode.class);
    }

    private int paymentsFor(UUID order) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o").param("o", order).query(Integer.class).single();
    }

    private List<String> eventsFor(String paymentId) {
        return jdbc.sql("SELECT type FROM payment_events WHERE payment_id = :p ORDER BY id")
                .param("p", UUID.fromString(paymentId)).query(String.class).list();
    }

    @Test
    void authorizeCreatesOnePaymentAndHidesTheToken() {
        UUID order = UUID.randomUUID();
        var res = authorize(key(), body(order, "tok_visa_00004242", 12_999));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(res.getBody().get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(res.getBody().get("amountCents").asLong()).isEqualTo(12_999);
        assertThat(res.getBody().get("tokenLast4").asText()).isEqualTo("4242");
        assertThat(res.getBody().toString()).doesNotContain("tok_visa");
        assertThat(eventsFor(res.getBody().get("id").asText())).containsExactly("AUTHORIZED");
    }

    @Test
    void sameKeySameBodyReplaysTheOriginalPayment() {
        UUID order = UUID.randomUUID();
        String k = key();
        var first = authorize(k, body(order, "tok_visa_00004242", 500));
        var second = authorize(k, body(order, "tok_visa_00004242", 500));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(paymentsFor(order)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs422() {
        UUID order = UUID.randomUUID();
        String k = key();
        authorize(k, body(order, "tok_visa_00004242", 500));

        var res = authorize(k, body(order, "tok_visa_00004242", 501));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(paymentsFor(order)).isEqualTo(1);
    }

    /** Phase 3 acceptance: concurrent duplicate authorizes yield exactly one payment. */
    @Test
    void concurrentDuplicateAuthorizesYieldOnePayment() throws Exception {
        UUID order = UUID.randomUUID();
        String k = key();
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return authorize(k, body(order, "tok_visa_00004242", 2_500));
            }));
        }
        start.countDown();
        Set<String> ids = new HashSet<>();
        int fresh = 0;
        for (var f : futures) {
            var r = f.get();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ids.add(r.getBody().get("id").asText());
            if ("false".equals(r.getHeaders().getFirst("Idempotent-Replayed"))) fresh++;
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(fresh).isEqualTo(1);
        assertThat(paymentsFor(order)).isEqualTo(1);
        assertThat(eventsFor(ids.iterator().next())).containsExactly("AUTHORIZED");
    }

    @Test
    void secondLiveAuthorizationForOrderUnderNewKeyIs409() {
        UUID order = UUID.randomUUID();
        authorize(key(), body(order, "tok_visa_00004242", 500));

        var res = authorize(key(), body(order, "tok_visa_00004242", 500));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("type").asText()).isEqualTo("urn:pickupdesk:order-already-authorized");
        assertThat(paymentsFor(order)).isEqualTo(1);
    }

    @Test
    void declineIs402AndReplaysAsDeclineAndAllowsANewAttempt() {
        UUID order = UUID.randomUUID();
        String k = key();
        var declined = authorize(k, body(order, "tok_decline_insufficient", 500));
        var replay = authorize(k, body(order, "tok_decline_insufficient", 500));
        var retryNewCard = authorize(key(), body(order, "tok_visa_00004242", 500));

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(declined.getBody().get("declineReason").asText()).isEqualTo("card_declined");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(replay.getBody().get("id")).isEqualTo(declined.getBody().get("id"));
        assertThat(retryNewCard.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(paymentsFor(order)).isEqualTo(2);
    }

    @Test
    void injectedDeclineRateOneDeclinesEverything() {
        faults.set(new FailureInjection.Settings(1.0, 0, 0, 0, 0, 1L));
        var res = authorize(key(), body(UUID.randomUUID(), "tok_visa_00004242", 500));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(res.getBody().get("declineReason").asText()).isEqualTo("injected_decline");
    }

    /**
     * Phase 3 acceptance, documented behavior of an injected timeout: the authorization is committed, then the
     * response is delayed past the client's timeout. The client retries with the same key and receives the
     * same payment; there is still exactly one payment and one AUTHORIZED event.
     */
    @Test
    void injectedTimeoutCommitsThenRetryWithSameKeyGetsSamePayment() {
        var impatient = new RestTemplateBuilder().rootUri("http://localhost:" + port)
                .readTimeout(Duration.ofMillis(500)).build();
        UUID order = UUID.randomUUID();
        String k = key();
        faults.set(new FailureInjection.Settings(0, 1.0, 3_000, 0, 0, 1L));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", k);
        var req = new HttpEntity<>(body(order, "tok_visa_00004242", 7_500), h);
        assertThatThrownBy(() -> impatient.postForEntity("/internal/payments/authorize", req, JsonNode.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(paymentsFor(order)).as("charge committed despite the timeout").isEqualTo(1);

        faults.reset();
        var retry = impatient.postForEntity("/internal/payments/authorize", req, JsonNode.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(paymentsFor(order)).isEqualTo(1);
        assertThat(eventsFor(retry.getBody().get("id").asText())).containsExactly("AUTHORIZED");
    }

    @Test
    void captureVoidRefundLifecycleIsIdempotent() {
        String a = authorize(key(), body(UUID.randomUUID(), "tok_visa_00004242", 500)).getBody().get("id").asText();
        assertThat(op("capture", a).getBody().get("status").asText()).isEqualTo("CAPTURED");
        assertThat(op("capture", a).getStatusCode()).as("repeat capture").isEqualTo(HttpStatus.OK);
        assertThat(op("refund", a).getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(op("refund", a).getStatusCode()).as("repeat refund").isEqualTo(HttpStatus.OK);
        assertThat(eventsFor(a)).containsExactly("AUTHORIZED", "CAPTURED", "REFUNDED");

        String b = authorize(key(), body(UUID.randomUUID(), "tok_visa_00004242", 500)).getBody().get("id").asText();
        assertThat(op("void", b).getBody().get("status").asText()).isEqualTo("VOIDED");
        assertThat(op("void", b).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventsFor(b)).containsExactly("AUTHORIZED", "VOIDED");
    }

    @Test
    void illegalOperationsAre409AndChangeNothing() {
        String a = authorize(key(), body(UUID.randomUUID(), "tok_visa_00004242", 500)).getBody().get("id").asText();
        var refundBeforeCapture = op("refund", a);
        assertThat(refundBeforeCapture.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refundBeforeCapture.getBody().get("currentStatus").asText()).isEqualTo("AUTHORIZED");

        op("capture", a);
        assertThat(op("void", a).getStatusCode()).as("void after capture").isEqualTo(HttpStatus.CONFLICT);

        String declined = authorize(key(), body(UUID.randomUUID(), "tok_decline_00000000", 500)).getBody().get("id").asText();
        assertThat(op("capture", declined).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(eventsFor(a)).containsExactly("AUTHORIZED", "CAPTURED");
    }

    @Test
    void injectedCaptureFailureIs503WithNoStateChangeAndRetrySucceeds() {
        String a = authorize(key(), body(UUID.randomUUID(), "tok_visa_00004242", 500)).getBody().get("id").asText();
        faults.set(new FailureInjection.Settings(0, 0, 0, 0, 1.0, 1L));

        assertThat(op("capture", a).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForObject("/internal/payments/" + a, JsonNode.class).get("status").asText())
                .isEqualTo("AUTHORIZED");

        faults.reset();
        assertThat(op("capture", a).getBody().get("status").asText()).isEqualTo("CAPTURED");
    }

    @Test
    void rawCardNumbersAndMissingKeysAreRejected() {
        UUID order = UUID.randomUUID();
        assertThat(authorize(key(), body(order, "4111111111111111", 500)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(authorize(key(), body(order, "tok_4111 1111 1111 1111", 500)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(authorize(null, body(order, "tok_visa_00004242", 500)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(authorize("short", body(order, "tok_visa_00004242", 500)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(authorize(key(), body(order, "tok_visa_00004242", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paymentsFor(order)).isZero();
        String tokens = jdbc.sql("SELECT coalesce(string_agg(token, ','), '') FROM payments").query(String.class).single();
        assertThat(tokens).doesNotContain("4111111111111111");
    }

    @Test
    void failureInjectionAdminEndpointRoundTrips() {
        var set = http.postForEntity("/admin/failure-injection",
                Map.of("declineRate", 0.2, "timeoutRate", 0.1, "timeoutMs", 2000, "latencyMs", 50,
                        "captureFailureRate", 0.3, "seed", 99), JsonNode.class);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("declineRate").asDouble()).isEqualTo(0.2);
        assertThat(http.postForEntity("/admin/failure-injection", Map.of("declineRate", 1.5), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.delete("/admin/failure-injection");
        assertThat(faults.get()).isEqualTo(FailureInjection.Settings.NONE);
    }
}
