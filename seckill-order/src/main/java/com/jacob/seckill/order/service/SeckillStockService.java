package com.jacob.seckill.order.service;

import com.jacob.seckill.order.enums.SeckillStockDeductResultEnum;

/**
 * @Author: jacob
 * @Date: 2026/11/4 12:10
 * @Version: v1.0.0
 * @Description: 秒杀库存服务
 **/
public interface SeckillStockService {

    /**
     * Redis Lua 原子预扣库存
     */
    SeckillStockDeductResultEnum preDeductStock(Long activityId, Long goodsId,
                                                Long userId, Long userOrderTtlSeconds,
                                                Long activityBeginTimeMillis, Long activityEndTimeMillis);

}
