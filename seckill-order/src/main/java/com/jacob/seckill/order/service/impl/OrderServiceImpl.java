package com.jacob.seckill.order.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jacob.seckill.common.constant.RedisKeyConstants;
import com.jacob.seckill.common.domain.dataobject.SeckillOrderDO;
import com.jacob.seckill.common.domain.mapper.SeckillGoodsDOMapper;
import com.jacob.seckill.common.domain.mapper.SeckillOrderDOMapper;
import com.jacob.seckill.common.enums.ResponseCodeEnum;
import com.jacob.seckill.common.exception.BizException;
import com.jacob.seckill.common.model.dto.SeckillActivityGoodsMetaDTO;
import com.jacob.seckill.common.utils.DateTimeUtils;
import com.jacob.seckill.common.utils.JsonUtils;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.order.enums.OrderStatusEnum;
import com.jacob.seckill.order.enums.SeckillStockDeductResultEnum;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;
import com.jacob.seckill.order.mq.SeckillOrderMessageSender;
import com.jacob.seckill.order.model.vo.DoSeckillReqVO;
import com.jacob.seckill.order.model.vo.DoSeckillRspVO;
import com.jacob.seckill.order.model.vo.FindSeckillOrderResultReqVO;
import com.jacob.seckill.order.model.vo.FindSeckillOrderResultRspVO;
import com.jacob.seckill.order.model.vo.GetSeckillTokenReqVO;
import com.jacob.seckill.order.model.vo.GetSeckillTokenRspVO;
import com.jacob.seckill.order.service.OrderService;
import com.jacob.seckill.order.service.SeckillOrderResultNotifyService;
import com.jacob.seckill.order.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:52
 * @Version: v1.0.0
 * @Description: 订单模块业务实现
 **/
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /**
     * 秒杀令牌随机源，SecureRandom 线程安全
     */
    private static final SecureRandom SECKILL_TOKEN_RANDOM = new SecureRandom();

    @Resource
    private SeckillGoodsDOMapper seckillGoodsDOMapper;

    @Resource
    private SeckillOrderDOMapper seckillOrderDOMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private SeckillOrderMessageSender seckillOrderMessageSender;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderResultNotifyService seckillOrderResultNotifyService;

    /**
     * 秒杀下单
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<DoSeckillRspVO> doSeckill(DoSeckillReqVO reqVO) {
        // 活动 ID
        Long activityId = reqVO.getActivityId();
        // 商品 ID
        Long goodsId = reqVO.getGoodsId();

        // 1. 获取当前登录用户 ID
        long userId = StpUtil.getLoginIdAsLong();

        // 1.1 校验秒杀令牌，拦截没有经过令牌接口的请求
        checkSeckillToken(activityId, goodsId, userId, reqVO.getToken());

        // 2. 从 Redis 读取秒杀活动商品元数据，不再查数据库
        String metaKey = RedisKeyConstants.buildSeckillActivityGoodsMetaKey(activityId, goodsId);
        String metaJson = stringRedisTemplate.opsForValue().get(metaKey);
        if (StrUtil.isBlank(metaJson)) {
            log.warn("==> 秒杀活动商品元数据未预热, key: {}", metaKey);
            throw new BizException(ResponseCodeEnum.SECKILL_GOODS_NOT_EXIST);
        }

        SeckillActivityGoodsMetaDTO metaDTO = JsonUtils.parseObject(metaJson, SeckillActivityGoodsMetaDTO.class);
        if (Objects.isNull(metaDTO) || Objects.isNull(metaDTO.getBeginTime()) || Objects.isNull(metaDTO.getEndTime())) {
            throw new IllegalStateException("秒杀活动商品元数据解析失败, key: " + metaKey);
        }

        // 3. 用户购买标记的 TTL 覆盖整个活动周期
        Long userOrderTtlSeconds = RedisKeyConstants.calculateTtlSeconds(metaDTO.getEndTime());
        if (Objects.isNull(userOrderTtlSeconds) || userOrderTtlSeconds <= 0) {
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_ENDED);
        }

        // 4. 由于业务需要，避免售罄标记抢在活动状态校验、一人一单之前返回，不然提示信息对用户不友好
        LocalDateTime now = LocalDateTime.now();

        // 校验活动开始、结束时间
        if (now.isBefore(metaDTO.getBeginTime())) {
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_STARTED);
        }

        if (!now.isBefore(metaDTO.getEndTime())) {
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_ENDED);
        }

        // 如果用户已经参与过本场秒杀，优先返回重复参与，而不是被售罄标记拦截
        String userOrderKey = RedisKeyConstants.buildSeckillUserOrderKey(activityId, goodsId, userId);
        if (stringRedisTemplate.hasKey(userOrderKey)) {
            throw new BizException(ResponseCodeEnum.SECKILL_ORDER_DUPLICATE);
        }

        // 秒杀商品已经售罄时，直接快速失败，不再继续生成 orderNo 和执行 Lua 脚本
        String soldOutKey = RedisKeyConstants.buildSeckillSoldOutKey(activityId, goodsId);
        if (stringRedisTemplate.hasKey(soldOutKey)) {
            log.info("==> 秒杀商品已售罄，命中售罄标记快速失败, key: {}", soldOutKey);
            throw new BizException(ResponseCodeEnum.SECKILL_GOODS_SOLD_OUT);
        }

        // 5. 使用 Hutool 提供的工具方法，通过雪花算法生成订单号
        String orderNo = IdUtil.getSnowflakeNextIdStr();

        // 6. 构建消息体，预扣成功时作为待回补上下文一起写入 Redis
        SeckillOrderMqDTO seckillOrderMqDTO = SeckillOrderMqDTO.builder()
                .userId(userId)
                .activityId(activityId)
                .seckillGoodsId(metaDTO.getSeckillGoodsId())
                .goodsName(metaDTO.getGoodsName())
                .goodsImg(metaDTO.getGoodsImg())
                .seckillPrice(metaDTO.getSeckillPrice())
                .goodsId(goodsId)
                .orderNo(orderNo)
                .requestTime(now)
                .build();

        // 7. Lua 原子校验活动时间、重复参与并预扣库存
        SeckillStockDeductResultEnum deductResult = seckillStockService.preDeductStock(
                seckillOrderMqDTO, userOrderTtlSeconds,
                DateTimeUtils.toEpochMilli(metaDTO.getBeginTime()),
                DateTimeUtils.toEpochMilli(metaDTO.getEndTime()));

        switch (deductResult) {
            case SUCCESS -> {
            }
            case ACTIVITY_NOT_STARTED -> throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_STARTED);
            case ACTIVITY_ENDED -> throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_ENDED);
            case SOLD_OUT -> throw new BizException(ResponseCodeEnum.SECKILL_GOODS_SOLD_OUT);
            case REPEATED_ORDER -> throw new BizException(ResponseCodeEnum.SECKILL_ORDER_DUPLICATE);
            case STOCK_NOT_PREHEATED -> throw new BizException(ResponseCodeEnum.SECKILL_GOODS_NOT_EXIST);
        }

        // 8. 先记录处理中状态，再发消息，避免消费者处理比状态写入更快导致状态被回退
        saveOrderStatus(userId, orderNo, OrderStatusEnum.PROCESSING.getStatus());

        // 9. 发送 MQ，内部会携带 CorrelationData(orderNo)，方便生产者确认回调定位消息
        boolean isSendSuccess = seckillOrderMessageSender.send(seckillOrderMqDTO);

        if (!isSendSuccess) {
            log.warn("==> 秒杀下单消息发送结果未知，订单保持处理中等待后续确认, orderNo: {}", orderNo);
        }

        log.info("==> 秒杀下单消息已发送至 MQ, orderNo: {}, userId: {}, activityId: {}, goodsId: {}",
                orderNo, userId, activityId, goodsId);

        // 立即响应 "处理中"，扣库存 + 建订单交给消费者异步处理
        return Response.success(
                DoSeckillRspVO.builder()
                        .orderNo(orderNo)
                        .status(OrderStatusEnum.PROCESSING.getStatus())
                        .build()
        );
    }

    /**
     * 获取秒杀下单令牌
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<GetSeckillTokenRspVO> getSeckillToken(GetSeckillTokenReqVO reqVO) {
        // 活动 ID
        Long activityId = reqVO.getActivityId();
        // 商品 ID
        Long goodsId = reqVO.getGoodsId();

        // 1. 获取当前登录用户 ID，令牌按用户维度隔离
        long userId = StpUtil.getLoginIdAsLong();

        // 2. 从 Redis 读取秒杀活动商品元数据
        String metaKey = RedisKeyConstants.buildSeckillActivityGoodsMetaKey(activityId, goodsId);
        String metaJson = stringRedisTemplate.opsForValue().get(metaKey);
        if (StrUtil.isBlank(metaJson)) {
            log.warn("==> 秒杀活动商品元数据未预热, key: {}", metaKey);
            throw new IllegalStateException("秒杀下单入口元数据未预热，请先预热活动");
        }

        SeckillActivityGoodsMetaDTO metaDTO = JsonUtils.parseObject(metaJson, SeckillActivityGoodsMetaDTO.class);
        if (Objects.isNull(metaDTO) || Objects.isNull(metaDTO.getBeginTime()) || Objects.isNull(metaDTO.getEndTime())) {
            throw new IllegalStateException("秒杀活动商品元数据解析失败, key: " + metaKey);
        }

        // 3. 未开始或已结束的活动，不签发令牌
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(metaDTO.getBeginTime())) {
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_STARTED);
        }

        if (!now.isBefore(metaDTO.getEndTime())) {
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_ENDED);
        }

        // 4. 生成令牌并写入 Redis，同一用户重复获取会覆盖旧令牌
        String token = generateSeckillToken();
        String tokenKey = RedisKeyConstants.buildSeckillOrderTokenKey(activityId, goodsId, userId);
        stringRedisTemplate.opsForValue().set(tokenKey, token,
                RedisKeyConstants.SECKILL_ORDER_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        log.info("==> 秒杀令牌签发成功, userId: {}, activityId: {}, goodsId: {}", userId, activityId, goodsId);

        return Response.success(
                GetSeckillTokenRspVO.builder()
                        .token(token)
                        .expireSeconds(RedisKeyConstants.SECKILL_ORDER_TOKEN_TTL_SECONDS)
                        .build()
        );
    }

    /**
     * 生成秒杀令牌：32 字节随机数，Base64 URL 安全编码
     *
     * @return
     */
    private String generateSeckillToken() {
        byte[] bytes = new byte[32];
        SECKILL_TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 校验秒杀令牌
     *
     * @param activityId
     * @param goodsId
     * @param userId
     * @param token
     */
    private void checkSeckillToken(Long activityId, Long goodsId, Long userId, String token) {
        String tokenKey = RedisKeyConstants.buildSeckillOrderTokenKey(activityId, goodsId, userId);
        String cachedToken = stringRedisTemplate.opsForValue().get(tokenKey);

        // 令牌不存在（已过期）或与缓存不一致，都视为无效
        if (StrUtil.isBlank(cachedToken) || !cachedToken.equals(token)) {
            throw new BizException(ResponseCodeEnum.SECKILL_TOKEN_INVALID);
        }
    }

    /**
     * 异步消费秒杀下单消息：扣减库存 + 创建订单
     *
     * @param message
     */
    @Override
    public void createSeckillOrder(SeckillOrderMqDTO message) {
        // 记录消费开始时间
        long startTime = System.currentTimeMillis();

        Long activityId = message.getActivityId();
        Long goodsId = message.getGoodsId();
        Long userId = message.getUserId();
        String orderNo = message.getOrderNo();

        log.info("==> 消费秒杀下单消息, orderNo: {}, userId: {}, activityId: {}, goodsId: {}",
                orderNo, userId, activityId, goodsId);

        // 订单过期时间：当前时间 + 30 分钟
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);

        // 编程式事务，精确控制事务边界
        try {
            SeckillOrderDO orderDO = transactionTemplate.execute(status -> {
                // 7. 扣减库存
                long deductStartTime = System.currentTimeMillis();

                int count = seckillGoodsDOMapper.deductStock(message.getSeckillGoodsId());

                // 扣库存耗时
                long deductCost = System.currentTimeMillis() - deductStartTime;
                log.info("#### 秒杀扣库存耗时, orderNo: {}, seckillGoodsId: {}, cost: {} ms",
                        orderNo, message.getSeckillGoodsId(), deductCost);

                if (count == 0) {
                    // 售罄是正常业务结果，直接返回让消息正常 ACK，避免无限 requeue
                    log.warn("==> 扣减库存失败，商品已售罄或已下架, orderNo: {}", orderNo);
                    return null;
                }

                // 8. 创建订单
                SeckillOrderDO order = SeckillOrderDO.builder()
                        .userId(userId)
                        .activityId(activityId)
                        .goodsId(goodsId)
                        .orderNo(orderNo)
                        .seckillPrice(message.getSeckillPrice())
                        .goodsName(message.getGoodsName())
                        .goodsImg(message.getGoodsImg())
                        .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                        .expireTime(expireTime)
                        .isDeleted(0)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build();

                seckillOrderDOMapper.insert(order);
                return order;
            });

            // 扣库存失败，更新 Redis 中订单状态为秒杀失败
            if (Objects.isNull(orderDO)) {
                saveOrderStatus(userId, orderNo, OrderStatusEnum.SECKILL_FAILED.getStatus());

                // 清理 Redis 中的回补上下文
                deleteCompensationContext(orderNo);

                // 推送 SSE 结果
                seckillOrderResultNotifyService.notifyOrderResult(userId,
                        buildStatusResult(orderNo, OrderStatusEnum.SECKILL_FAILED));
                return;
            }

            // 订单创建成功，更新 Redis 中订单状态为待支付
            saveOrderStatus(userId, orderNo, OrderStatusEnum.PENDING_PAYMENT.getStatus());

            // 消费者已经正常落库，删除待回补上下文，避免 Redis 中残留无效数据，占着内存
            deleteCompensationContext(orderNo);

            // 推送 SSE 结果
            seckillOrderResultNotifyService.notifyOrderResult(userId, buildOrderResult(orderDO));

            log.info("==> 异步秒杀下单成功, orderNo: {}", orderNo);
        } catch (DuplicateKeyException e) {
            // 幂等兜底：order_no 唯一索引命中，说明是重复投递的消息
            // 直接当作成功处理，不再抛异常，避免消费者把消息无限重投
            log.warn("==> 重复消费秒杀消息，订单已存在，幂等返回, orderNo: {}", orderNo);

            // 不能直接写 PENDING_PAYMENT，避免把已支付、已取消等状态回退
            SeckillOrderDO existedOrderDO = seckillOrderDOMapper.selectByOrderNoAndUserId(orderNo, userId);
            if (Objects.nonNull(existedOrderDO)) {
                saveOrderStatus(userId, orderNo, existedOrderDO.getStatus());

                // 清理 Redis 中的回补上下文
                deleteCompensationContext(orderNo);

                // 推送 SSE 结果
                seckillOrderResultNotifyService.notifyOrderResult(userId, buildOrderResult(existedOrderDO));
            } else {
                log.warn("==> 重复消费命中唯一索引，但未查询到当前用户订单, orderNo: {}, userId: {}", orderNo, userId);
            }
        } finally {
            log.info("#### 秒杀订单消费链路总耗时, orderNo: {}, cost: {} ms",
                    orderNo, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 构建订单结果（含订单详情）
     *
     * @param orderDO
     * @return
     */
    private FindSeckillOrderResultRspVO buildOrderResult(SeckillOrderDO orderDO) {
        return FindSeckillOrderResultRspVO.builder()
                .orderId(orderDO.getId())
                .orderNo(orderDO.getOrderNo())
                .status(orderDO.getStatus())
                .statusDesc(OrderStatusEnum.getDescriptionByStatus(orderDO.getStatus()))
                .goodsId(orderDO.getGoodsId())
                .goodsName(orderDO.getGoodsName())
                .goodsImg(orderDO.getGoodsImg())
                .seckillPrice(orderDO.getSeckillPrice())
                .build();
    }

    /**
     * 构建仅含状态的订单结果
     *
     * @param orderNo
     * @param status
     * @return
     */
    private FindSeckillOrderResultRspVO buildStatusResult(String orderNo, OrderStatusEnum status) {
        return FindSeckillOrderResultRspVO.builder()
                .orderNo(orderNo)
                .status(status.getStatus())
                .statusDesc(status.getDescription())
                .build();
    }

    /**
     * 保存秒杀订单异步处理状态
     *
     * @param userId
     * @param orderNo
     * @param status
     */
    /**
     * 删除秒杀订单待回补上下文
     *
     * @param orderNo 订单号
     */
    private void deleteCompensationContext(String orderNo) {
        String compensationKey = RedisKeyConstants.buildSeckillOrderCompensationKey(orderNo);
        stringRedisTemplate.delete(compensationKey);
    }

    /**
     * 保存秒杀订单异步处理状态
     *
     * @param userId
     * @param orderNo
     * @param status
     */
    private void saveOrderStatus(Long userId, String orderNo, Integer status) {
        String redisKey = RedisKeyConstants.SECKILL_ORDER_STATUS_PREFIX + userId + ":" + orderNo;
        stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(status),
                RedisKeyConstants.SECKILL_ORDER_STATUS_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 查询秒杀订单处理结果
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<FindSeckillOrderResultRspVO> findSeckillOrderResult(FindSeckillOrderResultReqVO reqVO) {
        // 订单号
        String orderNo = reqVO.getOrderNo();
        // 当前登陆用户 ID
        long userId = StpUtil.getLoginIdAsLong();

        // 查询 Redis 获取订单状态
        String redisKey = RedisKeyConstants.SECKILL_ORDER_STATUS_PREFIX + userId + ":" + orderNo;
        String orderStatus = stringRedisTemplate.opsForValue().get(redisKey);

        if (StrUtil.isNotBlank(orderStatus)) {
            OrderStatusEnum statusEnum = OrderStatusEnum.getByStatus(Integer.valueOf(orderStatus));

            // Redis 中订单状态，如果是 PROCESSING 或 SECKILL_FAILED，直接返回
            if (OrderStatusEnum.PROCESSING.equals(statusEnum)
                    || OrderStatusEnum.SECKILL_FAILED.equals(statusEnum)) {
                return Response.success(FindSeckillOrderResultRspVO.builder()
                        .orderNo(orderNo)
                        .status(statusEnum.getStatus())
                        .statusDesc(statusEnum.getDescription())
                        .build());
            }
        }

        // 如果是 PENDING_PAYMENT 待支付状态，继续回查订单表，返回完整订单信息
        SeckillOrderDO orderDO = seckillOrderDOMapper.selectByOrderNoAndUserId(orderNo, userId);

        // 如果表里没订单记录，可能消费者还没来得及消费，返回处理中
        if (Objects.isNull(orderDO)) {
            return Response.success(FindSeckillOrderResultRspVO.builder()
                    .orderNo(orderNo)
                    .status(OrderStatusEnum.PROCESSING.getStatus())
                    .statusDesc(OrderStatusEnum.PROCESSING.getDescription())
                    .build());
        }

        return Response.success(FindSeckillOrderResultRspVO.builder()
                .orderId(orderDO.getId())
                .orderNo(orderDO.getOrderNo())
                .status(orderDO.getStatus())
                .statusDesc(OrderStatusEnum.getDescriptionByStatus(orderDO.getStatus()))
                .goodsId(orderDO.getGoodsId())
                .goodsName(orderDO.getGoodsName())
                .goodsImg(orderDO.getGoodsImg())
                .seckillPrice(orderDO.getSeckillPrice())
                .build());
    }
}
