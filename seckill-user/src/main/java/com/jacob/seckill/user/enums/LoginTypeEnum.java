package com.jacob.seckill.user.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 登录类型枚举
 **/
@Getter
@AllArgsConstructor
public enum LoginTypeEnum {

    PASSWORD(1, "密码登录"),
    VERIFY_CODE(2, "验证码登录"),
    ;

    // 类型值
    private final Integer code;
    // 类型描述
    private final String description;
}
