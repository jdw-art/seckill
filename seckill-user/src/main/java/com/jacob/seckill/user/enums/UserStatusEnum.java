package com.jacob.seckill.user.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: 犬小哈
 * @Date: 2026/4/10 17:55
 * @Version: v1.0.0
 * @Description: 用户状态枚举
 **/
@Getter
@AllArgsConstructor
public enum UserStatusEnum {

    DISABLED(0, "禁用"),
    ENABLED(1, "启用"),
    ;

    // 状态值
    private final Integer code;
    // 状态描述
    private final String description;

}
