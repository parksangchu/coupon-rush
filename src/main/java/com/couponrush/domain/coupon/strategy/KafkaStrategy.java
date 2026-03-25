package com.couponrush.domain.coupon.strategy;

import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.COUNTER_KEY_PREFIX;
import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.ISSUE_SCRIPT;
import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.TOTAL_KEY_PREFIX;
import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.USERS_KEY_PREFIX;

import com.couponrush.domain.coupon.dto.IssuanceMessage;
import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "kafka")
@RequiredArgsConstructor
public class KafkaStrategy implements IssuanceStrategy {

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final KafkaTemplate<String, IssuanceMessage> kafkaTemplate;

    @Value("${coupon.kafka.topic}")
    private String topic;

    @Override
    public Issuance issue(Long couponId, Long userId) {
        initTotalQuantity(couponId);

        Long result = redisTemplate.execute(
            ISSUE_SCRIPT,
            List.of(
                COUNTER_KEY_PREFIX + couponId,
                TOTAL_KEY_PREFIX + couponId,
                USERS_KEY_PREFIX + couponId
            ),
            String.valueOf(userId)
        );

        if (result == null) {
            throw new RuntimeException("Redis Lua 스크립트 실행 실패");
        }
        if (result == -2) {
            throw new DuplicateIssuanceException();
        }
        if (result == -1) {
            throw new CouponExhaustedException();
        }

        try {
            kafkaTemplate.send(topic, String.valueOf(userId), new IssuanceMessage(couponId, userId)).get();
        } catch (Exception e) {
            // 보상: DECR + SREM
            redisTemplate.opsForValue().decrement(COUNTER_KEY_PREFIX + couponId);
            redisTemplate.opsForSet().remove(USERS_KEY_PREFIX + couponId, String.valueOf(userId));
            throw new RuntimeException("Kafka 발행 실패, 보상 완료", e);
        }

        return null;
    }

    @Override
    public int getIssuedCount(Long couponId) {
        String value = redisTemplate.opsForValue().get(COUNTER_KEY_PREFIX + couponId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private void initTotalQuantity(Long couponId) {
        String key = TOTAL_KEY_PREFIX + couponId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return;
        }
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(coupon.getTotalQuantity()));
    }
}
