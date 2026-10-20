package pickupdesk.order.clients;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import pickupdesk.order.OrderProperties;

/**
 * Every outbound call goes through here: a connect and read timeout on the HTTP client, a circuit breaker per
 * downstream, and bounded retries with exponential backoff and full-range jitter around it. Only transport
 * errors and 5xx are retried; a 4xx is an answer, not a failure. Values are in application.yml (ADR-015).
 */
@Component
public class Downstream {

    /** A completed HTTP exchange. {@code body} is null when the response had no JSON body. */
    public record Response(int status, JsonNode body) {
        public boolean is(int code) {
            return status == code;
        }
    }

    /** Thrown inside the decorated call so 5xx counts as a failure for retry and the breaker. */
    static class RetryableStatus extends RuntimeException {
        final Response response;

        RetryableStatus(Response response) {
            super("HTTP " + response.status());
            this.response = response;
        }
    }

    private final OrderProperties.Http cfg;
    private final ObjectMapper json;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final JdkClientHttpRequestFactory requestFactory;

    public Downstream(OrderProperties props, ObjectMapper json, MeterRegistry meters) {
        this.cfg = props.http();
        this.json = json;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.requestFactory = new JdkClientHttpRequestFactory(http);
        this.requestFactory.setReadTimeout(Duration.ofMillis(cfg.readTimeoutMs()));

        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.breakerWindow())
                .minimumNumberOfCalls(cfg.breakerMinCalls())
                .failureRateThreshold(cfg.breakerFailureRate())
                .waitDurationInOpenState(Duration.ofMillis(cfg.breakerOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(RetryableStatus.class, ResourceAccessException.class, IOException.class)
                .build());
        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(cfg.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(cfg.retryInitialBackoffMs()), cfg.retryMultiplier(), cfg.retryJitter()))
                .retryExceptions(RetryableStatus.class, ResourceAccessException.class, IOException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
    }

    public RestClient client(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public CircuitBreakerRegistry breakers() {
        return breakers;
    }

    /**
     * Runs {@code exchange} with retry(circuitBreaker(call)). Retry is outermost so each attempt is recorded
     * by the breaker, and an open breaker fails fast without being retried.
     *
     * @throws DownstreamUnavailable if all attempts fail, the breaker is open, or the last answer was a 5xx
     */
    public Response call(String downstream, Supplier<Response> exchange) {
        CircuitBreaker breaker = breakers.circuitBreaker(downstream);
        Retry retry = retries.retry(downstream);
        Supplier<Response> guarded = () -> {
            Response r = exchange.get();
            if (r.status() >= 500) {
                throw new RetryableStatus(r);
            }
            return r;
        };
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, guarded)).get();
        } catch (RetryableStatus e) {
            throw new DownstreamUnavailable(downstream, "HTTP " + e.response.status());
        } catch (CallNotPermittedException e) {
            throw new DownstreamUnavailable(downstream, "circuit open");
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailable(downstream, "transport: " + e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    /** Executes a request and captures status + JSON body without throwing on 4xx/5xx. */
    public Response exchange(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((req, res) -> {
            HttpStatusCode code = res.getStatusCode();
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode body = bytes.length == 0 ? null : json.readTree(bytes);
            return new Response(code.value(), body);
        });
    }
}
