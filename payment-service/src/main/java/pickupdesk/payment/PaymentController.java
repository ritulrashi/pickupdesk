package pickupdesk.payment;

import java.net.URI;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import pickupdesk.payment.PaymentExceptions.GatewayUnavailable;
import pickupdesk.payment.PaymentExceptions.IdempotencyConflict;
import pickupdesk.payment.PaymentExceptions.IllegalTransition;
import pickupdesk.payment.PaymentExceptions.NotFound;
import pickupdesk.payment.PaymentExceptions.OrderAlreadyAuthorized;

/** Service-to-service gateway API, called by order-service. Authentication arrives in Phase 5. */
@RestController
public class PaymentController {

    /** Opaque card token. Anything else, including a raw card number, fails validation. */
    public record AuthorizeBody(
            @NotNull UUID orderId,
            @NotNull @Pattern(regexp = "tok_[A-Za-z0-9_]{8,64}") String token,
            @Positive @Max(100_000_000) long amountCents) {
    }

    public record PaymentRef(@NotNull UUID paymentId) {
    }

    private final PaymentService service;
    private final FailureInjection faults;

    public PaymentController(PaymentService service, FailureInjection faults) {
        this.service = service;
        this.faults = faults;
    }

    /**
     * 201 when authorized, 402 when declined. A replay of a known key returns the stored payment with the
     * same status code and {@code Idempotent-Replayed: true}.
     */
    @PostMapping("/internal/payments/authorize")
    public ResponseEntity<Payment> authorize(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_:.-]{8,128}") String key,
            @Valid @RequestBody AuthorizeBody body) {
        var result = service.authorize(key,
                new PaymentService.AuthorizeRequest(body.orderId(), body.token(), body.amountCents()));
        HttpStatus status = result.payment().status() == PaymentStatus.DECLINED
                ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.payment());
    }

    @PostMapping("/internal/payments/capture")
    public Payment capture(@Valid @RequestBody PaymentRef ref) {
        return service.capture(ref.paymentId());
    }

    @PostMapping("/internal/payments/void")
    public Payment voidAuthorization(@Valid @RequestBody PaymentRef ref) {
        return service.voidAuthorization(ref.paymentId());
    }

    @PostMapping("/internal/payments/refund")
    public Payment refund(@Valid @RequestBody PaymentRef ref) {
        return service.refund(ref.paymentId());
    }

    @GetMapping("/internal/payments/{id}")
    public Payment get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** ADMIN only once security lands. Settings are per instance and in memory. */
    @PostMapping("/admin/failure-injection")
    public FailureInjection.Settings setFaults(@Valid @RequestBody FailureInjection.Settings settings) {
        faults.set(settings);
        return faults.get();
    }

    @GetMapping("/admin/failure-injection")
    public FailureInjection.Settings getFaults() {
        return faults.get();
    }

    @DeleteMapping("/admin/failure-injection")
    public FailureInjection.Settings resetFaults() {
        faults.reset();
        return faults.get();
    }

    @RestControllerAdvice
    static class Errors extends ResponseEntityExceptionHandler {

        @ExceptionHandler
        ProblemDetail idempotency(IdempotencyConflict e) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-conflict", e);
        }

        @ExceptionHandler
        ProblemDetail alreadyAuthorized(OrderAlreadyAuthorized e) {
            return problem(HttpStatus.CONFLICT, "order-already-authorized", e);
        }

        @ExceptionHandler
        ProblemDetail illegal(IllegalTransition e) {
            ProblemDetail p = problem(HttpStatus.CONFLICT, "illegal-transition", e);
            p.setProperty("currentStatus", e.current.name());
            return p;
        }

        @ExceptionHandler
        ProblemDetail notFound(NotFound e) {
            return problem(HttpStatus.NOT_FOUND, "not-found", e);
        }

        @ExceptionHandler
        ProblemDetail unavailable(GatewayUnavailable e) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "gateway-unavailable", e);
        }

        @ExceptionHandler
        ProblemDetail constraint(DataIntegrityViolationException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "rejected by a payment invariant");
            p.setType(URI.create("urn:pickupdesk:constraint-violation"));
            return p;
        }

        /** Header and path constraint failures (e.g. a malformed Idempotency-Key) are client errors. */
        @ExceptionHandler
        ProblemDetail constraintViolation(jakarta.validation.ConstraintViolationException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid request");
            p.setType(URI.create("urn:pickupdesk:invalid-request"));
            return p;
        }

        private static ProblemDetail problem(HttpStatus status, String type, RuntimeException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
            p.setType(URI.create("urn:pickupdesk:" + type));
            return p;
        }
    }
}
