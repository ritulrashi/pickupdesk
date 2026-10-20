package pickupdesk.order.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import pickupdesk.order.clients.DownstreamUnavailable;

/** Order API failures, each with one status code. */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    /** Base for all mapped failures. */
    public static class ApiException extends RuntimeException {
        final HttpStatus status;
        final String type;

        public ApiException(HttpStatus status, String type, String message) {
            super(message);
            this.status = status;
            this.type = type;
        }
    }

    public static ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "no order " + id);
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-request", message);
    }

    public static ApiException idempotencyConflict() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-conflict",
                "Idempotency-Key already used with a different request body");
    }

    /** Another request (a concurrent duplicate, or the recovery job) is working on this order right now. */
    public static ApiException busy(UUID id) {
        return new ApiException(HttpStatus.CONFLICT, "in-progress", "order " + id + " is being processed; retry shortly");
    }

    public static ApiException illegalState(UUID id, String status, String action) {
        return new ApiException(HttpStatus.CONFLICT, "illegal-transition",
                "cannot " + action + " order " + id + " in status " + status);
    }

    public static ApiException wrongPickupCode(int remaining) {
        return new ApiException(HttpStatus.FORBIDDEN, "wrong-pickup-code",
                "pickup code does not match; " + remaining + " attempts remaining");
    }

    public static ApiException pickupLocked() {
        return new ApiException(HttpStatus.LOCKED, "pickup-locked", "too many wrong pickup codes; reissue the code");
    }

    @ExceptionHandler
    ProblemDetail api(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status, e.getMessage());
        p.setType(URI.create("urn:pickupdesk:" + e.type));
        return p;
    }

    @ExceptionHandler
    ProblemDetail unavailable(DownstreamUnavailable e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        p.setType(URI.create("urn:pickupdesk:downstream-unavailable"));
        return p;
    }
}
