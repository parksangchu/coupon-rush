package com.couponrush.api.coupon;

import com.couponrush.domain.coupon.Coupon;
import java.time.LocalDateTime;

public record CouponResponse(
    Long id,
    String code,
    Integer totalQuantity,
    Integer issuedQuantity,
    LocalDateTime createdAt
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
            coupon.getId(),
            coupon.getCode(),
            coupon.getTotalQuantity(),
            coupon.getIssuedQuantity(),
            coupon.getCreatedAt()
        );
    }
}
