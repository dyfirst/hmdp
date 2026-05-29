package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Configuration
public class RabbitMQCallbackConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        // 消息到达交换机后触发（不管是否路由到队列）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息投递到交换机失败，id: {}, 原因: {}", correlationData.getId(), cause);
            }
        });

        // 消息无法路由到队列时触发（交换机找不到匹配的队列）
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("消息路由到队列失败，交换机: {}, 路由键: {}, 原因: {}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
    }
}
