package com.couponrush.domain.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
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

    protected Coupon() {
    }

    public Coupon(String code, Integer totalQuantity) {
        this.code = code;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public Integer getIssuedQuantity() {
        return issuedQuantity;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
