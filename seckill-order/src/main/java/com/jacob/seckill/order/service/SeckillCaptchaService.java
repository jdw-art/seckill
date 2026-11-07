package com.jacob.seckill.order.service;

/**
 * @Author: jacob
 * @Date: 2026/11/7 22:10
 * @Version: v1.0.0
 * @Description: 秒杀验证码校验服务
 **/
public interface SeckillCaptchaService {

    /**
     * 校验秒杀行为验证码
     *
     * @param captchaId 行为验证码二次校验 ID
     */
    void verify(String captchaId);
}
