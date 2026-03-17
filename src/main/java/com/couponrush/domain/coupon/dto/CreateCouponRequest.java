package com.couponrush.domain.coupon.dto;

public record CreateCouponRequest(
    String code,
    Integer totalQuantity
) {
}
