package com.jacob.seckill.order.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.hutool.core.util.StrUtil;
import com.jacob.seckill.common.enums.ResponseCodeEnum;
import com.jacob.seckill.common.exception.BizException;
import com.jacob.seckill.order.service.SeckillCaptchaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @Author: jacob
 * @Date: 2026/11/7 22:10
 * @Version: v1.0.0
 * @Description: 秒杀验证码校验服务实现
 **/
@Service
@Slf4j
public class SeckillCaptchaServiceImpl implements SeckillCaptchaService {

    @Resource
    private ImageCaptchaApplication imageCaptchaApplication;

    /**
     * 校验秒杀行为验证码
     *
     * @param captchaId 行为验证码二次校验 ID
     */
    @Override
    public void verify(String captchaId) {
        // 行为验证码二次校验 ID 不能为空
        if (StrUtil.isBlank(captchaId)) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }

        // 二次校验
        boolean verified = false;
        if (imageCaptchaApplication instanceof SecondaryVerificationApplication secondaryVerificationApplication) {
            verified = secondaryVerificationApplication.secondaryVerification(captchaId);
        }

        // 校验失败，说明用户没有完成验证码，或者 captchaId 已过期 / 已使用
        if (!verified) {
            log.warn("==> 秒杀行为验证码校验失败, captchaId: {}", captchaId);
            throw new BizException(ResponseCodeEnum.CAPTCHA_VERIFICATION_FAILED);
        }
    }
}
