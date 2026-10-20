package pickupdesk.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import pickupdesk.inventory.reservation.strategy.ReserveStrategy;

@ConfigurationProperties("inventory")
public record InventoryProperties(
        ReserveStrategy reserveStrategy,
        int optimisticMaxAttempts,
        int defaultTtlSeconds,
        Expiry expiry,
        Events events) {

    public record Expiry(boolean enabled, long intervalMs, int batchSize) {
    }

    public record Events(boolean enabled, String topic) {
    }
}
