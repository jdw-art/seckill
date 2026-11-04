package com.jacob.seckill.order.service;

import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.order.model.dto.SeckillOrderMqDTO;
import com.jacob.seckill.order.model.vo.DoSeckillReqVO;
import com.jacob.seckill.order.model.vo.DoSeckillRspVO;
import com.jacob.seckill.order.model.vo.FindSeckillOrderResultReqVO;
import com.jacob.seckill.order.model.vo.FindSeckillOrderResultRspVO;

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

    /**
     * 异步消费秒杀下单消息：扣减库存 + 创建订单
     *
     * @param message
     */
    void createSeckillOrder(SeckillOrderMqDTO message);

    /**
     * 查询秒杀订单处理结果
     *
     * @param reqVO
     * @return
     */
    Response<FindSeckillOrderResultRspVO> findSeckillOrderResult(FindSeckillOrderResultReqVO reqVO);
}
