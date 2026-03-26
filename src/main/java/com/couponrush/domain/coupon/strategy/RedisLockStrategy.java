package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.exception.LockAcquisitionException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-lock")
@RequiredArgsConstructor
public class RedisLockStrategy implements IssuanceStrategy {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;
    private final TransactionTemplate transactionTemplate;

    private static final String USERS_KEY_PREFIX = "coupon_users:";

    @Value("${coupon.redis-lock.wait-time:5000}")
    private long waitTime;

    @Value("${coupon.redis-lock.lease-time:10000}")
    private long leaseTime;

    private static final String LOCK_KEY_PREFIX = "coupon_lock:";

    @Override
    public void issue(Long couponId, Long userId) {
        Boolean added = redisTemplate.opsForSet().add(USERS_KEY_PREFIX + couponId, String.valueOf(userId)) == 1;
        if (!Boolean.TRUE.equals(added)) {
            throw new DuplicateIssuanceException();
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + couponId);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS);
            if (!acquired) {
                redisTemplate.opsForSet().remove(USERS_KEY_PREFIX + couponId, String.valueOf(userId));
                throw new LockAcquisitionException();
            }

            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Coupon coupon = couponRepository.findById(couponId)
                        .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
                    coupon.issue();
                    issuanceRepository.save(new Issuance(coupon, userId));
                });
            } catch (RuntimeException e) {
                redisTemplate.opsForSet().remove(USERS_KEY_PREFIX + couponId, String.valueOf(userId));
                throw e;
            }
        } catch (InterruptedException e) {
            redisTemplate.opsForSet().remove(USERS_KEY_PREFIX + couponId, String.valueOf(userId));
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public int getIssuedCount(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
        return coupon.getIssuedQuantity();
    }
}
