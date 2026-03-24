package com.couponrush.domain.coupon.repository;

import com.couponrush.domain.coupon.entity.Issuance;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssuanceRepository extends JpaRepository<Issuance, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    long countByCouponId(Long couponId);

    @Query(
        "SELECT COUNT(i) FROM Issuance i WHERE i.coupon.id = :couponId " +
        "GROUP BY i.coupon.id, i.userId HAVING COUNT(i) > 1")
    List<Long> findDuplicateCounts(@Param("couponId") Long couponId);
}
