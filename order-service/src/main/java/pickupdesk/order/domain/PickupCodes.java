package pickupdesk.order.domain;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

import pickupdesk.order.OrderProperties;

/**
 * Pickup codes: 8 characters from a 31-symbol alphabet without look-alikes (0/O, 1/I/L), about 2^39 values.
 * Stored only as salted PBKDF2-HMAC-SHA256, verified in constant time, and attempts per order are capped,
 * so neither a database leak nor online guessing yields usable codes cheaply.
 */
@Component
public class PickupCodes {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PickupCodes(OrderProperties props) {
        this.iterations = props.pickupCodeIterations();
    }

    public String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /** Format: {@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>}. */
    public String hash(String code) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$" + iterations + "$" + b64(salt) + "$" + b64(derive(code, salt, iterations));
    }

    public boolean matches(String code, String stored) {
        if (code == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        int iter = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        String normalized = code.trim().toUpperCase();
        return MessageDigest.isEqual(expected, derive(normalized, salt, iter));
    }

    private static byte[] derive(String code, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
