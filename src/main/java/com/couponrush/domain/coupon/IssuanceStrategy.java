package com.couponrush.domain.coupon;

public interface IssuanceStrategy {
    Issuance issue(Long couponId, Long userId);
}
