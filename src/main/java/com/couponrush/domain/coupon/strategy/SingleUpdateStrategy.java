package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "single-update")
@RequiredArgsConstructor
public class SingleUpdateStrategy implements IssuanceStrategy {

    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;

    @Override
    @Transactional
    public void issue(Long couponId, Long userId) {
        if (issuanceRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new DuplicateIssuanceException();
        }

        int updatedRows = couponRepository.incrementIssuedQuantity(couponId);
        if (updatedRows == 0) {
            throw new CouponExhaustedException();
        }

        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));

        issuanceRepository.save(new Issuance(coupon, userId));
    }

    @Override
    public int getIssuedCount(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
        return coupon.getIssuedQuantity();
    }
}
