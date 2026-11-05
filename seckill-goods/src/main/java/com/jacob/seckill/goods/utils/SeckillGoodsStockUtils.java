package com.jacob.seckill.goods.utils;

import cn.hutool.core.util.StrUtil;

/**
 * @Author: jacob
 * @Date: 2026/11/5 10:30
 * @Version: v1.0.0
 * @Description: 秒杀商品库存展示工具类
 **/
public class SeckillGoodsStockUtils {

    private SeckillGoodsStockUtils() {
    }

    /**
     * 计算页面展示库存
     *
     * @param stockValue    Redis 秒杀库存值
     * @param fallbackStock 兜底库存值
     * @return 页面展示库存
     */
    public static Integer resolveDisplayStock(String stockValue, Integer fallbackStock) {
        if (StrUtil.isBlank(stockValue)) {
            return fallbackStock;
        }

        try {
            return Math.max(Integer.parseInt(stockValue), 0);
        } catch (NumberFormatException e) {
            return fallbackStock;
        }
    }
}
