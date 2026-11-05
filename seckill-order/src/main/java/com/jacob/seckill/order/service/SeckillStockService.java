package com.jacob.seckill.order.service;

import com.jacob.seckill.order.enums.SeckillStockCompensationResultEnum;
import com.jacob.seckill.order.enums.SeckillStockDeductResultEnum;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;

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
    SeckillStockDeductResultEnum preDeductStock(SeckillOrderMqDTO seckillOrderMqDTO,
                                                Long userOrderTtlSeconds,
                                                Long activityBeginTimeMillis,
                                                Long activityEndTimeMillis);

    /**
     * Redis Lua 原子回补秒杀库存，并删除用户购买标记
     */
    SeckillStockCompensationResultEnum compensatePreDeductStock(Long activityId,
                                                                Long goodsId,
                                                                Long userId,
                                                                String orderNo);

}
