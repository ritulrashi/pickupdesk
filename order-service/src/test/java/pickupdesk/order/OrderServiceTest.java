package pickupdesk.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import pickupdesk.order.api.CreateOrderRequest;

class OrderServiceTest {

    private static CreateOrderRequest req(String token, CreateOrderRequest.Item... items) {
        return new CreateOrderRequest("cust-1", "STORE-0042", List.of(items), token);
    }

    @Test
    void requestHashIgnoresItemOrderButNotContent() {
        var a = new CreateOrderRequest.Item("A-1", 1);
        var b = new CreateOrderRequest.Item("B-1", 2);
        String h = OrderService.requestHash(req("tok_aaaaaaaa", a, b));
        assertThat(OrderService.requestHash(req("tok_aaaaaaaa", b, a))).isEqualTo(h);
        assertThat(OrderService.requestHash(req("tok_aaaaaaaa", a, new CreateOrderRequest.Item("B-1", 3)))).isNotEqualTo(h);
        assertThat(OrderService.requestHash(req("tok_bbbbbbbb", a, b))).isNotEqualTo(h);
        assertThat(h).doesNotContain("tok_");
    }
}
