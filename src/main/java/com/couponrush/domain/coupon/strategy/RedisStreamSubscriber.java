package com.couponrush.domain.coupon.strategy;

import org.springframework.data.redis.connection.stream.MapRecord;

public interface RedisStreamSubscriber {

    String streamKey();

    String groupName();

    void handle(MapRecord<String, String, String> message);
}
