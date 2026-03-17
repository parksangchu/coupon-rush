package com.couponrush.domain.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false)
    private Integer totalQuantity;

    @Column(nullable = false)
    private Integer issuedQuantity;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Coupon(String code, Integer totalQuantity) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("쿠폰 코드는 필수입니다");
        }
        if (totalQuantity == null || totalQuantity <= 0) {
            throw new IllegalArgumentException("총 수량은 1 이상이어야 합니다");
        }
        this.code = code;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.createdAt = LocalDateTime.now();
    }

    public void issue() {
        if (this.issuedQuantity >= this.totalQuantity) {
            throw new CouponExhaustedException();
        }
        this.issuedQuantity++;
    }

    public int remainingQuantity() {
        return this.totalQuantity - this.issuedQuantity;
    }
}
