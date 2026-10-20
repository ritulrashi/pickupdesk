package pickupdesk.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Payment(
        UUID id,
        UUID orderId,
        @JsonIgnore String token,
        long amountCents,
        PaymentStatus status,
        String declineReason,
        @JsonIgnore String requestHash,
        OffsetDateTime createdAt) {

    /** Only the last four characters of the token ever leave the service. */
    @JsonProperty("tokenLast4")
    public String tokenLast4() {
        return token.length() <= 4 ? "****" : token.substring(token.length() - 4);
    }

    @Override
    public String toString() {
        return "Payment[id=" + id + ", orderId=" + orderId + ", status=" + status + ", amountCents=" + amountCents + "]";
    }
}
