package com.couponrush.domain.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.couponrush.domain.coupon.repository.IssuanceRepository;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

@TestPropertySource(properties = "coupon.strategy=kafka")
class KafkaStrategyTest extends IssuanceStrategyTestBase {

    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
        .withExposedPorts(6379);

    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    static {
        redis.start();
        kafka.start();
    }

    @Autowired
    private IssuanceRepository issuanceRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("coupon.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Test
    @DisplayName("Kafka Consumer가 비동기로 DB에 Issuance를 저장한다")
    void consumerSavesIssuanceToDb() {
        var coupon = couponRepository.save(
            new com.couponrush.domain.coupon.entity.Coupon("ASYNC-DB-TEST", 10));

        issuanceStrategy.issue(coupon.getId(), 100L);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() ->
                assertThat(issuanceRepository.countByCouponId(coupon.getId())).isEqualTo(1));
    }
}
