package pickupdesk.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import pickupdesk.inventory.InventoryITBase;

class ExpiryIT extends InventoryITBase {

    @Autowired
    ReservationService service;
    @Autowired
    ReservationExpiryJob job;

    private List<UUID> reserveMany(String store, String sku, int n) {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID order = UUID.randomUUID();
            service.reserve(request(order, store, sku, 1));
            orders.add(order);
        }
        return orders;
    }

    private void makeDue(List<UUID> orders) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE order_id IN (:ids)")
                .param("ids", orders)
                .update();
    }

    private static List<Integer> runConcurrently(int threads, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    void expiresOnlyDueHeldReservationsAndReturnsTheirStock() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 10);
        List<UUID> orders = reserveMany(store, sku, 5);
        makeDue(orders.subList(0, 3));
        service.confirm(orders.get(4));

        job.expireAllDue();

        assertThat(orders.subList(0, 3)).allSatisfy(o -> assertThat(statusOf(o)).isEqualTo("EXPIRED"));
        assertThat(statusOf(orders.get(3))).isEqualTo("HELD");
        assertThat(statusOf(orders.get(4))).isEqualTo("CONFIRMED");
        assertThat(stock(store, sku).reserved()).isEqualTo(2);
        assertThat(heldUnits(store, sku)).isEqualTo(2);
    }

    /** Four "instances" draining the same backlog in small batches: every reservation expires exactly once. */
    @Test
    void concurrentRunsClaimDisjointRows() throws Exception {
        String store = newStore(), sku = newSku();
        seed(store, sku, 200);
        List<UUID> orders = reserveMany(store, sku, 120);
        makeDue(orders.subList(0, 90));

        List<Integer> perThread = runConcurrently(4, () -> {
            int mine = 0, n;
            while ((n = job.expireBatch(7)) > 0) {
                mine += n;
            }
            return mine;
        });

        assertThat(perThread.stream().mapToInt(Integer::intValue).sum()).as("each due row claimed once").isEqualTo(90);
        assertThat(stock(store, sku).reserved()).isEqualTo(30);
        assertThat(heldUnits(store, sku)).isEqualTo(30);
    }

    /** Release (compensation) racing the expiry job on the same rows: stock comes back exactly once per row. */
    @Test
    void releaseRacingExpiryReturnsStockOnce() throws Exception {
        String store = newStore(), sku = newSku();
        seed(store, sku, 100);
        List<UUID> orders = reserveMany(store, sku, 60);
        makeDue(orders);

        AtomicInteger role = new AtomicInteger();
        runConcurrently(2, () -> {
            if (role.getAndIncrement() == 0) {
                return job.expireAllDue();
            }
            orders.forEach(service::release);
            return 0;
        });

        assertThat(orders).allSatisfy(o -> assertThat(statusOf(o)).isIn("EXPIRED", "RELEASED"));
        assertThat(stock(store, sku).reserved()).isZero();
        assertThat(stock(store, sku).onHand()).isEqualTo(100);
    }

    @Test
    void releasingAnExpiredReservationIsANoOp() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 5);
        UUID order = reserveMany(store, sku, 1).get(0);
        makeDue(List.of(order));
        job.expireAllDue();

        var r = service.release(order);

        assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stock(store, sku).reserved()).isZero();
    }
}
