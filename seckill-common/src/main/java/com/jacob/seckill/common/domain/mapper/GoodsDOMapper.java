package com.jacob.seckill.common.domain.mapper;

import com.jacob.seckill.common.domain.dataobject.GoodsDO;

import java.util.List;

public interface GoodsDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(GoodsDO record);

    int insertSelective(GoodsDO record);

    GoodsDO selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(GoodsDO record);

    int updateByPrimaryKey(GoodsDO record);

    /**
     * 根据主键批量查询商品
     *
     * @param ids
     * @return
     */
    List<GoodsDO> selectByIds(List<Long> ids);
}
