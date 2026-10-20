package pickupdesk.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import pickupdesk.order.OrderITBase;

class OrderIdempotencyIT extends OrderITBase {

    private int ordersFor(String key) {
        return jdbc.sql("SELECT count(*) FROM orders o JOIN idempotency_keys k ON k.order_id = o.id WHERE k.key = :k")
                .param("k", key).query(Integer.class).single();
    }

    @Test
    void sameKeySameBodyReturnsTheOriginalResponseWithoutThePickupCode() {
        allHappy();
        String key = "k-" + UUID.randomUUID();
        var first = create(key, orderBody("tok_visa_00004242"));
        var second = create(key, orderBody("tok_visa_00004242"));

        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(first.getBody().has("pickupCode")).isTrue();
        assertThat(second.getBody().has("pickupCode")).as("shown once").isFalse();
        assertThat(calls("POST", "/internal/reservations")).isEqualTo(1);
        assertThat(ordersFor(key)).isEqualTo(1);
    }

    @Test
    void itemOrderDoesNotChangeTheRequestIdentity() {
        allHappy();
        String key = "k-" + UUID.randomUUID();
        var body = new HashMap<>(orderBody("tok_visa_00004242"));
        create(key, body);
        body.put("items", List.of(Map.of("sku", "CASE-CLR", "qty", 2), Map.of("sku", "PHONE-128-BLK", "qty", 1)));

        assertThat(create(key, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void sameKeyDifferentBodyIs422() {
        allHappy();
        String key = "k-" + UUID.randomUUID();
        create(key, orderBody("tok_visa_00004242"));

        var res = create(key, orderBody("tok_visa_99999999"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ordersFor(key)).isEqualTo(1);
    }

    @Test
    void failedOrdersReplayTheirFailureToo() {
        allHappy();
        stub("POST", "/internal/reservations", 409, "{\"sku\":\"PHONE-128-BLK\"}");
        String key = "k-" + UUID.randomUUID();
        create(key, orderBody("tok_visa_00004242"));

        var again = create(key, orderBody("tok_visa_00004242"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("failureReason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(calls("POST", "/internal/reservations")).isEqualTo(1);
    }

    /** Spec section 5: concurrent duplicates must result in one order. Losers see 409 (in progress) or the replay. */
    @Test
    void concurrentDuplicatesCreateOneOrder() throws Exception {
        allHappy();
        String key = "k-" + UUID.randomUUID();
        int n = 25;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return create(key, orderBody("tok_visa_00004242"));
            }));
        }
        start.countDown();
        int created = 0, inProgress = 0;
        for (var f : futures) {
            var r = f.get();
            if (r.getStatusCode() == HttpStatus.CREATED) created++;
            else if (r.getStatusCode() == HttpStatus.CONFLICT) inProgress++;
        }
        pool.shutdown();

        assertThat(created + inProgress).isEqualTo(n);
        assertThat(created).isGreaterThanOrEqualTo(1);
        assertThat(ordersFor(key)).isEqualTo(1);
        assertThat(calls("POST", "/internal/reservations")).isEqualTo(1);
        assertThat(calls("POST", "/internal/payments/authorize")).isEqualTo(1);
    }

    @Test
    void missingOrMalformedKeyAndBadInputAre400() {
        allHappy();
        var noKey = http.postForEntity("/api/orders", orderBody("tok_visa_00004242"), JsonNode.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create("short", orderBody("tok_visa_00004242")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unknownSku = new HashMap<>(orderBody("tok_visa_00004242"));
        unknownSku.put("items", List.of(Map.of("sku", "NOPE-1", "qty", 1)));
        assertThat(create("k-" + UUID.randomUUID(), unknownSku).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create("k-" + UUID.randomUUID(), orderBody("4111111111111111")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(calls("POST", "/internal/reservations")).isZero();
    }
}
