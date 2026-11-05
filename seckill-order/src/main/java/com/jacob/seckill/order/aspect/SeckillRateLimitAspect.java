package com.jacob.seckill.order.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.jacob.seckill.common.constant.RedisKeyConstants;
import com.jacob.seckill.common.enums.ResponseCodeEnum;
import com.jacob.seckill.common.exception.BizException;
import com.jacob.seckill.common.utils.IpUtils;
import com.jacob.seckill.order.model.vo.DoSeckillReqVO;
import com.jacob.seckill.order.service.SeckillRateLimitService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Objects;

/**
 * @Author: jacob
 * @Date: 2026/11/5 23:30
 * @Version: v1.0.0
 * @Description: 秒杀接口限流切面
 **/
@Aspect
@Component
@Slf4j
public class SeckillRateLimitAspect {

    @Resource
    private SeckillRateLimitService seckillRateLimitService;

    /**
     * 环绕拦截添加了 @SeckillRateLimit 注解的方法
     *
     * @param joinPoint
     * @param seckillRateLimit
     * @return
     * @throws Throwable
     */
    @Around("@annotation(seckillRateLimit)")
    public Object doAround(ProceedingJoinPoint joinPoint, SeckillRateLimit seckillRateLimit) throws Throwable {
        // 1. 从被拦截方法的入参中，获取秒杀下单请求参数
        DoSeckillReqVO reqVO = getDoSeckillReqVO(joinPoint);
        if (Objects.isNull(reqVO)) {
            throw new IllegalStateException("@SeckillRateLimit 注解只能用于包含 DoSeckillReqVO 入参的方法");
        }

        // 2. 从请求参数中获取活动 ID 和商品 ID，用于构建限流 Key
        Long activityId = reqVO.getActivityId();
        Long goodsId = reqVO.getGoodsId();

        // 3. 获取当前登录用户 ID，用于做用户维度限流
        Long userId = StpUtil.getLoginIdAsLong();

        // 4. 从当前 HTTP 请求中获取客户端 IP，用于做 IP 维度限流
        HttpServletRequest request = getCurrentRequest();
        String clientIp = IpUtils.getClientIp(request);

        // 5. 先做用户维度限流，拦截同一个用户的高频请求
        checkUserRateLimit(activityId, goodsId, userId, seckillRateLimit);

        // 6. 再做 IP 维度限流，拦截同一个 IP 下的大量请求
        checkIpRateLimit(activityId, goodsId, clientIp, seckillRateLimit);

        // 7. 两个维度都通过后，才继续执行真正的秒杀下单方法
        return joinPoint.proceed();
    }

    /**
     * 用户维度限流
     */
    private void checkUserRateLimit(Long activityId, Long goodsId, Long userId, SeckillRateLimit seckillRateLimit) {
        // 构建用户维度限流 Key
        String userRateLimitKey = RedisKeyConstants.buildSeckillRateLimitUserKey(activityId, goodsId, userId);

        // 执行 Lua 脚本限流
        boolean userAllowed = seckillRateLimitService.tryAcquire(userRateLimitKey,
                seckillRateLimit.userMaxCount(), seckillRateLimit.userWindowSeconds());

        // 如果用户维度超过阈值时，直接抛业务异常，不再进入秒杀主链路
        if (!userAllowed) {
            throw new BizException(ResponseCodeEnum.SECKILL_REQUEST_TOO_FREQUENT);
        }
    }

    /**
     * IP 维度限流
     */
    private void checkIpRateLimit(Long activityId, Long goodsId, String clientIp, SeckillRateLimit seckillRateLimit) {
        // 构建 IP 维度限流 Key
        String ipRateLimitKey = RedisKeyConstants.buildSeckillRateLimitIpKey(activityId, goodsId, clientIp);

        // 执行 Lua 脚本限流
        boolean ipAllowed = seckillRateLimitService.tryAcquire(ipRateLimitKey,
                seckillRateLimit.ipMaxCount(), seckillRateLimit.ipWindowSeconds());

        // 如果 IP 维度超过阈值时，直接抛业务异常，不再进入秒杀主链路
        if (!ipAllowed) {
            throw new BizException(ResponseCodeEnum.SECKILL_REQUEST_TOO_FREQUENT);
        }
    }

    /**
     * 从方法入参中获取秒杀下单请求参数
     */
    private DoSeckillReqVO getDoSeckillReqVO(ProceedingJoinPoint joinPoint) {
        return Arrays.stream(joinPoint.getArgs())
                .filter(DoSeckillReqVO.class::isInstance)
                .map(DoSeckillReqVO.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前 HTTP 请求
     */
    private HttpServletRequest getCurrentRequest() {
        // 从 Spring 当前线程上下文中，获取请求属性
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();

        // 秒杀限流切面，只应该在 HTTP 请求线程中执行，如果拿不到请求上下文，说明使用位置不正确
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            throw new IllegalStateException("当前上下文不存在 HTTP 请求");
        }

        // 从 ServletRequestAttributes 中取出当前请求对象
        return servletRequestAttributes.getRequest();
    }
}
