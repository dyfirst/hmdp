package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Component
public class SeckillOrderListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void onMessage(VoucherOrder voucherOrder, Message message, Channel channel) throws IOException {
        voucherOrderService.handleVoucherOrder(voucherOrder);
        // 手动ACK
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
