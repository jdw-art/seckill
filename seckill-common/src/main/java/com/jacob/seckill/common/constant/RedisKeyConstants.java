package com.jacob.seckill.common.constant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @Author: jacob
 * @Date: 2026/11/4 00:20
 * @Version: v1.0.0
 * @Description: Redis 缓存 Key 常量类
 **/
public class RedisKeyConstants {

    /**
     * 商品列表缓存 Key 前缀
     *
     * 完整格式：seckill:goods:list:{activityId}
     */
    public static final String GOODS_LIST_PREFIX = "seckill:goods:list:";

    /**
     * 商品详情缓存 Key 前缀
     *
     * 完整格式：seckill:goods:detail:{activityId}:{goodsId}
     */
    public static final String GOODS_DETAIL_PREFIX = "seckill:goods:detail:";

    /**
     * 活动布隆过滤器 Key
     */
    public static final String SECKILL_ACTIVITY_BLOOM_KEY = "seckill:bloom:activity";

    /**
     * 商品布隆过滤器 Key
     */
    public static final String SECKILL_GOODS_BLOOM_KEY = "seckill:bloom:goods";

    /**
     * 缓存空值，用于防止缓存穿透
     */
    public static final String NULL_CACHE_VALUE = "NULL";

    /**
     * 缓存空值的过期时间（单位：分钟）
     */
    public static final long NULL_CACHE_TTL_MINUTES = 5;

    /**
     * 活动结束后，缓存保留的短过期时间（单位：分钟）
     * 防止活动结束后仍有余温流量，每次都打到 DB
     */
    public static final long ENDED_ACTIVITY_TTL_MINUTES = 5;

    /**
     * 安全缓冲时间（单位：秒）
     */
    public static final long SAFETY_BUFFER_SECONDS = 30 * 60; // 30 分钟

    /**
     * 秒杀订单处理状态 Key 前缀
     *
     * 完整格式：seckill:order:status:{userId}:{orderNo}
     */
    public static final String SECKILL_ORDER_STATUS_PREFIX = "seckill:order:status:";

    /**
     * 秒杀订单处理状态过期时间（单位：分钟）
     */
    public static final long SECKILL_ORDER_STATUS_TTL_MINUTES = 30;

    /**
     * 秒杀库存 Key 前缀
     *
     * 完整格式：seckill:stock:{activityId}:{goodsId}
     */
    public static final String SECKILL_STOCK_PREFIX = "seckill:stock:";

    /**
     * 构建秒杀库存 Key
     */
    public static String buildSeckillStockKey(Long activityId, Long goodsId) {
        return SECKILL_STOCK_PREFIX + activityId + ":" + goodsId;
    }

    /**
     * 秒杀用户购买标记 Key 前缀
     *
     * 完整格式：seckill:user:order:{activityId}:{goodsId}:{userId}
     */
    public static final String SECKILL_USER_ORDER_PREFIX = "seckill:user:order:";

    /**
     * 构建秒杀用户购买标记 Key
     */
    public static String buildSeckillUserOrderKey(Long activityId, Long goodsId, Long userId) {
        return SECKILL_USER_ORDER_PREFIX + activityId + ":" + goodsId + ":" + userId;
    }

    /**
     * 秒杀活动商品元数据 Key 前缀
     *
     * 完整格式：seckill:activity:goods:meta:{activityId}:{goodsId}
     */
    public static final String SECKILL_ACTIVITY_GOODS_META_PREFIX = "seckill:activity:goods:meta:";

    /**
     * 构建秒杀活动商品元数据 Key
     */
    public static String buildSeckillActivityGoodsMetaKey(Long activityId, Long goodsId) {
        return SECKILL_ACTIVITY_GOODS_META_PREFIX + activityId + ":" + goodsId;
    }

    /**
     * 秒杀商品售罄标记 Key 前缀
     *
     * 完整格式：seckill:soldout:{activityId}:{goodsId}
     */
    public static final String SECKILL_SOLD_OUT_PREFIX = "seckill:soldout:";

    /**
     * 构建秒杀商品售罄标记 Key
     */
    public static String buildSeckillSoldOutKey(Long activityId, Long goodsId) {
        return SECKILL_SOLD_OUT_PREFIX + activityId + ":" + goodsId;
    }

    /**
     * 秒杀用户维度限流 Key 前缀
     */
    public static final String SECKILL_RATE_LIMIT_USER_PREFIX = "seckill:rate:user:";

    /**
     * 秒杀 IP 维度限流 Key 前缀
     */
    public static final String SECKILL_RATE_LIMIT_IP_PREFIX = "seckill:rate:ip:";

    /**
     * 构建秒杀用户维度限流 Key
     */
    public static String buildSeckillRateLimitUserKey(Long activityId, Long goodsId, Long userId) {
        return SECKILL_RATE_LIMIT_USER_PREFIX + activityId + ":" + goodsId + ":" + userId;
    }

    /**
     * 构建秒杀 IP 维度限流 Key
     */
    public static String buildSeckillRateLimitIpKey(Long activityId, Long goodsId, String clientIp) {
        return SECKILL_RATE_LIMIT_IP_PREFIX + activityId + ":" + goodsId + ":" + clientIp;
    }

    /**
     * 秒杀订单待回补上下文 Key 前缀
     *
     * 完整格式：seckill:order:compensation:{orderNo}
     */
    public static final String SECKILL_ORDER_COMPENSATION_PREFIX = "seckill:order:compensation:";

    /**
     * 构建秒杀订单待回补上下文 Key
     */
    public static String buildSeckillOrderCompensationKey(String orderNo) {
        return SECKILL_ORDER_COMPENSATION_PREFIX + orderNo;
    }

    /**
     * 根据活动结束时间动态计算缓存 TTL（秒）
     *
     * 公式：TTL = (活动结束时间 - 当前时间) + 安全缓冲时间
     *
     * @param endTime
     * @return
     */
    public static Long calculateTtlSeconds(LocalDateTime endTime) {
        if (Objects.isNull(endTime)) {
            return null;
        }
        long ttlSeconds = Duration.between(LocalDateTime.now(), endTime).getSeconds()
                + SAFETY_BUFFER_SECONDS;
        return ttlSeconds > 0 ? ttlSeconds : null;
    }
}
