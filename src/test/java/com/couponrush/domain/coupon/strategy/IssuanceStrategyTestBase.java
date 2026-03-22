package com.couponrush.domain.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.couponrush.IntegrationTestBase;
import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.exception.CouponExhaustedException;
import com.couponrush.domain.coupon.exception.DuplicateIssuanceException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

abstract class IssuanceStrategyTestBase extends IntegrationTestBase {

    @Autowired
    protected CouponRepository couponRepository;

    @Autowired
    protected IssuanceStrategy issuanceStrategy;

    @Test
    @DisplayName("100명이 동시에 발급 요청해도 총 수량을 초과하지 않는다")
    void concurrentIssueDoesNotExceedTotalQuantity() throws InterruptedException {
        int totalQuantity = 100;
        int threadCount = 150;

        Coupon coupon = couponRepository.save(new Coupon("CONCURRENT-TEST", totalQuantity));

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executor.submit(() -> {
                try {
                    issuanceStrategy.issue(coupon.getId(), userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Coupon result = couponRepository.findById(coupon.getId()).orElseThrow();

        assertThat(result.getIssuedQuantity()).isEqualTo(totalQuantity);
        assertThat(successCount.get()).isEqualTo(totalQuantity);
        assertThat(failCount.get()).isEqualTo(threadCount - totalQuantity);
    }

    @Test
    @DisplayName("같은 사용자가 중복 발급을 요청하면 예외가 발생한다")
    void duplicateIssuanceThrowsException() {
        Coupon coupon = couponRepository.save(new Coupon("DUPLICATE-TEST", 10));

        issuanceStrategy.issue(coupon.getId(), 1L);

        assertThatThrownBy(() -> issuanceStrategy.issue(coupon.getId(), 1L))
            .isInstanceOf(DuplicateIssuanceException.class);
    }

    @Test
    @DisplayName("수량이 소진된 쿠폰에 발급 요청하면 예외가 발생한다")
    void exhaustedCouponThrowsException() {
        Coupon coupon = couponRepository.save(new Coupon("EXHAUST-TEST", 1));

        issuanceStrategy.issue(coupon.getId(), 1L);

        assertThatThrownBy(() -> issuanceStrategy.issue(coupon.getId(), 2L))
            .isInstanceOf(CouponExhaustedException.class);
    }
}
