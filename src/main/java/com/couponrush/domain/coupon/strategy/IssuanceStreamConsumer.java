package com.couponrush.domain.coupon.strategy;

import static com.couponrush.domain.coupon.strategy.RedisStreamsStrategy.STREAM_KEY;

import com.couponrush.domain.coupon.entity.Coupon;
import com.couponrush.domain.coupon.entity.Issuance;
import com.couponrush.domain.coupon.repository.CouponRepository;
import com.couponrush.domain.coupon.repository.IssuanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-streams")
@RequiredArgsConstructor
public class IssuanceStreamConsumer implements RedisStreamSubscriber {

    private final CouponRepository couponRepository;
    private final IssuanceRepository issuanceRepository;

    @Override
    public String streamKey() {
        return STREAM_KEY;
    }

    @Override
    public String groupName() {
        return "coupon-issuance";
    }

    @Override
    public void handle(MapRecord<String, String, String> message) {
        String couponId = message.getValue().get("couponId");
        String userId = message.getValue().get("userId");

        Coupon coupon = couponRepository.findById(Long.parseLong(couponId))
            .orElseThrow(() -> new IllegalArgumentException(
                "쿠폰이 존재하지 않습니다: " + couponId));

        issuanceRepository.save(new Issuance(coupon, Long.parseLong(userId)));
        log.debug("Issuance 저장 완료: couponId={}, userId={}", couponId, userId);
    }
}
