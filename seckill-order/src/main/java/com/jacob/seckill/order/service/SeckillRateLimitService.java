package com.jacob.seckill.order.service;

/**
 * @Author: jacob
 * @Date: 2026/11/5 11:30
 * @Version: v1.0.0
 * @Description: 秒杀接口限流
 **/
public interface SeckillRateLimitService {

    /**
     * 尝试获取一次请求资格
     *
     * @param limitKey      限流 Key
     * @param maxCount      窗口内允许的最大请求次数
     * @param windowSeconds 窗口时间，单位：秒
     * @return true：允许通过；false：超过阈值
     */
    boolean tryAcquire(String limitKey, long maxCount, long windowSeconds);

}
