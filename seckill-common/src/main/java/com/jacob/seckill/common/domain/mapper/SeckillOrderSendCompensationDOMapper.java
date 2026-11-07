package com.jacob.seckill.common.domain.mapper;

import com.jacob.seckill.common.domain.dataobject.SeckillOrderSendCompensationDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author: jacob
 * @Date: 2026/11/7 23:00
 * @Version: v1.0.0
 * @Description: 秒杀订单发送失败补偿表 Mapper
 **/
public interface SeckillOrderSendCompensationDOMapper {

    int insert(SeckillOrderSendCompensationDO record);

    /**
     * 按订单号查询补偿记录（判断是否已登记过）
     */
    SeckillOrderSendCompensationDO selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询可重发的待重试记录（状态为待重试，并且已到达下次重发时间）
     *
     * @param now
     * @param limit
     * @return
     */
    List<SeckillOrderSendCompensationDO> selectPendingList(@Param("now") LocalDateTime now,
                                                           @Param("limit") Integer limit);

    /**
     * 按订单号更新补偿状态（标记为已补偿 / 待人工处理）
     *
     * @param orderNo
     * @param status
     * @return
     */
    int updateStatusByOrderNo(@Param("orderNo") String orderNo, @Param("status") Integer status);

    /**
     * 按订单号更新重试计划（重试次数 +1、下次重发时间退避拉长、记录失败原因）
     */
    int updateRetryPlan(@Param("orderNo") String orderNo,
                        @Param("retryCount") Integer retryCount,
                        @Param("nextRetryTime") LocalDateTime nextRetryTime,
                        @Param("failReason") String failReason);
}
