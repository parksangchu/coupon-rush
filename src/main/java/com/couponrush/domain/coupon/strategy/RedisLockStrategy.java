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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-lock")
@RequiredArgsConstructor
public class RedisLockStrategy implements IssuanceStrategy {

    private final RedissonClient redissonClient;
    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${coupon.redis-lock.wait-time:5000}")
    private long waitTime;

    @Value("${coupon.redis-lock.lease-time:10000}")
    private long leaseTime;

    private static final String LOCK_KEY_PREFIX = "coupon_lock:";

    @Override
    public Issuance issue(Long couponId, Long userId) {
        if (issuanceRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssuanceException();
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + couponId);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new LockAcquisitionException();
            }

            return transactionTemplate.execute(status -> {
                Coupon coupon = couponRepository.findById(couponId)
                    .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
                coupon.issue();
                return issuanceRepository.save(new Issuance(coupon, userId));
            });
        } catch (InterruptedException e) {
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
