package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
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
public class SeckillOrderDlxListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_DLX_QUEUE)
    public void onMessage(VoucherOrder voucherOrder, Message message, Channel channel) throws IOException {
        try {
            // 订单超时未支付，检查是否已下单
            Long orderId = voucherOrder.getId();
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                // 订单不存在，说明已被消费或取消，直接确认
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            // TODO: 这里可以检查订单状态，如果是未支付则取消订单、恢复库存
            // 当前简化处理：直接确认消息
            log.info("秒杀订单超时未支付，订单号：{}", orderId);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理死信消息异常", e);
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }
}
