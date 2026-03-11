package com.couponrush.domain.coupon;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    public Coupon create(String code, Integer totalQuantity) {
        validate(code, totalQuantity);

        if (couponRepository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("coupon code already exists: " + code);
        }

        return couponRepository.save(new Coupon(code, totalQuantity));
    }

    public List<Coupon> findAll() {
        return couponRepository.findAll();
    }

    private void validate(String code, Integer totalQuantity) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (totalQuantity == null || totalQuantity <= 0) {
            throw new IllegalArgumentException("totalQuantity must be greater than 0");
        }
    }
}
