package com.couponrush.domain.coupon.controller;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.service.CouponService;
import com.couponrush.domain.coupon.dto.CouponResponse;
import com.couponrush.domain.coupon.dto.CouponStatusResponse;
import com.couponrush.domain.coupon.dto.CreateCouponRequest;
import com.couponrush.domain.coupon.dto.IssueRequest;
import com.couponrush.domain.coupon.dto.IssueResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupons")
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

    @PostMapping("/{couponId}/issue")
    public IssueResponse issue(@PathVariable Long couponId, @RequestBody IssueRequest request) {
        return couponService.issue(couponId, request.userId());
    }

    @GetMapping("/{couponId}/status")
    public CouponStatusResponse getStatus(@PathVariable Long couponId) {
        return couponService.getStatus(couponId);
    }
}
