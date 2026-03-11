package com.couponrush.domain.coupon;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CouponResponse create(@RequestBody CreateCouponRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        Coupon saved = couponService.create(request.code(), request.totalQuantity());
        return CouponResponse.from(saved);
    }

    @GetMapping
    public List<CouponResponse> findAll() {
        return couponService.findAll().stream()
            .map(CouponResponse::from)
            .toList();
    }
}
