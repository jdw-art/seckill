package com.jacob.seckill.order.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Author: jacob
 * @Date: 2026/11/5 23:30
 * @Version: v1.0.0
 * @Description: 秒杀接口限流注解
 **/
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SeckillRateLimit {

    /**
     * 用户维度最大请求次数
     */
    long userMaxCount() default 5;

    /**
     * 用户维度限流窗口时间，单位：秒
     */
    long userWindowSeconds() default 10;

    /**
     * IP 维度最大请求次数
     */
    long ipMaxCount() default 50;

    /**
     * IP 维度限流窗口时间，单位：秒
     */
    long ipWindowSeconds() default 10;
}
