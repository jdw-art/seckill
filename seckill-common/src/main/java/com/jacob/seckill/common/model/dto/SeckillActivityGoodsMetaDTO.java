package com.jacob.seckill.common.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @Author: jacob
 * @Date: 2026/11/4 12:30
 * @Version: v1.0.0
 * @Description: 秒杀活动商品元数据
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SeckillActivityGoodsMetaDTO {

    private Long seckillGoodsId;

    private Long activityId;

    private Long goodsId;

    private BigDecimal seckillPrice;

    /**
     * 商品名称快照
     */
    private String goodsName;

    /**
     * 商品主图快照
     */
    private String goodsImg;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

}
