package pickupdesk.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import pickupdesk.inventory.InventoryITBase;

class ReservationApiIT extends InventoryITBase {

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return http.postForEntity(path, body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> release(UUID orderId) {
        return http.exchange("/internal/reservations/" + orderId, HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
    }

    @Test
    void reserveHoldsStockAndReturns201() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();

        var res = post("/internal/reservations", request(order, store, sku, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asText()).isEqualTo("HELD");
        assertThat(res.getBody().get("expiresAt").isNull()).isFalse();
        assertThat(stock(store, sku).reserved()).isEqualTo(3);
        assertThat(stock(store, sku).onHand()).isEqualTo(10);
        assertThat(http.getForObject("/api/inventory/" + store + "/" + sku, JsonNode.class).get("available").asInt())
                .isEqualTo(7);
    }

    @Test
    void retryWithSameOrderIdReturnsOriginalAndDoesNotReserveTwice() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();

        var first = post("/internal/reservations", request(order, store, sku, 3));
        var second = post("/internal/reservations", request(order, store, sku, 3));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(stock(store, sku).reserved()).isEqualTo(3);
    }

    @Test
    void sameOrderIdWithDifferentContentIs422() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();
        post("/internal/reservations", request(order, store, sku, 3));

        var res = post("/internal/reservations", request(order, store, sku, 4));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(stock(store, sku).reserved()).isEqualTo(3);
    }

    @Test
    void concurrentDuplicatesOfOneOrderReserveExactlyOnce() throws Exception {
        String store = newStore(), sku = newSku();
        seed(store, sku, 100);
        UUID order = UUID.randomUUID();
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/internal/reservations", request(order, store, sku, 2));
            }));
        }
        start.countDown();
        int created = 0, ok = 0;
        for (var f : futures) {
            HttpStatus s = HttpStatus.valueOf(f.get().getStatusCode().value());
            if (s == HttpStatus.CREATED) created++;
            else if (s == HttpStatus.OK) ok++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(ok).isEqualTo(n - 1);
        assertThat(stock(store, sku).reserved()).isEqualTo(2);
    }

    @Test
    void insufficientStockIs409AndLeavesNothingBehind() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 2);
        UUID order = UUID.randomUUID();

        var res = post("/internal/reservations", request(order, store, sku, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("type").asText()).isEqualTo("urn:pickupdesk:insufficient-stock");
        assertThat(stock(store, sku).reserved()).isZero();
        assertThat(statusOf(order)).isNull();
    }

    @Test
    void unknownSkuIs409WithDistinctType() {
        var res = post("/internal/reservations", request(UUID.randomUUID(), newStore(), newSku(), 1));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("type").asText()).isEqualTo("urn:pickupdesk:unknown-sku");
    }

    @Test
    void multiLineReservationIsAllOrNothing() {
        String store = newStore(), a = "A-" + newSku(), b = "B-" + newSku();
        seed(store, a, 5);
        seed(store, b, 1);
        UUID order = UUID.randomUUID();
        var req = new ReserveRequest(order, store,
                List.of(new ReserveRequest.Line(a, 2), new ReserveRequest.Line(b, 2)), null);

        var res = post("/internal/reservations", req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(store, a).reserved()).as("line A rolled back").isZero();
        assertThat(stock(store, b).reserved()).isZero();
        assertThat(statusOf(order)).isNull();
    }

    @Test
    void releaseRestoresStockAndIsIdempotent() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();
        post("/internal/reservations", request(order, store, sku, 4));

        var first = release(order);
        var second = release(order);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("status").asText()).isEqualTo("RELEASED");
        assertThat(stock(store, sku).reserved()).isZero();
    }

    @Test
    void releaseBeforeReserveWritesTombstoneSoLateReserveTakesNothing() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();

        var rel = release(order);
        var late = post("/internal/reservations", request(order, store, sku, 4));

        assertThat(rel.getBody().get("status").asText()).isEqualTo("RELEASED");
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(late.getBody().get("status").asText()).isEqualTo("RELEASED");
        assertThat(stock(store, sku).reserved()).isZero();
    }

    @Test
    void fullLifecycleConfirmConsumeReturn() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();
        post("/internal/reservations", request(order, store, sku, 2));

        var confirmed = post("/internal/reservations/" + order + "/confirm", null);
        assertThat(confirmed.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().get("expiresAt").isNull()).as("confirmed holds do not expire").isTrue();
        assertThat(stock(store, sku)).extracting("onHand", "reserved").containsExactly(10, 2);

        post("/internal/reservations/" + order + "/consume", null);
        assertThat(stock(store, sku)).extracting("onHand", "reserved").containsExactly(8, 0);

        var returned = post("/internal/reservations/" + order + "/return", null);
        assertThat(returned.getBody().get("status").asText()).isEqualTo("RETURNED");
        assertThat(stock(store, sku)).extracting("onHand", "reserved").containsExactly(10, 0);

        var again = post("/internal/reservations/" + order + "/return", null);
        assertThat(again.getStatusCode()).as("repeat is idempotent").isEqualTo(HttpStatus.OK);
        assertThat(stock(store, sku).onHand()).isEqualTo(10);
    }

    @Test
    void illegalTransitionsAre409AndChangeNothing() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        UUID order = UUID.randomUUID();
        post("/internal/reservations", request(order, store, sku, 2));

        var consumeWhileHeld = post("/internal/reservations/" + order + "/consume", null);
        assertThat(consumeWhileHeld.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(consumeWhileHeld.getBody().get("currentStatus").asText()).isEqualTo("HELD");

        release(order);
        var confirmAfterRelease = post("/internal/reservations/" + order + "/confirm", null);
        assertThat(confirmAfterRelease.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(store, sku)).extracting("onHand", "reserved").containsExactly(10, 0);
    }

    @Test
    void transitionOnUnknownOrderIs404() {
        var res = post("/internal/reservations/" + UUID.randomUUID() + "/confirm", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidInputIs400() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        assertThat(post("/internal/reservations", request(UUID.randomUUID(), "store-1", sku, 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/internal/reservations", request(UUID.randomUUID(), store, "bad sku;--", 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/internal/reservations", request(UUID.randomUUID(), store, sku, 0)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var dup = new ReserveRequest(UUID.randomUUID(), store,
                List.of(new ReserveRequest.Line(sku, 1), new ReserveRequest.Line(sku, 1)), null);
        assertThat(post("/internal/reservations", dup).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stock(store, sku).reserved()).isZero();
    }

    @Test
    void adminCannotSetOnHandBelowReserved() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        post("/internal/reservations", request(UUID.randomUUID(), store, sku, 6));

        var res = post("/admin/stock", Map.of("storeId", store, "sku", sku, "onHand", 5));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(store, sku).onHand()).isEqualTo(10);
    }
}
