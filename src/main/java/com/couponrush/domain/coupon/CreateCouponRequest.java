package com.couponrush.domain.coupon;

public record CreateCouponRequest(
    String code,
    Integer totalQuantity
) {
}
