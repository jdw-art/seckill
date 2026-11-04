package com.jacob.seckill.order.model.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/4 11:00
 * @Version: v1.0.0
 * @Description: 查询秒杀订单结果
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindSeckillOrderResultReqVO {

    /**
     * 订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

}
