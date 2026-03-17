package com.couponrush.domain.coupon.dto;

import com.couponrush.domain.coupon.entity.Coupon;

public record CouponStatusResponse(int total, int issued, int remaining) {

    public static CouponStatusResponse from(Coupon coupon) {
        return new CouponStatusResponse(
            coupon.getTotalQuantity(),
            coupon.getIssuedQuantity(),
            coupon.remainingQuantity()
        );
    }
}
