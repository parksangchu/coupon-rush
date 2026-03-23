package com.couponrush.domain.coupon.service;

import com.couponrush.domain.coupon.dto.CouponStatusResponse;
import com.couponrush.domain.coupon.dto.IssueResponse;
import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.strategy.IssuanceStrategy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final IssuanceStrategy issuanceStrategy;

    public Coupon create(String code, Integer totalQuantity) {
        if (couponRepository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("이미 존재하는 쿠폰 코드입니다: " + code);
        }

        return couponRepository.save(new Coupon(code, totalQuantity));
    }

    public List<Coupon> findAll() {
        return couponRepository.findAll();
    }

    public IssueResponse issue(Long couponId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다");
        }
        Issuance issuance = issuanceStrategy.issue(couponId, userId);
        return IssueResponse.from(issuance);
    }

    @Transactional(readOnly = true)
    public CouponStatusResponse getStatus(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("쿠폰이 존재하지 않습니다: " + couponId));
        return CouponStatusResponse.of(coupon, issuanceStrategy.getIssuedCount(couponId));
    }
}
