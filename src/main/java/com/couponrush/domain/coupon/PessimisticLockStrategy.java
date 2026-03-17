package com.couponrush.domain.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "pessimistic")
@RequiredArgsConstructor
public class PessimisticLockStrategy implements IssuanceStrategy {

    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;

    @Override
    @Transactional
    public Issuance issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdWithPessimisticLock(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));

        if (issuanceRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssuanceException();
        }

        coupon.issue();

        return issuanceRepository.save(new Issuance(coupon, userId));
    }
}
