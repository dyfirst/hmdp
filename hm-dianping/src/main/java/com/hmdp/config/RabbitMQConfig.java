package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    public static final String SECKILL_DLX_EXCHANGE = "seckill.order.dlx.exchange";
    public static final String SECKILL_DLX_QUEUE = "seckill.order.dlx.queue";
    public static final String SECKILL_DLX_ROUTING_KEY = "seckill.order.dlx";

    public static final String ERROR_EXCHANGE = "error.exchange";
    public static final String ERROR_QUEUE = "error.queue";
    public static final String ERROR_ROUTING_KEY = "error";

    // ==================== 秒杀订单 ====================

    @Bean
    public DirectExchange seckillOrderExchange() {
        return ExchangeBuilder.directExchange(SECKILL_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE)
                .deadLetterExchange(SECKILL_DLX_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_DLX_ROUTING_KEY)
                .ttl(30 * 60 * 1000)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    // ==================== 死信队列 ====================

    @Bean
    public DirectExchange seckillOrderDlxExchange() {
        return ExchangeBuilder.directExchange(SECKILL_DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue seckillOrderDlxQueue() {
        return QueueBuilder.durable(SECKILL_DLX_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderDlxBinding() {
        return BindingBuilder.bind(seckillOrderDlxQueue())
                .to(seckillOrderDlxExchange())
                .with(SECKILL_DLX_ROUTING_KEY);
    }

    // ==================== 错误消息队列（消费失败兜底） ====================

    @Bean
    public DirectExchange errorExchange() {
        return ExchangeBuilder.directExchange(ERROR_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue errorQueue() {
        return QueueBuilder.durable(ERROR_QUEUE).build();
    }

    @Bean
    public Binding errorBinding() {
        return BindingBuilder.bind(errorQueue())
                .to(errorExchange())
                .with(ERROR_ROUTING_KEY);
    }

    // 消费者重试耗尽后，将失败消息投递到错误队列
    @Bean
    public RepublishMessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, ERROR_EXCHANGE, ERROR_ROUTING_KEY);
    }
}
