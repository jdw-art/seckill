package com.jacob.seckill.user.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.jacob.seckill.common.domain.dataobject.UserDO;
import com.jacob.seckill.common.domain.mapper.UserDOMapper;
import com.jacob.seckill.common.enums.ResponseCodeEnum;
import com.jacob.seckill.common.exception.BizException;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.user.enums.LoginTypeEnum;
import com.jacob.seckill.user.enums.UserStatusEnum;
import com.jacob.seckill.user.enums.VerifyCodeTypeEnum;
import com.jacob.seckill.user.model.vo.LoginUserReqVO;
import com.jacob.seckill.user.model.vo.LoginUserRspVO;
import com.jacob.seckill.user.model.vo.RegisterUserReqVO;
import com.jacob.seckill.user.model.vo.SendVerifyCodeReqVO;
import com.jacob.seckill.user.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 用户业务
 **/
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Resource
    private UserDOMapper userDOMapper;
//
//    @Resource
//    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "bizExecutor")
    private Executor bizExecutor;

    @Resource
    private ImageCaptchaApplication imageCaptchaApplication;

    // Redis 中验证码的 Key 前缀
    private static final String VERIFY_CODE_KEY_PREFIX = "verify_code:";
    // Redis 中发送频率限制的 Key 前缀
    private static final String VERIFY_CODE_LIMIT_KEY_PREFIX = "verify_code_limit:";
    // 验证码过期时间（分钟）
    private static final Long VERIFY_CODE_EXPIRE_MINUTES = 5L;
    // 发送频率限制时间（秒）
    private static final Long VERIFY_CODE_LIMIT_SECONDS = 60L;

    // Redis 中每日发送次数限制的 Key 前缀
    private static final String VERIFY_CODE_DAILY_LIMIT_KEY_PREFIX = "verify_code_daily:";
    // 每日发送次数上限
    private static final Integer VERIFY_CODE_DAILY_LIMIT = 10;

    // Redis 中登录失败次数的 Key 前缀
    private static final String LOGIN_FAIL_COUNT_KEY_PREFIX = "login_fail_count:";
    // 登录失败次数上限（超过此值则临时锁定账号）
    private static final Integer LOGIN_FAIL_MAX_COUNT = 5;
    // 账号临时锁定时间（分钟）
    private static final Long LOGIN_LOCK_MINUTES = 30L;

    /**
     * 验证码校验 Lua 脚本
     */
    private final DefaultRedisScript<Long> checkAndDeleteVerifyCodeScript;

    /**
     * 登录失败计数 Lua 脚本
     */
    private final DefaultRedisScript<Long> checkAndIncrementLoginFailScript;

    /**
     * 验证码每日发送次数限制 Lua 脚本
     */
    private final DefaultRedisScript<Long> checkAndIncrementDailyLimitScript;

    /**
     * 构造函数：初始化 Lua 脚本
     */
    public UserServiceImpl() {
        // 1. 验证码校验 Lua 脚本
        checkAndDeleteVerifyCodeScript = new DefaultRedisScript<>();
        // 从 classpath 的 lua 目录下加载 Lua 脚本文件
        checkAndDeleteVerifyCodeScript.setLocation(new ClassPathResource("lua/check_and_delete_verify_code.lua"));
        // 指定脚本返回值类型
        checkAndDeleteVerifyCodeScript.setResultType(Long.class);

        // 2. 登录失败计数 Lua 脚本
        checkAndIncrementLoginFailScript = new DefaultRedisScript<>();
        checkAndIncrementLoginFailScript.setLocation(new ClassPathResource("lua/check_and_increment_login_fail_count.lua"));
        checkAndIncrementLoginFailScript.setResultType(Long.class);

        // 3. 每日发送次数限制 Lua 脚本
        checkAndIncrementDailyLimitScript = new DefaultRedisScript<>();
        checkAndIncrementDailyLimitScript.setLocation(new ClassPathResource("lua/check_and_increment_verify_code_daily_limit.lua"));
        checkAndIncrementDailyLimitScript.setResultType(Long.class);
    }

    // BCrypt 密码编码器
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * 用户注册
     *
     * @param registerUserReqVO
     * @return
     */
    @Override
    public Response<?> register(RegisterUserReqVO registerUserReqVO) {
        String mobile = registerUserReqVO.getMobile();
        String password = registerUserReqVO.getPassword();
        String verifyCode = registerUserReqVO.getVerifyCode();

        // 1. 校验验证码
        checkVerifyCode(verifyCode, mobile, VerifyCodeTypeEnum.REGISTER.getPurpose());

        // 2. 校验手机号是否已注册
        Long existUserId = userDOMapper.selectIdByMobile(mobile);
        if (Objects.nonNull(existUserId)) {
            throw new BizException(ResponseCodeEnum.USER_MOBILE_EXISTS);
        }

        // 3. 密码加密（使用 BCrypt 算法）
        String encodedPassword = PASSWORD_ENCODER.encode(password);

        // 4. 构建用户实体，插入数据库
        UserDO userDO = UserDO.builder()
                .mobile(mobile)
                .password(encodedPassword)
                .nickname(generateNickname()) // 生成随机用户昵称
                .status(UserStatusEnum.ENABLED.getCode()) // 默认启用
                .build();

        userDOMapper.insertSelective(userDO);

        log.info("==> 用户注册成功, mobile: {}", mobile);

        return Response.success();
    }

    /**
     * 用户登录
     *
     * @param loginUserReqVO
     * @return
     */
    @Override
    public Response<LoginUserRspVO> login(LoginUserReqVO loginUserReqVO) {
        String mobile = loginUserReqVO.getMobile();
        Integer type = loginUserReqVO.getType();

        // 1. 根据手机号查询用户
        UserDO userDO = userDOMapper.selectByMobile(mobile);

        // 2. 判断用户是否存在
        if (Objects.isNull(userDO)) {
            // 密码登录：返回模糊错误提示
            if (Objects.equals(type, LoginTypeEnum.PASSWORD.getCode())) {
                throw new BizException(ResponseCodeEnum.USER_LOGIN_CREDENTIAL_ERROR);
            }
            throw new BizException(ResponseCodeEnum.USER_MOBILE_NOT_REGISTERED);
        }

        // 3. 校验用户状态（是否被禁用，放在身份验证之前，避免被禁用账号还执行耗时的密码校验）
        if (Objects.equals(userDO.getStatus(), UserStatusEnum.DISABLED.getCode())) {
            throw new BizException(ResponseCodeEnum.USER_STATUS_DISABLED);
        }

        // 4. 根据登录类型，进行身份验证
        if (Objects.equals(type, LoginTypeEnum.PASSWORD.getCode())) {
            // 检查登录失败次数
            checkLoginFailLimit(mobile);

            // 密码登录：校验密码是否正确
            checkPassword(loginUserReqVO.getPassword(), userDO.getPassword(), mobile);
        } else {
            // 验证码登录：校验验证码是否正确
            checkVerifyCode(loginUserReqVO.getVerifyCode(), mobile, VerifyCodeTypeEnum.LOGIN.getPurpose());
        }

        // 5. 调用 SaToken 执行登录，传入用户 ID
        StpUtil.login(userDO.getId());

        // 6. 获取 SaToken 生成的 Token
        String token = StpUtil.getTokenValue();

        // 7. 构建返参对象
        LoginUserRspVO loginUserRspVO = LoginUserRspVO.builder()
                .token(token)
                .userInfo(LoginUserRspVO.UserInfo.builder()
                        .id(userDO.getId())
                        .nickname(userDO.getNickname())
                        .avatar(userDO.getAvatar())
                        .build())
                .build();

        log.info("==> 用户登录成功, userId: {}, mobile: {}", userDO.getId(), mobile);

        return Response.success(loginUserRspVO);
    }

    /**
     * 发送验证码
     *
     * @param sendVerifyCodeReqVO
     * @return
     */
    @Override
    public Response<?> sendVerifyCode(SendVerifyCodeReqVO sendVerifyCodeReqVO) {
        String mobile = sendVerifyCodeReqVO.getMobile();
        Integer type = sendVerifyCodeReqVO.getType();

        // // 行为验证码二次校验
        String captchaId = sendVerifyCodeReqVO.getCaptchaId();
        if (StrUtil.isBlank(captchaId)) {
            throw new BizException(ResponseCodeEnum.CAPTCHA_VERIFICATION_FAILED);
        }

        // 判断 ImageCaptchaApplication 是否支持二次校验
        boolean verified = false;
        if (imageCaptchaApplication instanceof SecondaryVerificationApplication) {
            verified = ((SecondaryVerificationApplication) imageCaptchaApplication).secondaryVerification(captchaId);
        }
        if (!verified) {
            throw new BizException(ResponseCodeEnum.CAPTCHA_VERIFICATION_FAILED);
        }

        // 判断验证码类型是否合法
        VerifyCodeTypeEnum verifyCodeType = VerifyCodeTypeEnum.valueOf(type);
        if (Objects.isNull(verifyCodeType)) {
            throw new BizException(ResponseCodeEnum.VERIFY_CODE_TYPE_ERROR);
        }

        // 发送频率限制：检查是否在 60 秒内重复发送
        String limitKey = VERIFY_CODE_LIMIT_KEY_PREFIX + verifyCodeType.getPurpose() + ":" + mobile;

        // 如果 Key 已存在（60 秒内已发送过），返回 false；不存在则创建 Key 并返回 true
        Boolean absent = stringRedisTemplate.opsForValue()
                .setIfAbsent(limitKey, "1", VERIFY_CODE_LIMIT_SECONDS, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(absent)) {
            throw new BizException(ResponseCodeEnum.VERIFY_CODE_SEND_TOO_FREQUENT);
        }

        // 每日发送次数限制：同一手机号、同一场景，每天最多发送 10 条
        String dailyLimitKey = VERIFY_CODE_DAILY_LIMIT_KEY_PREFIX + verifyCodeType.getPurpose()
                + ":" + mobile + ":" + LocalDate.now();

        // 计算从当前时间，到第二天凌晨零点之间还剩下多少秒
        long secondsUntilMidnight = Duration.between(
                LocalDateTime.now(),
                LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIDNIGHT)
        ).getSeconds();

        // 执行 Lua 脚本：原子性地检查每日发送次数并累加
        Long dailyCount = stringRedisTemplate.execute(checkAndIncrementDailyLimitScript,
                Collections.singletonList(dailyLimitKey),
                String.valueOf(VERIFY_CODE_DAILY_LIMIT),
                String.valueOf(secondsUntilMidnight));

        // 如果已经超过 10 条，抛出业务异常
        if (Objects.nonNull(dailyCount) && dailyCount == -1) {
            throw new BizException(ResponseCodeEnum.VERIFY_CODE_DAILY_LIMIT_EXCEEDED);
        }

        // 生成 6 位随机数字验证码
        String verifyCode = RandomUtil.randomNumbers(6);

        // 构建验证码 Redis Key
        String redisKey = VERIFY_CODE_KEY_PREFIX + verifyCodeType.getPurpose() + ":" + mobile;

        // 缓存验证码（5 分钟 TTL）
        stringRedisTemplate.opsForValue().set(redisKey, verifyCode, VERIFY_CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

        // 异步发送短信验证码
        bizExecutor.execute(() -> sendSms(mobile, verifyCode));

        return Response.success();
    }

    /**
     * 退出登录
     *
     * @return
     */
    @Override
    public Response<?> logout() {
        // 获取当前请求中的 Token 值
        String tokenValue = StpUtil.getTokenValue();
        // 获取当前登录用户的 ID
        Object userId = StpUtil.getLoginId();

        // 调用 SaToken 的退出登录方法
        // 此方法会自动从请求头中获取 Token，然后清除该 Token 对应的会话信息
        StpUtil.logout();

        log.info("==> 用户退出登录, userId: {}, token: {}", userId, tokenValue);

        return Response.success();
    }

    /**
     * 发送短信验证码（异步执行，由线程池调度）
     *
     * @param mobile     手机号
     * @param verifyCode 验证码
     */
    private void sendSms(String mobile, String verifyCode) {
        try {
            // TODO: 调用短信服务商 API 发送验证码

            // 开发阶段通过日志打印验证码，方便调试
            log.info("==> 验证码发送成功, mobile: {}, verifyCode: {}", mobile, verifyCode);
        } catch (Exception e) {
            log.error("==> 验证码发送失败, mobile: {}, verifyCode: {}", mobile, verifyCode, e);
        }
    }

    /**
     * 校验密码
     *
     * @param rawPassword     明文密码
     * @param encodedPassword 加密后的密码
     * @param mobile 手机号（用于构建 Redis Key）
     */
    private void checkPassword(String rawPassword, String encodedPassword, String mobile) {
        // 密码不能为空
        if (StrUtil.isBlank(rawPassword)) {
            // 登录失败次数 +1
            addLoginFailCount(mobile);

            throw new BizException(ResponseCodeEnum.USER_LOGIN_CREDENTIAL_ERROR);
        }

        // 使用 BCrypt 校验明文密码和密文密码是否匹配
        boolean matches = PASSWORD_ENCODER.matches(rawPassword, encodedPassword);

        if (!matches) {
            // 登录失败次数 +1
            addLoginFailCount(mobile);

            throw new BizException(ResponseCodeEnum.USER_LOGIN_CREDENTIAL_ERROR);
        }

        // 密码校验成功，清除登录失败次数
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + mobile;
        stringRedisTemplate.delete(failCountKey);
    }

    /**
     * 校验验证码
     *
     * @param verifyCode 验证码
     */
    private void checkVerifyCode(String verifyCode, String mobile, String purpose) {
        // 验证码不能为空
        if (StrUtil.isBlank(verifyCode)) {
            throw new BizException(ResponseCodeEnum.USER_VERIFY_CODE_ERROR);
        }

        // 构建 Redis Key
        String redisKey = VERIFY_CODE_KEY_PREFIX + purpose + ":" + mobile;

        // 执行 Lua 脚本：原子性地比对验证码并删除（匹配返回 1; 不匹配或 Key 不存在返回 0）
        Long result = stringRedisTemplate.execute(checkAndDeleteVerifyCodeScript,
                Collections.singletonList(redisKey),
                verifyCode);

        // 验证码错误或已过期
        if (result == null || result == 0) {
            throw new BizException(ResponseCodeEnum.USER_VERIFY_CODE_ERROR);
        }

        // 能走到这里，说明 result == 1， 验证码比对成功，并且验证码已被删除
    }

    /**
     * 生成随机昵称
     * 格式：用户 + 6 位随机数字，如：用户382910
     *
     * @return 昵称
     */
    private String generateNickname() {
        return "用户" + RandomUtil.randomNumbers(6);
    }

    /**
     * 检查登录失败次数是否超限
     *
     * @param mobile 手机号
     */
    private void checkLoginFailLimit(String mobile) {
        // 构建 Redis Key
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + mobile;

        // 查询 Redis 缓存中的计数
        String failCountStr = stringRedisTemplate.opsForValue().get(failCountKey);

        // 判断登录失败次数是否超过上限
        if (StrUtil.isNotBlank(failCountStr)) {
            int failCount = Integer.parseInt(failCountStr);
            if (failCount >= LOGIN_FAIL_MAX_COUNT) {
                throw new BizException(ResponseCodeEnum.LOGIN_FAIL_TOO_MANY);
            }
        }
    }

    /**
     * 累加登录失败次数（Lua 脚本原子操作）
     *
     * @param mobile 手机号
     */
    private void addLoginFailCount(String mobile) {
        // 构建 Redis Key
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + mobile;

        // 执行 Lua 脚本：原子性地检查失败次数并累加（超限返回 -1; 未超限返回累加后的值）
        Long result = stringRedisTemplate.execute(checkAndIncrementLoginFailScript,
                Collections.singletonList(failCountKey),
                String.valueOf(LOGIN_FAIL_MAX_COUNT),
                String.valueOf(LOGIN_LOCK_MINUTES * 60));

        // 失败次数已达上限，直接拒绝
        if (Objects.nonNull(result) && result == -1) {
            throw new BizException(ResponseCodeEnum.LOGIN_FAIL_TOO_MANY);
        }
    }
}
