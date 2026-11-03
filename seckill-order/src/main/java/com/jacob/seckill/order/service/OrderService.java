package com.jacob.seckill.order.service;

import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.order.model.vo.DoSeckillReqVO;
import com.jacob.seckill.order.model.vo.DoSeckillRspVO;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:52
 * @Version: v1.0.0
 * @Description: 订单模块业务
 **/
public interface OrderService {

    /**
     * 秒杀下单
     *
     * @param reqVO
     * @return
     */
    Response<DoSeckillRspVO> doSeckill(DoSeckillReqVO reqVO);
}
