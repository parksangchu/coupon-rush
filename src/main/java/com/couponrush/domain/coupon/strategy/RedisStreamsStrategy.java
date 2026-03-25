package com.couponrush.domain.coupon.strategy;

import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.COUNTER_KEY_PREFIX;
import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.TOTAL_KEY_PREFIX;
import static com.couponrush.domain.coupon.strategy.RedisCounterStrategy.USERS_KEY_PREFIX;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-streams")
@RequiredArgsConstructor
public class RedisStreamsStrategy implements IssuanceStrategy {

    static final String STREAM_KEY = "coupon_stream";

    /**
     * SADD + INCR + total 체크 + XADD를 원자 실행.
     * Kafka 전략의 dual-write gap이 원천 제거된다.
     *
     * Keys: counter_key, total_key, users_key, stream_key
     * Args: userId, couponId
     * Returns: current count (성공), -1 (소진), -2 (중복)
     */
    static final DefaultRedisScript<Long> ISSUE_AND_PUBLISH_SCRIPT = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[3], ARGV[1])
            if added == 0 then return -2 end
            local current = redis.call('INCR', KEYS[1])
            local total = tonumber(redis.call('GET', KEYS[2]) or '0')
            if current > total then
                redis.call('DECR', KEYS[1])
                redis.call('SREM', KEYS[3], ARGV[1])
                return -1
            end
            redis.call('XADD', KEYS[4], '*', 'couponId', ARGV[2], 'userId', ARGV[1])
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;

    @Override
    public Issuance issue(Long couponId, Long userId) {
        initTotalQuantity(couponId);

        Long result = redisTemplate.execute(
            ISSUE_AND_PUBLISH_SCRIPT,
            List.of(
                COUNTER_KEY_PREFIX + couponId,
                TOTAL_KEY_PREFIX + couponId,
                USERS_KEY_PREFIX + couponId,
                STREAM_KEY
            ),
            String.valueOf(userId),
            String.valueOf(couponId)
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
