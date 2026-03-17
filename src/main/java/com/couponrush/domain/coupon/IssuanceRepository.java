package com.couponrush.domain.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuanceRepository extends JpaRepository<Issuance, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
}
