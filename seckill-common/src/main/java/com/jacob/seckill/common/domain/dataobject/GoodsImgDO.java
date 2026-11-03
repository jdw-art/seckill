package com.jacob.seckill.common.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 商品轮播图表 DO
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GoodsImgDO {

    private Long id;

    private Long goodsId;

    private String imgUrl;

    private Integer sort;

    private LocalDateTime createTime;
}
