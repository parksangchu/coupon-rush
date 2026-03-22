package com.couponrush.domain.coupon.strategy;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "coupon.strategy=single-update")
class SingleUpdateStrategyTest extends IssuanceStrategyTestBase {
}
