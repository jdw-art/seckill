package com.jacob.seckill.goods.controller;

import com.jacob.seckill.common.aspect.ApiOperationLog;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsDetailReqVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsDetailRspVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsListReqVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsListRspVO;
import com.jacob.seckill.goods.service.GoodsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 商品模块
 **/
@RestController
@RequestMapping("/seckill/goods")
@Slf4j
public class GoodsController {

    @Resource
    private GoodsService goodsService;

    /**
     * 查询秒杀商品列表
     *
     * @param reqVO
     * @return
     */
    @PostMapping("/list")
    @ApiOperationLog(description = "查询秒杀商品列表")
    public Response<List<FindSeckillGoodsListRspVO>> getSeckillGoodsList(@RequestBody @Validated FindSeckillGoodsListReqVO reqVO) {
        return goodsService.findSeckillGoodsList(reqVO);
    }

    /**
     * 查询秒杀商品详情
     *
     * @param reqVO
     * @return
     */
    @PostMapping("/detail")
    @ApiOperationLog(description = "查询秒杀商品详情")
    public Response<FindSeckillGoodsDetailRspVO> getSeckillGoodsDetail(@RequestBody @Validated FindSeckillGoodsDetailReqVO reqVO) {
        return goodsService.findSeckillGoodsDetail(reqVO);
    }
}
