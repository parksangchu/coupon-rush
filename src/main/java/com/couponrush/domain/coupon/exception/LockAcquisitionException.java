package com.couponrush.domain.coupon.exception;

public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException() {
        super("락 획득에 실패했습니다");
    }
}
