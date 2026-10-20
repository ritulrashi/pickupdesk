package pickupdesk.payment;

import java.util.Random;

import org.springframework.stereotype.Component;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * In-memory, per-instance fault settings for chaos tests. Draws come from one seeded {@link Random}, so a
 * single-threaded driver that sets the same seed sees the same sequence of declines and timeouts every run.
 */
@Component
public class FailureInjection {

    public record Settings(
            @DecimalMin("0.0") @DecimalMax("1.0") double declineRate,
            @DecimalMin("0.0") @DecimalMax("1.0") double timeoutRate,
            @Min(0) @Max(60_000) long timeoutMs,
            @Min(0) @Max(10_000) long latencyMs,
            @DecimalMin("0.0") @DecimalMax("1.0") double captureFailureRate,
            Long seed) {

        public static final Settings NONE = new Settings(0, 0, 0, 0, 0, null);
    }

    /** What to do to one call, decided up front so the decision is recorded before any side effect. */
    public record Decision(boolean decline, boolean timeout, boolean failCapture, long latencyMs, long timeoutMs) {
    }

    private Settings settings = Settings.NONE;
    private Random random = new Random();

    public synchronized Settings get() {
        return settings;
    }

    public synchronized void set(Settings s) {
        settings = s;
        random = s.seed() == null ? new Random() : new Random(s.seed());
    }

    public synchronized void reset() {
        set(Settings.NONE);
    }

    /** Draws in a fixed order (decline, timeout, capture) so a seed fully determines the sequence. */
    public synchronized Decision draw() {
        Settings s = settings;
        boolean decline = random.nextDouble() < s.declineRate();
        boolean timeout = random.nextDouble() < s.timeoutRate();
        boolean failCapture = random.nextDouble() < s.captureFailureRate();
        return new Decision(decline, timeout, failCapture, s.latencyMs(), s.timeoutMs());
    }

    static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
