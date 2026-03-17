package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.entity.Issuance;

public interface IssuanceStrategy {
    Issuance issue(Long couponId, Long userId);
}
