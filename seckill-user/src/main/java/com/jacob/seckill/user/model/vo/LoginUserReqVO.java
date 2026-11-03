package com.jacob.seckill.user.model.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 犬小哈
 * @Date: 2026/4/12 21:07
 * @Version: v1.0.0
 * @Description: 用户登录
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LoginUserReqVO {

    /**
     * 手机号
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    /**
     * 登录类型：1=密码登录，2=验证码登录
     */
    @NotNull(message = "登录类型不能为空")
    private Integer type;

    /**
     * 密码（type=1 时必填）
     */
    private String password;

    /**
     * 短信验证码（type=2 时必填）
     */
    private String verifyCode;
}
