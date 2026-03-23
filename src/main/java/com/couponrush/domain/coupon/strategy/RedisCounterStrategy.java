package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-counter")
@RequiredArgsConstructor
public class RedisCounterStrategy implements IssuanceStrategy {

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;

    private static final String COUNTER_KEY_PREFIX = "coupon_counter:";
    private static final String TOTAL_KEY_PREFIX = "coupon_total:";

    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            local total = tonumber(redis.call('GET', KEYS[2]) or '0')
            if current > total then
                redis.call('DECR', KEYS[1])
                return -1
            end
            return current
            """, Long.class);

    @Override
    @Transactional
    public Issuance issue(Long couponId, Long userId) {
        if (issuanceRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssuanceException();
        }

        initTotalQuantity(couponId);

        Long result = redisTemplate.execute(
            ISSUE_SCRIPT,
            List.of(COUNTER_KEY_PREFIX + couponId, TOTAL_KEY_PREFIX + couponId)
        );

        if (result == null || result == -1) {
            throw new CouponExhaustedException();
        }

        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));

        return issuanceRepository.save(new Issuance(coupon, userId));
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
