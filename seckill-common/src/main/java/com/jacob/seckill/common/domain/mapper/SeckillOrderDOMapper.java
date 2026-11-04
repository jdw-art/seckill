package com.jacob.seckill.common.domain.mapper;

import com.jacob.seckill.common.domain.dataobject.SeckillOrderDO;
import org.apache.ibatis.annotations.Param;

public interface SeckillOrderDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(SeckillOrderDO record);

    int insertSelective(SeckillOrderDO record);

    SeckillOrderDO selectByPrimaryKey(Long id);

    /**
     * 按订单号 + 用户 ID 查询订单
     */
    SeckillOrderDO selectByOrderNoAndUserId(@Param("orderNo") String orderNo,
                                            @Param("userId") Long userId);

    int updateByPrimaryKeySelective(SeckillOrderDO record);

    int updateByPrimaryKey(SeckillOrderDO record);
}
