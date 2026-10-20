package pickupdesk.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import pickupdesk.order.clients.Downstream;
import pickupdesk.order.saga.OrderJobs;

/**
 * Order-service ITs: real Postgres and Kafka (Testcontainers), and one WireMock server standing in for both
 * inventory-service and payment-service, so each test scripts exactly the downstream behavior it needs
 * (success, 4xx answers, 5xx, slow responses past the read timeout, dropped connections).
 * Scheduled jobs are off; tests run recovery, expiry and the relay explicitly.
 */
@Import(OrderITBase.Containers.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.jobs.enabled=false",
        "order.http.read-timeout-ms=800",
        "order.http.retry-initial-backoff-ms=20",
        "order.pickup-code-iterations=1000"})
public abstract class OrderITBase {

    protected static final WireMockServer downstream = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        downstream.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:3.9.1");
        }
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry r) {
        r.add("order.inventory-url", downstream::baseUrl);
        r.add("order.payment-url", downstream::baseUrl);
    }

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected OrderJobs jobs;
    @Autowired
    protected Downstream downstreamCalls;

    @BeforeEach
    void resetDownstream() {
        downstream.resetAll();
        downstreamCalls.breakers().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // ------------------------------------------------------------------ API helpers

    protected static Map<String, Object> orderBody(String token) {
        return Map.of("customerRef", "cust-42", "storeId", "STORE-0042",
                "items", List.of(Map.of("sku", "PHONE-128-BLK", "qty", 1), Map.of("sku", "CASE-CLR", "qty", 2)),
                "paymentToken", token);
    }

    protected ResponseEntity<JsonNode> create(String key, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        return http.postForEntity("/api/orders", new HttpEntity<>(body, h), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> create() {
        return create("k-" + UUID.randomUUID(), orderBody("tok_visa_00004242"));
    }

    protected ResponseEntity<JsonNode> action(UUID id, String action, Object body) {
        return http.postForEntity("/api/orders/" + id + "/" + action, body, JsonNode.class);
    }

    protected static UUID id(ResponseEntity<JsonNode> res) {
        return UUID.fromString(res.getBody().get("id").asText());
    }

    protected String status(UUID id) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", id).query(String.class).single();
    }

    protected List<String> auditTrail(UUID id) {
        return jdbc.sql("SELECT to_status FROM order_events WHERE order_id = :id AND type <> 'SagaFailureRecorded' ORDER BY id")
                .param("id", id).query(String.class).list();
    }

    protected List<String> outboxTypes(UUID id) {
        return jdbc.sql("SELECT type FROM outbox WHERE aggregate_id = :id ORDER BY created_at, id")
                .param("id", id).query(String.class).list();
    }

    /** Pretend nobody has touched the order for a minute, so recovery considers it stale. */
    protected void ageOrder(UUID id, int seconds) {
        jdbc.sql("UPDATE orders SET updated_at = now() - make_interval(secs => :s), created_at = created_at - make_interval(secs => :s), saga_lease_until = NULL WHERE id = :id")
                .param("s", seconds).param("id", id).update();
    }

    // ------------------------------------------------------------------ downstream stubs

    protected static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    protected void inventoryHappy() {
        downstream.stubFor(post(urlPathMatching("/internal/reservations"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"HELD\"}")));
        downstream.stubFor(post(urlPathMatching("/internal/reservations/.*/confirm"))
                .willReturn(okJson("{\"status\":\"CONFIRMED\"}")));
        downstream.stubFor(delete(urlPathMatching("/internal/reservations/.*"))
                .willReturn(okJson("{\"status\":\"RELEASED\"}")));
    }

    protected void paymentHappy() {
        downstream.stubFor(post(urlPathMatching("/internal/payments/authorize"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"AUTHORIZED\"}")));
        downstream.stubFor(post(urlPathMatching("/internal/payments/cancel-order"))
                .willReturn(okJson("{\"voidedPaymentIds\":[]}")));
        downstream.stubFor(post(urlPathMatching("/internal/payments/capture"))
                .willReturn(okJson("{\"status\":\"CAPTURED\"}")));
        downstream.stubFor(post(urlPathMatching("/internal/payments/refund"))
                .willReturn(okJson("{\"status\":\"REFUNDED\"}")));
    }

    protected void allHappy() {
        inventoryHappy();
        paymentHappy();
    }

    protected void stub(String method, String pathRegex, int status, String json) {
        var mapping = "DELETE".equals(method) ? delete(urlPathMatching(pathRegex)) : post(urlPathMatching(pathRegex));
        downstream.stubFor(mapping.willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json").withBody(json == null ? "" : json)));
    }

    protected void stubSlow(String pathRegex, int delayMs) {
        downstream.stubFor(post(urlPathMatching(pathRegex)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"AUTHORIZED\"}")
                .withFixedDelay(delayMs)));
    }

    protected void stubConnectionReset(String method, String pathRegex) {
        var mapping = "DELETE".equals(method) ? delete(urlPathMatching(pathRegex)) : post(urlPathMatching(pathRegex));
        downstream.stubFor(mapping.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    protected int calls(String method, String pathRegex) {
        var pattern = "DELETE".equals(method)
                ? com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlPathMatching(pathRegex))
                : com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathMatching(pathRegex));
        return downstream.findAll(pattern).size();
    }
}
