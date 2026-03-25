package com.couponrush.domain.coupon.strategy;

public interface IssuanceStrategy {
    void issue(Long couponId, Long userId);
    int getIssuedCount(Long couponId);
}
