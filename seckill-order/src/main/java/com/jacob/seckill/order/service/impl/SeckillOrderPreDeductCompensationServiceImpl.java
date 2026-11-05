package com.jacob.seckill.order.service.impl;

import cn.hutool.core.util.StrUtil;
import com.jacob.seckill.common.constant.RedisKeyConstants;
import com.jacob.seckill.common.utils.JsonUtils;
import com.jacob.seckill.order.enums.OrderStatusEnum;
import com.jacob.seckill.order.enums.SeckillStockCompensationResultEnum;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;
import com.jacob.seckill.order.model.vo.FindSeckillOrderResultRspVO;
import com.jacob.seckill.order.service.SeckillOrderPreDeductCompensationService;
import com.jacob.seckill.order.service.SeckillOrderResultNotifyService;
import com.jacob.seckill.order.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @Author: jacob
 * @Date: 2026/11/5 09:30
 * @Version: v1.0.0
 * @Description: 秒杀订单预扣回补
 **/
@Service
@Slf4j
public class SeckillOrderPreDeductCompensationServiceImpl implements SeckillOrderPreDeductCompensationService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillOrderResultNotifyService seckillOrderResultNotifyService;

    /**
     * MQ 发布明确失败时，回补 Redis 预扣库存和用户购买标记
     *
     * @param orderNo
     * @param reason
     */
    @Override
    public void compensateWhenPublishFailed(String orderNo, String reason) {
        // 构建回补上下文 Redis Key
        String compensationKey = RedisKeyConstants.buildSeckillOrderCompensationKey(orderNo);
        // 查询 Redis
        String cachedMessage = stringRedisTemplate.opsForValue().get(compensationKey);

        if (StrUtil.isBlank(cachedMessage)) {
            log.warn("==> 未找到待回补上下文，跳过 Redis 回补, orderNo: {}, reason: {}", orderNo, reason);
            return;
        }

        // 将上下文转 Json 换为实体类
        SeckillOrderMqDTO seckillOrderMqDTO = JsonUtils.parseObject(cachedMessage, SeckillOrderMqDTO.class);

        // 执行 Lua 脚本，回补秒杀库存，并删除用户购买标记
        SeckillStockCompensationResultEnum result = seckillStockService.compensatePreDeductStock(
                seckillOrderMqDTO.getActivityId(), seckillOrderMqDTO.getGoodsId(), seckillOrderMqDTO.getUserId(), seckillOrderMqDTO.getOrderNo());

        // 库存回补成功
        if (Objects.equals(result, SeckillStockCompensationResultEnum.SUCCESS)) {
            // 设置订单状态为 “秒杀失败”，并 SSE 推送结果
            saveOrderFailedStatus(seckillOrderMqDTO);

            // 删除 Redis 中的回补上下文
            stringRedisTemplate.delete(compensationKey);
            log.warn("==> MQ 发布失败已完成 Redis 预扣回补, orderNo: {}, reason: {}", orderNo, reason);
            return;
        }

        // 用户购买标记不存在, 或不属于当前订单
        if (Objects.equals(result, SeckillStockCompensationResultEnum.USER_ORDER_MARK_NOT_EXIST)) {
            // 删除 Redis 中的回补上下文
            stringRedisTemplate.delete(compensationKey);
            log.warn("==> Redis 预扣无需重复回补, orderNo: {}, reason: {}", orderNo, reason);
            return;
        }

        log.error("==> Redis 库存 Key 不存在，暂不自动回补, orderNo: {}, reason: {}", orderNo, reason);
    }

    /**
     * 设置订单状态为 “秒杀失败”，并 SSE 推送结果
     *
     * @param seckillOrderMqDTO
     */
    private void saveOrderFailedStatus(SeckillOrderMqDTO seckillOrderMqDTO) {
        // 构建订单状态 Key
        String statusKey = RedisKeyConstants.SECKILL_ORDER_STATUS_PREFIX
                + seckillOrderMqDTO.getUserId() + ":" + seckillOrderMqDTO.getOrderNo();

        // 设置订单状态为秒杀失败
        stringRedisTemplate.opsForValue().set(statusKey,
                String.valueOf(OrderStatusEnum.SECKILL_FAILED.getStatus()),
                RedisKeyConstants.SECKILL_ORDER_STATUS_TTL_MINUTES, TimeUnit.MINUTES);

        // SSE 推送秒杀订单处理结果
        seckillOrderResultNotifyService.notifyOrderResult(seckillOrderMqDTO.getUserId(),
                FindSeckillOrderResultRspVO.builder()
                        .orderNo(seckillOrderMqDTO.getOrderNo())
                        .status(OrderStatusEnum.SECKILL_FAILED.getStatus())
                        .statusDesc(OrderStatusEnum.SECKILL_FAILED.getDescription())
                        .build());
    }
}
