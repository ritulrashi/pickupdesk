package pickupdesk.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    @Test
    void requestHashDependsOnEveryChargeField() {
        UUID order = UUID.randomUUID();
        var base = new PaymentService.AuthorizeRequest(order, "tok_aaaaaaaa", 1000);
        String h = PaymentService.requestHash(base);
        assertThat(PaymentService.requestHash(new PaymentService.AuthorizeRequest(order, "tok_aaaaaaaa", 1000))).isEqualTo(h);
        assertThat(PaymentService.requestHash(new PaymentService.AuthorizeRequest(order, "tok_aaaaaaaa", 1001))).isNotEqualTo(h);
        assertThat(PaymentService.requestHash(new PaymentService.AuthorizeRequest(order, "tok_bbbbbbbb", 1000))).isNotEqualTo(h);
        assertThat(PaymentService.requestHash(new PaymentService.AuthorizeRequest(UUID.randomUUID(), "tok_aaaaaaaa", 1000))).isNotEqualTo(h);
        assertThat(h).doesNotContain("tok_aaaaaaaa").hasSize(64);
    }

    @Test
    void paymentToStringAndJsonNeverCarryTheToken() {
        var p = new Payment(UUID.randomUUID(), UUID.randomUUID(), "tok_secret1234", 100, PaymentStatus.AUTHORIZED,
                null, "h", null);
        assertThat(p.toString()).doesNotContain("tok_secret");
        assertThat(p.tokenLast4()).isEqualTo("1234");
    }
}
