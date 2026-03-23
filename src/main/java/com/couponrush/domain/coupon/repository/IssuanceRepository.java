package com.couponrush.domain.coupon.repository;

import com.couponrush.domain.coupon.entity.Issuance;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuanceRepository extends JpaRepository<Issuance, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
}
