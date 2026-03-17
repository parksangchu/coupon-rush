package com.couponrush.domain.coupon;

public class DuplicateIssuanceException extends RuntimeException {

    public DuplicateIssuanceException() {
        super("이미 발급받은 쿠폰입니다");
    }
}
