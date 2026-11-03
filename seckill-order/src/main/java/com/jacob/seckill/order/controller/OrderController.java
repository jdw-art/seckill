package com.jacob.seckill.order.controller;

import com.jacob.seckill.common.aspect.ApiOperationLog;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.order.model.vo.DoSeckillReqVO;
import com.jacob.seckill.order.model.vo.DoSeckillRspVO;
import com.jacob.seckill.order.service.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:52
 * @Version: v1.0.0
 * @Description: 订单模块
 **/
@RestController
@RequestMapping("/seckill/order")
@Slf4j
public class OrderController {

    @Resource
    private OrderService orderService;

    /**
     * 秒杀下单
     *
     * @param reqVO
     * @return
     */
    @PostMapping
    @ApiOperationLog(description = "秒杀下单")
    public Response<DoSeckillRspVO> doSeckill(@RequestBody @Validated DoSeckillReqVO reqVO) {
        return orderService.doSeckill(reqVO);
    }
}
