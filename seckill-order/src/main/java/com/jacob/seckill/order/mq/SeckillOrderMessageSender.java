package com.jacob.seckill.order.mq;

import com.jacob.seckill.common.config.RabbitMQConfig;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * @Author: jacob
 * @Date: 2026/11/4 10:50
 * @Version: v1.0.0
 * @Description: 秒杀订单消息发送器
 **/
@Component
@Slf4j
public class SeckillOrderMessageSender {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送秒杀下单消息。
     *
     * @param message 秒杀下单消息体
     */
    public void send(SeckillOrderMqDTO message) {
        // 使用订单号作为关联 ID，方便 ConfirmCallback 中定位是哪一笔订单消息发送失败
        CorrelationData correlationData = new CorrelationData(message.getOrderNo());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_EXCHANGE,
                RabbitMQConfig.SECKILL_ROUTING_KEY,
                message,
                correlationData
        );

        log.info("==> 秒杀下单消息发送请求已提交, orderNo: {}, userId: {}, activityId: {}, goodsId: {}",
                message.getOrderNo(), message.getUserId(), message.getActivityId(), message.getGoodsId());
    }
}
