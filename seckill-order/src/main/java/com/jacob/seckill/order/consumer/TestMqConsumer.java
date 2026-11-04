package com.jacob.seckill.order.consumer;

import com.jacob.seckill.common.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * @Author: jacob
 * @Date: 2026/11/4 01:20
 * @Version: v1.0.0
 * @Description: 测试消费者
 **/
@Component
@Slf4j
public class TestMqConsumer {

    /**
     * 监听测试队列，收到消息后打印日志
     *
     * @param message
     */
    // 全局已改为手动 ACK，测试消费者没有 ack 逻辑，单独保持自动模式
    @RabbitListener(queues = RabbitMQConfig.TEST_QUEUE, ackMode = "AUTO")
    public void consume(String message) {
        log.info("## 收到测试消息: {}", message);
    }
}
