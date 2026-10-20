package pickupdesk.order.clients;

/**
 * The downstream did not give a usable answer (timeouts, 5xx after retries, breaker open). The outcome of the
 * request is unknown: it may or may not have taken effect, so callers must compensate idempotently.
 */
public class DownstreamUnavailable extends RuntimeException {

    public final String downstream;

    public DownstreamUnavailable(String downstream, String detail) {
        super(downstream + " unavailable: " + detail);
        this.downstream = downstream;
    }
}
