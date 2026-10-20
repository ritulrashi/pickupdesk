package pickupdesk.inventory.api;

import java.net.URI;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import pickupdesk.inventory.reservation.ReservationExceptions.IdempotencyConflict;
import pickupdesk.inventory.reservation.ReservationExceptions.IllegalTransition;
import pickupdesk.inventory.reservation.ReservationExceptions.InsufficientStock;
import pickupdesk.inventory.reservation.ReservationExceptions.InvalidRequest;
import pickupdesk.inventory.reservation.ReservationExceptions.NotFound;
import pickupdesk.inventory.reservation.strategy.ContentionException;

/** Maps domain failures to RFC 9457 problem responses. Bean-validation failures are handled by the superclass (400). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail insufficient(InsufficientStock e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, e.skuKnown ? "insufficient-stock" : "unknown-sku", e);
        p.setProperty("sku", e.sku);
        return p;
    }

    @ExceptionHandler
    ProblemDetail illegal(IllegalTransition e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "illegal-transition", e);
        p.setProperty("currentStatus", e.current.name());
        return p;
    }

    @ExceptionHandler
    ProblemDetail idempotency(IdempotencyConflict e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-conflict", e);
    }

    @ExceptionHandler
    ProblemDetail notFound(NotFound e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e);
    }

    @ExceptionHandler
    ProblemDetail invalid(InvalidRequest e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e);
    }

    /** Optimistic strategy only: retries exhausted. The client may retry. */
    @ExceptionHandler
    ProblemDetail contention(ContentionException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "contention", e);
    }

    /** A CHECK constraint fired, e.g. setting on_hand below reserved. The database refused; nothing changed. */
    @ExceptionHandler
    ProblemDetail constraint(DataIntegrityViolationException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "rejected by a stock invariant");
        p.setType(URI.create("urn:pickupdesk:constraint-violation"));
        return p;
    }

    private static ProblemDetail problem(HttpStatus status, String type, RuntimeException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("urn:pickupdesk:" + type));
        return p;
    }
}
