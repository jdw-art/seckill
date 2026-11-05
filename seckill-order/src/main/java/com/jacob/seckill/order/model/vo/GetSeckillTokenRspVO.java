package com.jacob.seckill.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/5 23:50
 * @Version: v1.0.0
 * @Description: 获取秒杀令牌出参
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GetSeckillTokenRspVO {

    /**
     * 秒杀令牌
     */
    private String token;

    /**
     * 令牌有效期（秒）
     */
    private Long expireSeconds;
}
