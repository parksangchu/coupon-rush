package com.couponrush.api.coupon;

public record CreateCouponRequest(
    String code,
    Integer totalQuantity
) {
}
