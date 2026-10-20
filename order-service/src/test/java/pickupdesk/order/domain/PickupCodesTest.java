package pickupdesk.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import pickupdesk.order.OrderProperties;

class PickupCodesTest {

    private final PickupCodes codes = new PickupCodes(
            new OrderProperties(null, null, null, 0, 0, 0, 0, 0, 1000, null, null));

    @Test
    void codesAreEightUnambiguousCharacters() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String c = codes.generate();
            assertThat(c).matches("[A-HJKMNP-Z2-9]{8}");
            seen.add(c);
        }
        assertThat(seen).hasSizeGreaterThan(995);
    }

    @Test
    void hashIsSaltedAndVerifiesCaseInsensitively() {
        String code = codes.generate();
        String h1 = codes.hash(code);
        String h2 = codes.hash(code);
        assertThat(h1).isNotEqualTo(h2).doesNotContain(code).startsWith("pbkdf2-sha256$1000$");
        assertThat(codes.matches(code, h1)).isTrue();
        assertThat(codes.matches(code.toLowerCase() + " ", h2)).isTrue();
        assertThat(codes.matches("AAAAAAAA", h1)).isFalse();
        assertThat(codes.matches(null, h1)).isFalse();
        assertThat(codes.matches(code, "garbage")).isFalse();
    }
}
