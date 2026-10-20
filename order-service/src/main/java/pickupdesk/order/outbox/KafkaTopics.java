package pickupdesk.order.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import pickupdesk.order.OrderProperties;

@Configuration
class KafkaTopics {

    /** 3 partitions keyed by order id: per-order ordering, some consumer parallelism. Replication 1 (single broker). */
    @Bean
    NewTopic orderEvents(OrderProperties props) {
        return TopicBuilder.name(props.topic()).partitions(3).replicas(1).build();
    }
}
