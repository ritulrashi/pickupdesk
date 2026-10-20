package pickupdesk.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order")
public record OrderProperties(
        String inventoryUrl,
        String paymentUrl,
        String topic,
        int leaseSeconds,
        long pickupWindowSeconds,
        long sagaDeadlineSeconds,
        int maxPickupAttempts,
        int captureAlertThreshold,
        int pickupCodeIterations,
        Http http,
        Jobs jobs) {

    public record Http(
            int connectTimeoutMs,
            int readTimeoutMs,
            int retryMaxAttempts,
            long retryInitialBackoffMs,
            double retryMultiplier,
            double retryJitter,
            int breakerWindow,
            int breakerMinCalls,
            float breakerFailureRate,
            long breakerOpenMs) {
    }

    public record Jobs(
            boolean enabled,
            long relayIntervalMs,
            int relayBatchSize,
            long recoveryIntervalMs,
            long recoveryStaleSeconds,
            long expiryIntervalMs) {
    }
}
