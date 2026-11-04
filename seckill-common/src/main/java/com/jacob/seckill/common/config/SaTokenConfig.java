package com.jacob.seckill.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:52
 * @Version: v1.0.0
 * @Description: SaToken 配置类
 **/
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 拦截器，打开注解式鉴权功能
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 秒杀下单接口，需要登录
            SaRouter.match("/seckill/order", r -> StpUtil.checkLogin());
            // 登出接口，需要登录
            SaRouter.match("/user/logout", r -> StpUtil.checkLogin());
            // 管理端接口，需要登录
            SaRouter.match("/admin/**", r -> StpUtil.checkLogin());
        }) {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                // SSE 完成后 Spring 会再做一次 ASYNC 派发，此时 Sa-Token 上下文尚未初始化，直接放行
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/**");
    }
}
