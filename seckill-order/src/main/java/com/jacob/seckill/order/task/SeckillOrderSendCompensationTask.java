package com.jacob.seckill.order.task;

import cn.hutool.core.collection.CollUtil;
import com.jacob.seckill.common.domain.dataobject.SeckillOrderSendCompensationDO;
import com.jacob.seckill.common.domain.mapper.SeckillOrderSendCompensationDOMapper;
import com.jacob.seckill.common.utils.JsonUtils;
import com.jacob.seckill.order.enums.SendCompensationStatusEnum;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;
import com.jacob.seckill.order.mq.SeckillOrderMessageSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author: jacob
 * @Date: 2026/11/7 23:20
 * @Version: v1.0.0
 * @Description: 秒杀订单 MQ 发送失败补偿任务: 定时扫描补偿表，将发送失败的消息重新投递
 **/
@Component
@Slf4j
public class SeckillOrderSendCompensationTask {

    /**
     * 每轮最多捞起的补偿记录数，避免单轮处理数据量太大
     */
    private static final int BATCH_SIZE = 100;

    @Resource
    private SeckillOrderSendCompensationDOMapper sendCompensationDOMapper;

    @Resource
    private SeckillOrderMessageSender seckillOrderMessageSender;

    /**
     * 补偿重发：每 10 秒执行一次
     *
     * 注意，使用 fixedDelay（上一轮执行结束后再间隔计时），避免上一轮还没扫完、下一轮又启动导致任务重叠了
     */
    @Scheduled(fixedDelay = 10_000) // 任务完成后，等待 10 秒执行下一轮
    public void resendFailedMessages() {
        // 1. 捞取到达重试时间的待重试记录
        List<SeckillOrderSendCompensationDO> pendingList =
                sendCompensationDOMapper.selectPendingList(LocalDateTime.now(), BATCH_SIZE);

        // 没有待重发的记录，直接 return，本轮扫描结束
        if (CollUtil.isEmpty(pendingList)) {
            return;
        }

        log.info("==> 发送失败补偿任务启动, 本轮待重发 {} 条", pendingList.size());

        // 2. 逐条重发
        for (SeckillOrderSendCompensationDO compensation : pendingList) {
            // 当前订单号
            String orderNo = compensation.getOrderNo();

            try {
                // 从消息体 JSON 还原出完整的下单消息 DTO
                SeckillOrderMqDTO message = JsonUtils.parseObject(compensation.getMessageBody(), SeckillOrderMqDTO.class);

                // 重新发送
                boolean isSendSuccess = seckillOrderMessageSender.send(message);

                if (isSendSuccess) {
                    // 发送请求已提交，标记为 "已补偿"；最终是否到达交换机仍由 Confirm 机制异步确认
                    sendCompensationDOMapper.updateStatusByOrderNo(orderNo, SendCompensationStatusEnum.COMPENSATED.getStatus());

                    log.info("==> 发送失败补偿重发成功, orderNo: {}", orderNo);
                }

                // 如果发送失败，已由 @Recover 统一登记，这里无需额外处理
            } catch (Exception e) {
                // 单条重发异常，等待下一轮重试或人工介入
                log.error("==> 发送失败补偿重发异常, orderNo: {}", orderNo, e);
            }
        }
    }
}
