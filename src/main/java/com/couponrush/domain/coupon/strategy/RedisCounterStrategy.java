package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Override
    @Transactional
    public Issuance issue(Long couponId, Long userId) {
        if (issuanceRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssuanceException();
        }

        Long issued = redisTemplate.opsForValue().increment(COUNTER_KEY_PREFIX + couponId);

        int totalQuantity = getTotalQuantity(couponId);
        if (issued > totalQuantity) {
            redisTemplate.opsForValue().decrement(COUNTER_KEY_PREFIX + couponId);
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

    private int getTotalQuantity(Long couponId) {
        String key = TOTAL_KEY_PREFIX + couponId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Integer.parseInt(cached);
        }

        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(coupon.getTotalQuantity()));
        return coupon.getTotalQuantity();
    }
}
