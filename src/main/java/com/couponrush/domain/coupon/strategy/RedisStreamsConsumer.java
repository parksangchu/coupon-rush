package com.couponrush.domain.coupon.strategy;

import static com.couponrush.domain.coupon.strategy.RedisStreamsStrategy.STREAM_KEY;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-streams")
public class RedisStreamsConsumer {

    @Bean
    public Subscription streamSubscription(
            RedisConnectionFactory connectionFactory,
            CouponRepository couponRepository,
            IssuanceRepository issuanceRepository) {

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
            .builder()
            .pollTimeout(Duration.ofSeconds(1))
            .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
            StreamMessageListenerContainer.create(connectionFactory, options);

        Subscription subscription = container.receive(
            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
            message -> {
                String couponId = message.getValue().get("couponId");
                String userId = message.getValue().get("userId");

                Coupon coupon = couponRepository.findById(Long.parseLong(couponId))
                    .orElseThrow(() -> new IllegalArgumentException(
                        "쿠폰이 존재하지 않습니다: " + couponId));

                issuanceRepository.save(new Issuance(coupon, Long.parseLong(userId)));
                log.debug("Issuance 저장 완료: couponId={}, userId={}", couponId, userId);
            }
        );

        container.start();
        return subscription;
    }
}
