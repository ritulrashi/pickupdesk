package pickupdesk.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FailureInjectionTest {

    private static List<FailureInjection.Decision> drawMany(FailureInjection f, int n) {
        List<FailureInjection.Decision> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(f.draw());
        }
        return out;
    }

    @Test
    void sameSeedGivesSameSequence() {
        var s = new FailureInjection.Settings(0.3, 0.2, 100, 0, 0.5, 42L);
        var a = new FailureInjection();
        var b = new FailureInjection();
        a.set(s);
        b.set(s);
        assertThat(drawMany(a, 200)).isEqualTo(drawMany(b, 200));
    }

    @Test
    void ratesZeroAndOneAreExact() {
        var f = new FailureInjection();
        f.set(new FailureInjection.Settings(1.0, 0.0, 0, 0, 1.0, 7L));
        assertThat(drawMany(f, 500)).allSatisfy(d -> {
            assertThat(d.decline()).isTrue();
            assertThat(d.timeout()).isFalse();
            assertThat(d.failCapture()).isTrue();
        });
    }

    @Test
    void seededRateIsCloseToConfigured() {
        var f = new FailureInjection();
        f.set(new FailureInjection.Settings(0.25, 0, 0, 0, 0, 1L));
        long declines = drawMany(f, 10_000).stream().filter(FailureInjection.Decision::decline).count();
        assertThat(declines).isBetween(2_300L, 2_700L);
    }

    @Test
    void resetDisablesEverything() {
        var f = new FailureInjection();
        f.set(new FailureInjection.Settings(1, 1, 5, 5, 1, null));
        f.reset();
        assertThat(drawMany(f, 100)).allSatisfy(d ->
                assertThat(d.decline() || d.timeout() || d.failCapture()).isFalse());
    }
}
