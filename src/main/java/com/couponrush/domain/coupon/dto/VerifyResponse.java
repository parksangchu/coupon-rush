package com.couponrush.domain.coupon.dto;

public record VerifyResponse(
    int strategyCount,
    long dbCount,
    int duplicateCount,
    boolean consistent
) {
}
