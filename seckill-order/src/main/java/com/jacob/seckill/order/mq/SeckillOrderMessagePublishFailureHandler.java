package com.jacob.seckill.order.mq;

import com.jacob.seckill.common.mq.MessagePublishFailureHandler;
import com.jacob.seckill.order.service.SeckillOrderPreDeductCompensationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author: jacob
 * @Date: 2026/11/4 14:20
 * @Version: v1.0.0
 * @Description: 秒杀订单消息发布失败处理器
 **/
@Component
@Slf4j
public class SeckillOrderMessagePublishFailureHandler implements MessagePublishFailureHandler {

    @Resource
    private SeckillOrderPreDeductCompensationService seckillOrderPreDeductCompensationService;

    /**
     * Broker 返回 Nack，表示 Broker 未能成功处理这条要发布的消息
     */
    @Override
    public void handleConfirmNack(String messageId, String cause) {
        // 在这里执行 Redis 预扣回补
        seckillOrderPreDeductCompensationService.compensateWhenPublishFailed(messageId,
                "Confirm Nack: " + cause);
    }

    /**
     * 消息到达交换机，但未路由到队列中
     */
    @Override
    public void handleReturned(String messageId, String exchange, String routingKey) {
        // 在这里执行 Redis 预扣回补
        seckillOrderPreDeductCompensationService.compensateWhenPublishFailed(messageId,
                "Return: exchange=" + exchange + ", routingKey=" + routingKey);
    }
}
