package com.couponrush.global;

import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import com.couponrush.domain.coupon.exception.LockAcquisitionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException ex) {
        return Map.of("message", ex.getMessage());
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleCouponExhausted(CouponExhaustedException ex) {
        return Map.of("message", ex.getMessage());
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDuplicateIssuance(DuplicateIssuanceException ex) {
        return Map.of("message", ex.getMessage());
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleLockAcquisition(LockAcquisitionException ex) {
        return Map.of("message", ex.getMessage());
    }
}
