package com.couponrush.global;

import com.couponrush.domain.coupon.strategy.RedisStreamSubscriber;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "redis-streams")
public class RedisStreamsConfig {

    @Value("${coupon.redis-streams.consumer-name:consumer-1}")
    private String consumerName;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            List<RedisStreamSubscriber> subscribers) {

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
            .builder()
            .pollTimeout(Duration.ofSeconds(1))
            .build();

        var container = StreamMessageListenerContainer.create(connectionFactory, options);

        for (var subscriber : subscribers) {
            initConsumerGroup(redisTemplate, subscriber.streamKey(), subscriber.groupName());

            container.receive(
                Consumer.from(subscriber.groupName(), consumerName),
                StreamOffset.create(subscriber.streamKey(), ReadOffset.lastConsumed()),
                message -> {
                    try {
                        subscriber.handle(message);
                        redisTemplate.opsForStream().acknowledge(
                            subscriber.streamKey(), subscriber.groupName(), message.getId());
                    } catch (Exception e) {
                        log.error("메시지 처리 실패: streamKey={}, messageId={}",
                            subscriber.streamKey(), message.getId(), e);
                    }
                }
            );
        }

        container.start();
        return container;
    }

    private void initConsumerGroup(StringRedisTemplate redisTemplate, String streamKey, String groupName) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
        } catch (Exception e) {
            // 그룹이 이미 존재하거나 스트림이 아직 없는 경우 무시
        }
    }
}
