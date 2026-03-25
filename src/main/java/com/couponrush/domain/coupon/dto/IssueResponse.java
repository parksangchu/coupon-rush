package com.couponrush.domain.coupon.dto;

import com.couponrush.domain.coupon.entity.Issuance;

public record IssueResponse(Long issuanceId, String couponCode) {

    public static IssueResponse from(Issuance issuance) {
        return new IssueResponse(issuance.getId(), issuance.getCoupon().getCode());
    }
}
