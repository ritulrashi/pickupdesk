package pickupdesk.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import pickupdesk.order.api.ApiErrors;
import pickupdesk.order.api.CreateOrderRequest;
import pickupdesk.order.api.OrderView;
import pickupdesk.order.clients.DownstreamUnavailable;
import pickupdesk.order.clients.PaymentClient;
import pickupdesk.order.clients.PaymentClient.OpOutcome;
import pickupdesk.order.domain.CatalogRepository;
import pickupdesk.order.domain.FailureReason;
import pickupdesk.order.domain.Order;
import pickupdesk.order.domain.OrderRepository;
import pickupdesk.order.domain.OrderStatus;
import pickupdesk.order.domain.OrderTransitions;
import pickupdesk.order.domain.PickupCodes;
import pickupdesk.order.idempotency.IdempotencyRepository;
import pickupdesk.order.saga.SagaOrchestrator;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** HTTP status + body of an API response, so a replay can return exactly the original. */
    public record ApiResult(int status, OrderView body) {
    }

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final CatalogRepository catalog;
    private final IdempotencyRepository idempotency;
    private final SagaOrchestrator saga;
    private final PaymentClient payment;
    private final PickupCodes codes;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final OrderProperties props;
    private final Timer createTimer;
    private final Counter captureAlerts;

    public OrderService(OrderRepository orders, OrderTransitions transitions, CatalogRepository catalog,
                        IdempotencyRepository idempotency, SagaOrchestrator saga, PaymentClient payment,
                        PickupCodes codes, TransactionTemplate tx, ObjectMapper json, OrderProperties props,
                        MeterRegistry meters) {
        this.orders = orders;
        this.transitions = transitions;
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.saga = saga;
        this.payment = payment;
        this.codes = codes;
        this.tx = tx;
        this.json = json;
        this.props = props;
        this.createTimer = Timer.builder("order.create.latency")
                .description("POST /api/orders end to end, including the synchronous saga")
                .publishPercentileHistogram()
                .register(meters);
        this.captureAlerts = Counter.builder("order.capture.alerts")
                .description("orders whose capture failed capture-alert-threshold times in a row")
                .register(meters);
    }

    // ---------------------------------------------------------------- create

    /**
     * Idempotent create (ADR-016). The first request with a key creates the order and runs the saga. A repeat
     * with the same body gets the stored response once the order is finished, resumes the saga if the first
     * attempt died part-way, or gets 409 while another request is driving it. A repeat with a different body
     * gets 422.
     */
    public ApiResult create(String key, CreateOrderRequest req) {
        return createTimer.record(() -> doCreate(key, req));
    }

    private ApiResult doCreate(String key, CreateOrderRequest req) {
        List<Order.Item> items = priced(req);
        String hash = requestHash(req);
        UUID orderId = UUID.randomUUID();
        String code = codes.generate();
        String codeHash = codes.hash(code);
        long total = items.stream().mapToLong(i -> i.unitPriceCents() * i.qty()).sum();

        Boolean claimed = tx.execute(t -> {
            if (!idempotency.claim(key, hash, orderId)) {
                return false;
            }
            orders.insert(orderId, req.customerRef(), req.storeId(), total, req.paymentToken(), codeHash,
                    props.leaseSeconds(), items);
            transitions.recordCreated(orders.find(orderId).orElseThrow());
            return true;
        });

        if (Boolean.TRUE.equals(claimed)) {
            return runAndFinish(key, orderId, code);
        }
        var rec = idempotency.find(key).orElseThrow();
        if (!rec.requestHash().equals(hash)) {
            throw ApiErrors.idempotencyConflict();
        }
        if (rec.completed()) {
            return new ApiResult(rec.responseStatus(), read(rec.responseBody()));
        }
        if (!orders.tryLease(rec.orderId(), props.leaseSeconds())) {
            throw ApiErrors.busy(rec.orderId());
        }
        // The original request died part-way (or gave up); finish the saga on its behalf. The pickup code from
        // the first attempt is not recoverable (only its hash is stored), so the customer reissues it.
        return runAndFinish(key, rec.orderId(), null);
    }

    private ApiResult runAndFinish(String key, UUID orderId, String pickupCode) {
        Order o;
        try {
            o = saga.advance(orderId);
        } finally {
            orders.releaseLease(orderId);
        }
        boolean showCode = pickupCode != null && o.status() == OrderStatus.CONFIRMED;
        OrderView view = OrderView.of(o, showCode ? pickupCode : null);
        int status = createStatus(o);
        if (!o.status().isUnfinished()) {
            // Stored without the pickup code: the code is shown once and only its hash is kept (ADR-016).
            idempotency.complete(key, status, write(view.withoutPickupCode()));
        }
        return new ApiResult(status, view);
    }

    private static int createStatus(Order o) {
        if (o.status() == OrderStatus.CONFIRMED) {
            return HttpStatus.CREATED.value();
        }
        if (o.status() == OrderStatus.CANCELLED) {
            String r = o.failureReason();
            if (FailureReason.OUT_OF_STOCK.name().equals(r)) return HttpStatus.CONFLICT.value();
            if (FailureReason.PAYMENT_DECLINED.name().equals(r)) return HttpStatus.PAYMENT_REQUIRED.value();
            return HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        // Still unfinished (a downstream is down mid-compensation): accepted, recovery will finish it.
        return HttpStatus.ACCEPTED.value();
    }

    private List<Order.Item> priced(CreateOrderRequest req) {
        var seen = new HashSet<String>();
        for (var i : req.items()) {
            if (!seen.add(i.sku())) {
                throw ApiErrors.invalid("duplicate sku " + i.sku());
            }
        }
        var prices = catalog.prices(seen);
        return req.items().stream()
                .sorted(Comparator.comparing(CreateOrderRequest.Item::sku))
                .map(i -> {
                    Long price = prices.get(i.sku());
                    if (price == null) {
                        throw ApiErrors.invalid("unknown sku " + i.sku());
                    }
                    return new Order.Item(i.sku(), i.qty(), price);
                })
                .toList();
    }

    /** Hash of everything that defines the order. Item order does not matter. */
    static String requestHash(CreateOrderRequest req) {
        StringBuilder sb = new StringBuilder()
                .append(req.customerRef()).append('|').append(req.storeId()).append('|').append(req.paymentToken());
        req.items().stream().sorted(Comparator.comparing(CreateOrderRequest.Item::sku))
                .forEach(i -> sb.append('|').append(i.sku()).append('x').append(i.qty()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- reads

    public Order get(UUID id) {
        return orders.find(id).orElseThrow(() -> ApiErrors.notFound(id));
    }

    public List<Order> pickupQueue(String storeId) {
        return orders.pickupQueue(storeId);
    }

    // ---------------------------------------------------------------- customer actions

    /** CREATED | RESERVED -> CANCELLED, compensating whatever may have happened. */
    public Order cancel(UUID id) {
        return withLease(id, o -> {
            if (o.status() != OrderStatus.CREATED && o.status() != OrderStatus.RESERVED) {
                throw ApiErrors.illegalState(id, o.status().name(), "cancel");
            }
            transitions.recordFailure(id, o.status(), FailureReason.CUSTOMER_CANCELLED);
            Order after = saga.advance(id);
            if (after.status() != OrderStatus.CANCELLED) {
                throw new DownstreamUnavailable("compensation", "cancel recorded, compensation will be retried");
            }
            return after;
        });
    }

    /** Replaces a lost pickup code. The old code stops working. */
    public String reissuePickupCode(UUID id) {
        Order o = get(id);
        if (o.status() != OrderStatus.CONFIRMED && o.status() != OrderStatus.READY_FOR_PICKUP) {
            throw ApiErrors.illegalState(id, o.status().name(), "reissue the pickup code for");
        }
        String code = codes.generate();
        orders.replacePickupCode(id, codes.hash(code));
        return code;
    }

    // ---------------------------------------------------------------- associate actions

    public Order markReady(UUID id) {
        return transitions.apply(id, OrderStatus.READY_FOR_PICKUP)
                .orElseThrow(() -> ApiErrors.illegalState(id, get(id).status().name(), "mark ready"));
    }

    /**
     * Verify the code, capture the payment, then PICKED_UP (whose outbox event makes inventory consume the
     * hold). If capture fails the order stays READY_FOR_PICKUP and the associate retries.
     */
    public Order pickup(UUID id, String code) {
        return withLease(id, o -> {
            if (o.status() != OrderStatus.READY_FOR_PICKUP) {
                throw ApiErrors.illegalState(id, o.status().name(), "hand over");
            }
            if (o.pickupAttempts() >= props.maxPickupAttempts()) {
                throw ApiErrors.pickupLocked();
            }
            if (!codes.matches(code, o.pickupCodeHash())) {
                int used = orders.incrementPickupAttempts(id);
                throw ApiErrors.wrongPickupCode(Math.max(0, props.maxPickupAttempts() - used));
            }
            OpOutcome captured;
            try {
                captured = payment.capture(o.paymentId());
            } catch (DownstreamUnavailable e) {
                int failures = orders.incrementCaptureFailures(id);
                if (failures == props.captureAlertThreshold()) {
                    captureAlerts.increment();
                    log.error("ALERT order {} capture failed {} times", id, failures);
                }
                throw e;
            }
            if (captured instanceof OpOutcome.Illegal illegal) {
                throw ApiErrors.illegalState(id, "payment " + illegal.currentStatus(), "capture");
            }
            return transitions.apply(id, OrderStatus.PICKED_UP).orElseThrow();
        });
    }

    /** PICKED_UP -> refund -> RETURNED (whose outbox event makes inventory put the stock back). */
    public Order returnOrder(UUID id) {
        return withLease(id, o -> {
            if (o.status() != OrderStatus.PICKED_UP) {
                throw ApiErrors.illegalState(id, o.status().name(), "return");
            }
            if (payment.refund(o.paymentId()) instanceof OpOutcome.Illegal illegal) {
                throw ApiErrors.illegalState(id, "payment " + illegal.currentStatus(), "refund");
            }
            return transitions.apply(id, OrderStatus.RETURNED).orElseThrow();
        });
    }

    private <T> T withLease(UUID id, Function<Order, T> action) {
        get(id);   // 404 before trying to lease
        if (!orders.tryLease(id, props.leaseSeconds())) {
            throw ApiErrors.busy(id);
        }
        try {
            return action.apply(orders.find(id).orElseThrow());
        } finally {
            orders.releaseLease(id);
        }
    }

    private String write(OrderView view) {
        try {
            return json.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderView read(String body) {
        try {
            return json.readValue(body, OrderView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
