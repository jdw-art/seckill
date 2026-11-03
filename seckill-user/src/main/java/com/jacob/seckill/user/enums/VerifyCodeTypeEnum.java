package com.jacob.seckill.user.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 验证码类型枚举
 **/
@Getter
@AllArgsConstructor
public enum VerifyCodeTypeEnum {

    REGISTER(1, "register", "注册"),
    LOGIN(2, "login", "登录"),
    ;

    // 类型值
    private final Integer code;
    // 场景标识（用于拼接 Redis Key）
    private final String purpose;
    // 类型描述
    private final String description;

    /**
     * 根据 code 获取枚举
     *
     * @param code
     * @return
     */
    public static VerifyCodeTypeEnum valueOf(Integer code) {
        for (VerifyCodeTypeEnum typeEnum : values()) {
            if (typeEnum.getCode().equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }
}
