package pickupdesk.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import pickupdesk.payment.PaymentExceptions.GatewayUnavailable;
import pickupdesk.payment.PaymentExceptions.IdempotencyConflict;
import pickupdesk.payment.PaymentExceptions.IllegalTransition;
import pickupdesk.payment.PaymentExceptions.NotFound;
import pickupdesk.payment.PaymentExceptions.OrderAlreadyAuthorized;

@Service
public class PaymentService {

    /** Tokens with this prefix are always declined, so tests can force a decline without randomness. */
    public static final String DECLINE_TOKEN_PREFIX = "tok_decline";

    public record AuthorizeRequest(UUID orderId, String token, long amountCents) {
    }

    /** @param replayed true when the idempotency key was seen before and the stored payment is returned */
    public record AuthorizeResult(Payment payment, boolean replayed) {
    }

    private final PaymentRepository payments;
    private final FailureInjection faults;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, FailureInjection faults, TransactionTemplate tx) {
        this.payments = payments;
        this.faults = faults;
        this.tx = tx;
    }

    /**
     * Idempotent authorize. The same key with the same body always returns the one payment it created, whether
     * the retry is sequential or concurrent. The same key with a different body is rejected (422).
     * An injected timeout happens <em>after</em> commit: the charge exists but the caller never hears about it,
     * which is exactly the case a retry with the same key must survive without charging twice.
     */
    public AuthorizeResult authorize(String idempotencyKey, AuthorizeRequest req) {
        String hash = requestHash(req);
        var seen = payments.findByIdempotencyKey(idempotencyKey);
        if (seen.isPresent()) {
            return replay(seen.get(), hash, req);
        }

        FailureInjection.Decision d = faults.draw();
        FailureInjection.sleep(d.latencyMs());
        boolean decline = d.decline() || req.token().startsWith(DECLINE_TOKEN_PREFIX);
        PaymentStatus status = decline ? PaymentStatus.DECLINED : PaymentStatus.AUTHORIZED;
        String reason = decline ? (d.decline() ? "injected_decline" : "card_declined") : null;

        AuthorizeResult result;
        try {
            result = tx.execute(t -> {
                var inserted = payments.insertIfNewKey(UUID.randomUUID(), req.orderId(), req.token(),
                        req.amountCents(), status, reason, idempotencyKey, hash);
                if (inserted.isEmpty()) {
                    // A concurrent request with the same key committed first.
                    return replay(payments.findByIdempotencyKey(idempotencyKey).orElseThrow(), hash, req);
                }
                payments.appendEvent(inserted.get().id(), status);
                return new AuthorizeResult(inserted.get(), false);
            });
        } catch (DuplicateKeyException e) {
            // payments_one_live_per_order: a different key already holds a live authorization for this order.
            throw new OrderAlreadyAuthorized(req.orderId());
        }

        if (d.timeout() && !result.replayed()) {
            FailureInjection.sleep(d.timeoutMs());
        }
        return result;
    }

    /** Capture at pickup. Injected capture failures happen before any state change, so the caller can retry. */
    public Payment capture(UUID paymentId) {
        FailureInjection.Decision d = faults.draw();
        FailureInjection.sleep(d.latencyMs());
        if (d.failCapture()) {
            throw new GatewayUnavailable("injected capture failure");
        }
        Payment p = move(paymentId, PaymentStatus.CAPTURED);
        if (d.timeout()) {
            FailureInjection.sleep(d.timeoutMs());
        }
        return p;
    }

    /** Releases an authorization that will never be captured (order cancelled before pickup). */
    public Payment voidAuthorization(UUID paymentId) {
        FailureInjection.Decision d = faults.draw();
        FailureInjection.sleep(d.latencyMs());
        return move(paymentId, PaymentStatus.VOIDED);
    }

    public Payment refund(UUID paymentId) {
        FailureInjection.Decision d = faults.draw();
        FailureInjection.sleep(d.latencyMs());
        return move(paymentId, PaymentStatus.REFUNDED);
    }

    public Payment get(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new NotFound(paymentId));
    }

    /** Transition with status-based idempotency: repeating a completed operation returns the same result. */
    private Payment move(UUID paymentId, PaymentStatus target) {
        return tx.execute(t -> {
            var moved = payments.transition(paymentId, target);
            if (moved.isPresent()) {
                payments.appendEvent(paymentId, target);
                return moved.get();
            }
            Payment current = payments.findById(paymentId).orElseThrow(() -> new NotFound(paymentId));
            if (current.status() == target) {
                return current;
            }
            throw new IllegalTransition(paymentId, current.status(), target);
        });
    }

    private static AuthorizeResult replay(Payment existing, String hash, AuthorizeRequest req) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyConflict();
        }
        return new AuthorizeResult(existing, true);
    }

    /** SHA-256 over the fields that define the charge. The token is hashed, never stored in the hash in clear. */
    static String requestHash(AuthorizeRequest req) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = req.orderId() + "|" + req.token() + "|" + req.amountCents();
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
