package com.jacob.seckill.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:52
 * @Version: v1.0.0
 * @Description: 秒杀下单出参
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DoSeckillRspVO {

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 订单状态：-1=处理中（不落库，真实状态由消费者写入）
     */
    private Integer status;
}
