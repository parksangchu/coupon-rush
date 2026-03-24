package com.couponrush.domain.coupon.strategy;

import com.couponrush.domain.coupon.dto.IssuanceMessage;
import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "kafka")
@RequiredArgsConstructor
public class IssuanceConsumer {

    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;

    @KafkaListener(topics = "${coupon.kafka.topic}", groupId = "coupon-issuance")
    public void consume(IssuanceMessage message) {
        Coupon coupon = couponRepository.findById(message.couponId())
            .orElseThrow(() -> new IllegalArgumentException(
                "쿠폰이 존재하지 않습니다: " + message.couponId()));

        issuanceRepository.save(new Issuance(coupon, message.userId()));
        log.debug("Issuance 저장 완료: couponId={}, userId={}", message.couponId(), message.userId());
    }
}
