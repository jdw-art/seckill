package com.jacob.seckill.user.model.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 发送验证码
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SendVerifyCodeReqVO {

    /**
     * 手机号
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    /**
     * 验证码类型（1: 注册，2: 登录）
     */
    @NotNull(message = "验证码类型不能为空")
    private Integer type;

    /**
     * 行为验证码校验 ID（前端完成滑块验证后，由 /captcha/check 接口返回）
     */
    @NotBlank(message = "行为验证码校验 ID 不能为空")
    private String captchaId;

}
