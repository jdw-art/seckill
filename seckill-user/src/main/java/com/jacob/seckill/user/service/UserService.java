package com.jacob.seckill.user.service;

import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.user.model.vo.LoginUserReqVO;
import com.jacob.seckill.user.model.vo.LoginUserRspVO;
import com.jacob.seckill.user.model.vo.RegisterUserReqVO;
import com.jacob.seckill.user.model.vo.SendVerifyCodeReqVO;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 用户业务
 **/
public interface UserService {

    /**
     * 用户注册
     * @param registerUserReqVO
     * @return
     */
    Response<?> register(RegisterUserReqVO registerUserReqVO);

    /**
     * 用户登录
     * @param loginUserReqVO
     * @return
     */
    Response<LoginUserRspVO> login(LoginUserReqVO loginUserReqVO);

    /**
     * 发送验证码
     * @param sendVerifyCodeReqVO
     * @return
     */
    Response<?> sendVerifyCode(SendVerifyCodeReqVO sendVerifyCodeReqVO);

    /**
     * 退出登录
     * @return
     */
    Response<?> logout();
}
