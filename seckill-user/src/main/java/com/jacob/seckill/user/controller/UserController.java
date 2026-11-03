package com.jacob.seckill.user.controller;

import com.jacob.seckill.common.aspect.ApiOperationLog;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.user.model.vo.LoginUserReqVO;
import com.jacob.seckill.user.model.vo.LoginUserRspVO;
import com.jacob.seckill.user.model.vo.RegisterUserReqVO;
import com.jacob.seckill.user.model.vo.SendVerifyCodeReqVO;
import com.jacob.seckill.user.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: 犬小哈
 * @Date: 2026/4/10 18:49
 * @Version: v1.0.0
 * @Description: 用户接口
 **/
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    @ApiOperationLog(description = "用户注册")
    public Response<?> register(@Validated @RequestBody RegisterUserReqVO registerUserReqVO) {
        return userService.register(registerUserReqVO);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @ApiOperationLog(description = "用户登录")
    public Response<LoginUserRspVO> login(@Validated @RequestBody LoginUserReqVO loginUserReqVO) {
        return userService.login(loginUserReqVO);
    }

    /**
     * 发送验证码
     */
    @PostMapping("/code/send")
    @ApiOperationLog(description = "发送验证码")
    public Response<?> sendVerifyCode(@Validated @RequestBody SendVerifyCodeReqVO sendVerifyCodeReqVO) {
        return userService.sendVerifyCode(sendVerifyCodeReqVO);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @ApiOperationLog(description = "退出登录")
    public Response<?> logout() {
        return userService.logout();
    }

}
