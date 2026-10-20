package pickupdesk.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import pickupdesk.inventory.InventoryITBase;
import pickupdesk.inventory.reservation.ReservationExceptions.InsufficientStock;
import pickupdesk.inventory.reservation.strategy.ContentionException;
import pickupdesk.inventory.reservation.strategy.ReserveStrategy;

/**
 * 200 concurrent single-unit reservations against 50 units, released at the same instant, once per strategy.
 * The HTTP-level version with repeated runs is bench/oversell_test.py; this one guards every CI build.
 */
class OversellIT extends InventoryITBase {

    private static final int STOCK = 50;
    private static final int ATTEMPTS = 200;

    @Autowired
    ReservationService service;

    enum Outcome { RESERVED, INSUFFICIENT, CONTENTION }

    @ParameterizedTest
    @EnumSource(ReserveStrategy.class)
    void neverOversells(ReserveStrategy strategy) throws Exception {
        String store = newStore(), sku = newSku();
        seed(store, sku, STOCK);

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.reserve(request(UUID.randomUUID(), store, sku, 1), strategy);
                    return Outcome.RESERVED;
                } catch (InsufficientStock e) {
                    return Outcome.INSUFFICIENT;
                } catch (ContentionException e) {
                    return Outcome.CONTENTION;
                }
            }));
        }
        start.countDown();
        int reserved = 0, insufficient = 0, contention = 0;
        for (var f : futures) {
            switch (f.get()) {
                case RESERVED -> reserved++;
                case INSUFFICIENT -> insufficient++;
                case CONTENTION -> contention++;
            }
        }
        pool.shutdown();

        var row = stock(store, sku);
        assertThat(row.onHand()).isEqualTo(STOCK);
        assertThat(row.reserved()).as("reserved matches successful calls").isEqualTo(reserved);
        assertThat(heldUnits(store, sku)).as("reservation lines match stock.reserved").isEqualTo(row.reserved());
        assertThat(reserved).as("never oversold").isLessThanOrEqualTo(STOCK);
        assertThat(reserved + insufficient + contention).isEqualTo(ATTEMPTS);

        if (strategy == ReserveStrategy.OPTIMISTIC) {
            // Optimistic may give up under contention (a false rejection), but must never oversell, and
            // every unit left unsold must be explained by a request that gave up.
            assertThat(STOCK - reserved).isLessThanOrEqualTo(contention);
        } else {
            assertThat(reserved).as("locking strategies sell out exactly").isEqualTo(STOCK);
            assertThat(insufficient).isEqualTo(ATTEMPTS - STOCK);
            assertThat(contention).isZero();
        }
    }

    /**
     * Two-sku orders arriving with their lines in opposite orders. Without the sku sort in reserve, thread 1
     * would lock A then wait for B while thread 2 holds B and waits for A, and Postgres would abort one as a
     * deadlock victim. With the sort, every call succeeds.
     */
    @ParameterizedTest
    @EnumSource(value = ReserveStrategy.class, names = {"CONDITIONAL", "FOR_UPDATE"})
    void oppositeLineOrdersDoNotDeadlock(ReserveStrategy strategy) throws Exception {
        String store = newStore(), a = "A-" + newSku(), b = "B-" + newSku();
        int orders = 100;
        seed(store, a, orders);
        seed(store, b, orders);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            var lines = i % 2 == 0
                    ? List.of(new ReserveRequest.Line(a, 1), new ReserveRequest.Line(b, 1))
                    : List.of(new ReserveRequest.Line(b, 1), new ReserveRequest.Line(a, 1));
            futures.add(pool.submit(() -> {
                start.await();
                return service.reserve(new ReserveRequest(UUID.randomUUID(), store, lines, null), strategy);
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();   // a deadlock victim surfaces here as an ExecutionException
        }
        pool.shutdown();

        assertThat(stock(store, a).reserved()).isEqualTo(orders);
        assertThat(stock(store, b).reserved()).isEqualTo(orders);
    }
}
