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
import com.jacob.seckill.order.service.SeckillCaptchaService;
import com.jacob.seckill.order.service.SeckillOrderResultNotifyService;
import com.jacob.seckill.order.service.SeckillStockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
     * 秒杀令牌校验并删除 Lua 脚本
     */
    private final DefaultRedisScript<Long> seckillCheckTokenScript;

    /**
     * 秒杀令牌大闸 Lua 脚本
     */
    private final DefaultRedisScript<String> seckillTokenGateScript;

    /**
     * 初始化秒杀令牌校验并删除 Lua 脚本
     */
    public OrderServiceImpl() {
        this.seckillCheckTokenScript = new DefaultRedisScript<>();
        this.seckillCheckTokenScript.setLocation(new ClassPathResource("lua/seckill_check_token.lua"));
        this.seckillCheckTokenScript.setResultType(Long.class);

        this.seckillTokenGateScript = new DefaultRedisScript<>();
        this.seckillTokenGateScript.setLocation(new ClassPathResource("lua/seckill_token_gate.lua"));
        this.seckillTokenGateScript.setResultType(String.class);
    }

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

    @Resource
    private SeckillCaptchaService seckillCaptchaService;

    @Resource(name = "seckillOrderQueueDrainExecutor")
    private ThreadPoolTaskExecutor seckillOrderQueueDrainExecutor;

    /**
     * 秒杀下单
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<DoSeckillRspVO> doSeckill(DoSeckillReqVO reqVO) {
        // 获取登录用户 ID
        long userId = StpUtil.getLoginIdAsLong();

        try {
            // 提交到固定大小线程池，限制同一时刻真正进入下单链路的请求数量
            return seckillOrderQueueDrainExecutor.submit(() -> doSeckillInternal(reqVO, userId)).get();
        } catch (RejectedExecutionException e) {
            // 线程池队列已满，当前瞬时请求过多，直接快速失败
            log.warn("==> 秒杀下单队列泄洪线程池已满, activityId: {}, goodsId: {}, userId: {}",
                    reqVO.getActivityId(), reqVO.getGoodsId(), userId);

            throw new BizException(ResponseCodeEnum.SECKILL_SYSTEM_BUSY);
        } catch (InterruptedException e) {
            // 当前请求线程被中断，快速失败
            Thread.currentThread().interrupt();
            log.warn("==> 秒杀下单任务被中断, activityId: {}, goodsId: {}, userId: {}",
                    reqVO.getActivityId(), reqVO.getGoodsId(), userId, e);

            throw new BizException(ResponseCodeEnum.SECKILL_SYSTEM_BUSY);
        } catch (ExecutionException e) {
            // 线程内部执行错误，业务异常需要原样抛出
            Throwable cause = e.getCause();

            if (cause instanceof BizException bizException) {
                throw bizException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("秒杀下单任务执行失败", cause);
        }
    }

    /**
     * 真正执行秒杀下单逻辑
     *
     * @param reqVO
     * @param userId
     * @return
     */
    private Response<DoSeckillRspVO> doSeckillInternal(DoSeckillReqVO reqVO, long userId) {
        // 活动 ID
        Long activityId = reqVO.getActivityId();
        // 商品 ID
        Long goodsId = reqVO.getGoodsId();

        // 1. 校验秒杀令牌，拦截没有经过令牌接口的请求
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
        // Spring Retry 重试耗尽后，异常由 SeckillOrderMessageSender 的 @Recover 兜底接住
        // 失败消息会被登记到补偿表，由补偿任务重发
        boolean isSendSuccess;
        try {
            isSendSuccess = seckillOrderMessageSender.send(seckillOrderMqDTO);
        } catch (Exception e) {
            // 多次重试后，仍发送失败
            log.error("==> 秒杀下单消息发送失败, orderNo: {}", orderNo, e);
            isSendSuccess = false;
        }

        if (!isSendSuccess) {
            log.warn("==> 秒杀下单消息发送失败, 已进入补偿链路, 订单保持处理中, orderNo: {}", orderNo);
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

        // 1.1 获取令牌前先校验行为验证码，避免脚本直接高频刷新 token
        seckillCaptchaService.verify(reqVO.getCaptchaId());

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

        // 4. 构建令牌 Key 和大闸 Key
        String tokenKey = RedisKeyConstants.buildSeckillOrderTokenKey(activityId, goodsId, userId);
        String tokenGateKey = RedisKeyConstants.buildSeckillTokenGateKey(activityId, goodsId);

        // 5. 先生成候选令牌，是否真正写入 Redis 由 Lua 脚本原子判断
        String candidateToken = generateSeckillToken();

        // 6. Lua 内完成 "复用旧令牌 / 扣大闸 / 写新令牌" 的原子操作
        String tokenGateResult = stringRedisTemplate.execute(seckillTokenGateScript, List.of(tokenKey, tokenGateKey),
                candidateToken, String.valueOf(RedisKeyConstants.SECKILL_ORDER_TOKEN_TTL_SECONDS));

        return buildTokenGateResponse(tokenGateResult, userId, activityId, goodsId, tokenKey, tokenGateKey);
    }

    /**
     * 解析秒杀令牌剩余过期时间
     *
     * @param expireSeconds
     * @return
     */
    private Long resolveTokenExpireSeconds(Long expireSeconds) {
        if (Objects.isNull(expireSeconds) || expireSeconds <= 0) {
            return RedisKeyConstants.SECKILL_ORDER_TOKEN_TTL_SECONDS;
        }
        return expireSeconds;
    }

    /**
     * 构建秒杀令牌大闸响应
     *
     * @param tokenGateResult
     * @param userId
     * @param activityId
     * @param goodsId
     * @param tokenKey
     * @param tokenGateKey
     * @return
     */
    private Response<GetSeckillTokenRspVO> buildTokenGateResponse(String tokenGateResult, Long userId, Long activityId,
                                                                  Long goodsId, String tokenKey, String tokenGateKey) {
        if (StrUtil.isBlank(tokenGateResult)) {
            throw new IllegalStateException("执行秒杀令牌大闸 Lua 脚本失败");
        }

        // 分割 Lua 执行结果：code|token|expireSeconds
        String[] resultArr = tokenGateResult.split("\\|", -1);
        if (resultArr.length != 3) {
            throw new IllegalStateException("秒杀令牌大闸 Lua 脚本返回格式错误：" + tokenGateResult);
        }

        // 返回码
        Long resultCode = Long.valueOf(resultArr[0]);
        // 秒杀令牌
        String token = resultArr[1];
        // 过期时间
        Long expireSeconds = Long.valueOf(resultArr[2]);

        // -1：大闸 Key 不存在，属于未预热或已过期的系统配置问题
        if (Objects.equals(resultCode, -1L)) {
            log.error("==> 秒杀令牌大闸未预热, key: {}", tokenGateKey);
            throw new IllegalStateException("秒杀令牌大闸未预热，请先预热活动");
        }

        // 0：大闸名额已发完，不再发放新的用户级令牌
        if (Objects.equals(resultCode, 0L)) {
            log.info("==> 秒杀令牌大闸名额已发完, key: {}", tokenGateKey);
            throw new BizException(ResponseCodeEnum.SECKILL_TOKEN_GATE_EMPTY);
        }

        if (Objects.equals(resultCode, 2L)) {
            log.info("==> 当前用户已存在有效秒杀令牌, userId: {}, activityId: {}, goodsId: {}, tokenKey: {}",
                    userId, activityId, goodsId, tokenKey);
        } else if (Objects.equals(resultCode, 1L)) {
            log.info("==> 生成秒杀令牌, userId: {}, activityId: {}, goodsId: {}, tokenKey: {}",
                    userId, activityId, goodsId, tokenKey);
        } else {
            throw new IllegalStateException("秒杀令牌大闸 Lua 脚本返回未知结果码：" + resultCode);
        }

        return Response.success(GetSeckillTokenRspVO.builder()
                .token(token)
                .expireSeconds(resolveTokenExpireSeconds(expireSeconds))
                .build());
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
        // 构建当前用户、当前活动、当前商品对应的令牌 Key
        String tokenKey = RedisKeyConstants.buildSeckillOrderTokenKey(activityId, goodsId, userId);

        // 通过 Lua 脚本，来原子型的完成 "比对令牌 + 删除令牌"
        Long result = stringRedisTemplate.execute(seckillCheckTokenScript, List.of(tokenKey), token);
        if (Objects.isNull(result)) {
            throw new IllegalStateException("执行秒杀令牌校验 Lua 脚本失败");
        }

        // 返回 0，表示令牌不存在、已过期、已被使用过，或者请求中的令牌不正确
        if (!Objects.equals(result, 1L)) {
            log.warn("==> 秒杀令牌校验失败, userId: {}, activityId: {}, goodsId: {}, tokenKey: {}",
                    userId, activityId, goodsId, tokenKey);
            throw new BizException(ResponseCodeEnum.SECKILL_TOKEN_INVALID);
        }

        log.info("==> 秒杀令牌校验通过并删除, userId: {}, activityId: {}, goodsId: {}, tokenKey: {}",
                userId, activityId, goodsId, tokenKey);
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
