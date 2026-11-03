package com.jacob.seckill.goods.model.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/4 00:35
 * @Version: v1.0.0
 * @Description: 预热活动商品缓存
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PreheatActivityCacheReqVO {

    @NotNull(message = "活动 ID 不能为空")
    @Positive(message = "活动 ID 不合法")
    private Long activityId;

}
