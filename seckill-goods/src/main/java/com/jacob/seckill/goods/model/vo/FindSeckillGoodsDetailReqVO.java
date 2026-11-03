package com.jacob.seckill.goods.model.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 查询秒杀商品详情入参
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindSeckillGoodsDetailReqVO {

    /**
     * 商品 ID
     */
    @NotNull(message = "商品 ID 不能为空")
    @Positive(message = "商品 ID 不合法")
    private Long goodsId;

    /**
     * 活动 ID
     */
    @NotNull(message = "活动 ID 不能为空")
    @Positive(message = "活动 ID 不合法")
    private Long activityId;
}
