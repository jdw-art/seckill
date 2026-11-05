package com.jacob.seckill.goods.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.jacob.seckill.common.constant.RedisKeyConstants;
import com.jacob.seckill.common.domain.dataobject.GoodsDO;
import com.jacob.seckill.common.domain.dataobject.GoodsDetailDO;
import com.jacob.seckill.common.domain.dataobject.GoodsImgDO;
import com.jacob.seckill.common.domain.dataobject.SeckillActivityDO;
import com.jacob.seckill.common.domain.dataobject.SeckillGoodsDO;
import com.jacob.seckill.common.domain.mapper.GoodsDOMapper;
import com.jacob.seckill.common.domain.mapper.GoodsDetailDOMapper;
import com.jacob.seckill.common.domain.mapper.GoodsImgDOMapper;
import com.jacob.seckill.common.domain.mapper.SeckillActivityDOMapper;
import com.jacob.seckill.common.domain.mapper.SeckillGoodsDOMapper;
import com.jacob.seckill.common.enums.ActivityStatusEnum;
import com.jacob.seckill.common.enums.ResponseCodeEnum;
import com.jacob.seckill.common.exception.BizException;
import com.jacob.seckill.common.model.dto.SeckillActivityGoodsMetaDTO;
import com.jacob.seckill.common.utils.JsonUtils;
import com.jacob.seckill.common.utils.Response;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsDetailReqVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsDetailRspVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsListReqVO;
import com.jacob.seckill.goods.model.vo.FindSeckillGoodsListRspVO;
import com.jacob.seckill.goods.service.GoodsService;
import com.jacob.seckill.goods.utils.SeckillGoodsStockUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @Author: jacob
 * @Date: 2026/11/3 22:42
 * @Version: v1.0.0
 * @Description: 商品模块业务
 **/
@Service
@Slf4j
public class GoodsServiceImpl implements GoodsService {

    @Resource
    private SeckillGoodsDOMapper seckillGoodsDOMapper;

    @Resource
    private SeckillActivityDOMapper seckillActivityDOMapper;

    @Resource
    private GoodsDOMapper goodsDOMapper;

    @Resource
    private GoodsImgDOMapper goodsImgDOMapper;

    @Resource
    private GoodsDetailDOMapper goodsDetailDOMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private Cache<String, String> goodsListLocalCache;

    @Resource
    private Cache<String, String> goodsDetailLocalCache;

    /**
     * 查询秒杀商品列表
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<List<FindSeckillGoodsListRspVO>> findSeckillGoodsList(FindSeckillGoodsListReqVO reqVO) {
        // 活动 ID
        Long activityId = reqVO.getActivityId();
        log.info("==> 查询秒杀商品列表, activityId: {}", activityId);

        // 构建 Redis 缓存 Key
        String redisKey = RedisKeyConstants.GOODS_LIST_PREFIX + activityId;

        // L1: 先查 Caffeine 本地缓存（微秒级，无网络开销）
        String localCachedValue = goodsListLocalCache.getIfPresent(redisKey);

        if (StrUtil.isNotBlank(localCachedValue)) {
            log.info("==> 命中本地缓存（L1）, key: {}", redisKey);

            // 手动将 String 字符串，反序列化为商品列表
            List<FindSeckillGoodsListRspVO> cachedList = processCachedGoodsList(localCachedValue, activityId);

            return Response.success(cachedList);
        }

        // 第一道防线：布隆过滤器校验活动是否存在
        // 如果布隆过滤器返回 "不存在"，绝对正确，说明该活动 ID 一定不合法，直接拒绝掉
        RBloomFilter<Long> activityBloom = redissonClient.getBloomFilter(RedisKeyConstants.SECKILL_ACTIVITY_BLOOM_KEY);

        if (activityBloom.isExists() && !activityBloom.contains(activityId)) {
            log.info("==> 布隆过滤器拦截：活动不存在, activityId: {}", activityId);
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
        }

        // L2: 查 Redis 缓存
        String redisJsonValue = stringRedisTemplate.opsForValue().get(redisKey);

        // 若缓存不为空
        if (StrUtil.isNotBlank(redisJsonValue)) {

            // 防止缓存穿透，判断缓存是否是 NULL
            if (Objects.equals(RedisKeyConstants.NULL_CACHE_VALUE, redisJsonValue)) {
                log.info("==> 命中空值缓存，活动不存在, redisKey: {}", redisKey);
                throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
            }

            log.info("==> 命中商品列表 Redis 缓存（L2）, redisKey: {}", redisKey);

            // 手动将 String 字符串，反序列化为商品列表
            List<FindSeckillGoodsListRspVO> cachedList = processCachedGoodsList(redisJsonValue, activityId);

            // 能走到这里，说明 L1 本地缓存未命中，需要回填，以便后续请求能够命中 L1
            goodsListLocalCache.put(redisKey, redisJsonValue);

            return Response.success(cachedList);
        }

        // 1. 查询活动信息
        SeckillActivityDO activityDO = seckillActivityDOMapper.selectByPrimaryKey(activityId);
        if (Objects.isNull(activityDO)) {
            // 缓存空值，防止缓存穿透
            cacheNullValue(redisKey);

            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
        }

        // 2. 根据活动 ID 查询该活动下的所有秒杀商品
        List<SeckillGoodsDO> seckillGoodsDOS = seckillGoodsDOMapper.selectByActivityId(activityId);
        if (CollUtil.isEmpty(seckillGoodsDOS)) {
            log.info("==> 该活动下暂无秒杀商品, activityId: {}", activityId);
            return Response.success(Collections.emptyList());
        }

        // 3. 批量查询关联的商品信息（目的是为了获取对应秒杀商品的原价）
        List<Long> goodsIds = seckillGoodsDOS.stream()
                .map(SeckillGoodsDO::getGoodsId)
                .collect(Collectors.toList());

        // 一次性批量查询所有商品，提升查询性能
        List<GoodsDO> goodsDOS = goodsDOMapper.selectByIds(goodsIds);

        // 将商品 ID 和商品信息映射为 Map，方便后续查找
        Map<Long, GoodsDO> goodsMap = goodsDOS.stream()
                .collect(Collectors.toMap(GoodsDO::getId, goodsDO -> goodsDO));

        // 4. 计算活动状态（基于当前时间动态判断）
        ActivityStatusEnum activityStatusEnum = calculateActivityStatus(activityDO);

        // 5. 组装响应数据
        List<FindSeckillGoodsListRspVO> rspVOS = new ArrayList<>();
        for (SeckillGoodsDO seckillGoodsDO : seckillGoodsDOS) {
            FindSeckillGoodsListRspVO rspVO = new FindSeckillGoodsListRspVO();
            rspVO.setId(seckillGoodsDO.getId());
            rspVO.setGoodsId(seckillGoodsDO.getGoodsId());  // 设置商品 ID
            rspVO.setActivityId(seckillGoodsDO.getActivityId());
            rspVO.setSeckillTitle(seckillGoodsDO.getSeckillTitle());
            rspVO.setSeckillImg(seckillGoodsDO.getSeckillImg());
            rspVO.setSeckillPrice(seckillGoodsDO.getSeckillPrice());
            rspVO.setSeckillTotal(seckillGoodsDO.getSeckillTotal());
            rspVO.setSeckillStock(seckillGoodsDO.getSeckillStock());
            rspVO.setActivityStatus(activityStatusEnum.getStatus());
            rspVO.setBeginTime(activityDO.getBeginTime());
            rspVO.setEndTime(activityDO.getEndTime());

            // 设置商品原价
            GoodsDO goodsDO = goodsMap.get(seckillGoodsDO.getGoodsId());
            if (Objects.nonNull(goodsDO)) {
                rspVO.setGoodsPrice(goodsDO.getGoodsPrice());
            }

            rspVOS.add(rspVO);
        }

        // DB 中的 seckill_stock 只是兜底值
        // 活动期间页面展示的实时库存，仍然优先从 Redis 秒杀库存 Key 中组合
        supplementStock(rspVOS, activityId);

        // 将商品列表写入 Redis 缓存和本地缓存
        log.info("==> 商品列表缓存未命中，将数据写入 Redis 和本地缓存中, Key: {}", redisKey);

        String jsonValue = JsonUtils.toJsonString(rspVOS);

        // 写入本地缓存
        goodsListLocalCache.put(redisKey, jsonValue);

        // 动态计算缓存过期时间
        Long ttlSeconds = RedisKeyConstants.calculateTtlSeconds(activityDO.getEndTime());

        if (Objects.nonNull(ttlSeconds) && ttlSeconds > 0) {
            // 活动未结束：动态 TTL = (endTime - now) + 30分钟的安全缓冲
            stringRedisTemplate.opsForValue().set(redisKey, jsonValue,
                    ttlSeconds, TimeUnit.SECONDS);
        } else {
            // 活动已结束：设置一个较短的 TTL 过期时间，防止余温流量打穿 DB
            stringRedisTemplate.opsForValue().set(redisKey, jsonValue,
                    RedisKeyConstants.ENDED_ACTIVITY_TTL_MINUTES, TimeUnit.MINUTES);
        }

        return Response.success(rspVOS);
    }

    /**
     * 预热指定活动的商品缓存
     *
     * @param activityId
     */
    @Override
    public Response<?> preheatActivityGoods(Long activityId) {
        log.info("==> 开始预热活动商品缓存, activityId: {}", activityId);

        // 1. 查询活动信息（获取活动结束时间，用于计算动态 TTL）
        SeckillActivityDO activityDO = seckillActivityDOMapper.selectByPrimaryKey(activityId);
        if (Objects.isNull(activityDO)) {
            log.info("==> 预热跳过：活动不存在, activityId: {}", activityId);
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
        }

        // 2. 校验活动是否已结束
        LocalDateTime endTime = activityDO.getEndTime();
        if (Objects.isNull(endTime) || !LocalDateTime.now().isBefore(endTime)) {
            log.info("==> 预热跳过：活动已结束, activityId: {}", activityId);
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_ENDED);
        }

        // 3. 计算动态缓存 TTL 过期时间
        Long ttlSeconds = RedisKeyConstants.calculateTtlSeconds(endTime);
        if (Objects.isNull(ttlSeconds) || ttlSeconds <= 0) {
            throw new IllegalStateException("秒杀活动缓存 TTL 计算失败");
        }

        // 3. 查询该活动下所有秒杀商品
        List<SeckillGoodsDO> seckillGoodsDOS = seckillGoodsDOMapper.selectByActivityId(activityId);
        if (CollUtil.isEmpty(seckillGoodsDOS)) {
            log.info("==> 预热跳过：活动下无商品, activityId: {}", activityId);
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_GOODS_EMPTY);
        }

        // 初始化活动布隆过滤器
        RBloomFilter<Long> activityBloom = redissonClient.getBloomFilter(RedisKeyConstants.SECKILL_ACTIVITY_BLOOM_KEY);
        // 初始化之前，如果之前已经创建了，先删除掉
        activityBloom.delete();
        // 预期插入一万个活动，误判率为 1%
        activityBloom.tryInit(10000L, 0.01);
        // 写入活动 ID
        activityBloom.add(activityId);
        // 设置过期时间，防止布隆过滤器一直占用着 Redis 内存
        redissonClient.getKeys().expire(RedisKeyConstants.SECKILL_ACTIVITY_BLOOM_KEY, 7, TimeUnit.DAYS);

        log.info("==> 活动布隆过滤器写入成功, activityId: {}", activityId);

        // 初始化商品布隆过滤器
        RBloomFilter<String> goodsBloom = redissonClient.getBloomFilter(RedisKeyConstants.SECKILL_GOODS_BLOOM_KEY);
        // 初始化之前，如果之前已经创建了，先删除掉
        goodsBloom.delete();
        // 预期插入十万个商品，误判率为 1%
        goodsBloom.tryInit(100000L, 0.01);
        // 写入活动下所有商品
        seckillGoodsDOS.forEach(seckillGoodsDO -> {
            goodsBloom.add(activityId + ":" + seckillGoodsDO.getGoodsId());
        });
        // 设置过期时间，防止布隆过滤器一直占用着 Redis 内存
        redissonClient.getKeys().expire(RedisKeyConstants.SECKILL_GOODS_BLOOM_KEY, 7, TimeUnit.DAYS);

        log.info("==> 商品布隆过滤器写入成功, activityId: {}, 商品数: {}", activityId, seckillGoodsDOS.size());

        // 4. 批量查询商品原价
        List<Long> goodsIds = seckillGoodsDOS.stream()
                .map(SeckillGoodsDO::getGoodsId)
                .collect(Collectors.toList());

        List<GoodsDO> goodsDOS = goodsDOMapper.selectByIds(goodsIds);

        // 转 Map, 方便后续查对应商品的原价
        Map<Long, GoodsDO> goodsMap = goodsDOS.stream()
                .collect(Collectors.toMap(GoodsDO::getId, goodsDO -> goodsDO));

        // 5. 预热商品列表缓存
        String listKey = RedisKeyConstants.GOODS_LIST_PREFIX + activityId;
        List<FindSeckillGoodsListRspVO> listRspVOS = new ArrayList<>();
        for (SeckillGoodsDO sg : seckillGoodsDOS) {
            FindSeckillGoodsListRspVO vo = new FindSeckillGoodsListRspVO();
            vo.setId(sg.getId());
            vo.setGoodsId(sg.getGoodsId());
            vo.setActivityId(sg.getActivityId());
            vo.setSeckillTitle(sg.getSeckillTitle());
            vo.setSeckillImg(sg.getSeckillImg());
            vo.setSeckillPrice(sg.getSeckillPrice());
            vo.setSeckillTotal(sg.getSeckillTotal());
            vo.setSeckillStock(sg.getSeckillStock());
            vo.setActivityStatus(calculateActivityStatus(activityDO).getStatus());
            vo.setBeginTime(activityDO.getBeginTime());
            vo.setEndTime(activityDO.getEndTime());

            // 设置商品原价
            GoodsDO goodsDO = goodsMap.get(sg.getGoodsId());
            if (Objects.nonNull(goodsDO)) {
                vo.setGoodsPrice(goodsDO.getGoodsPrice());
            }

            listRspVOS.add(vo);
        }

        stringRedisTemplate.opsForValue().set(listKey, JsonUtils.toJsonString(listRspVOS),
                ttlSeconds, TimeUnit.SECONDS);
        log.info("==> 预热商品列表缓存成功, key: {}, TTL: {}s", listKey, ttlSeconds);

        // 6. 预热每个商品的详情缓存
        for (SeckillGoodsDO sg : seckillGoodsDOS) {
            String detailKey = RedisKeyConstants.GOODS_DETAIL_PREFIX + activityId + ":" + sg.getGoodsId();

            // 查询商品基本信息
            GoodsDO goodsDO = goodsDOMapper.selectByPrimaryKey(sg.getGoodsId());

            // 查询商品轮播图
            List<GoodsImgDO> goodsImgDOS = goodsImgDOMapper.selectByGoodsId(sg.getGoodsId());
            List<String> goodsImgs = null;
            if (CollUtil.isNotEmpty(goodsImgDOS)) {
                goodsImgs = goodsImgDOS.stream()
                        .map(GoodsImgDO::getImgUrl)
                        .toList();
            }

            // 查询商品详情 HTML
            GoodsDetailDO goodsDetailDO = goodsDetailDOMapper.selectByGoodsId(sg.getGoodsId());

            // 组装详情 VO
            FindSeckillGoodsDetailRspVO detailVO = FindSeckillGoodsDetailRspVO.builder()
                    .id(sg.getId())
                    .goodsId(sg.getGoodsId())
                    .activityId(sg.getActivityId())
                    .seckillPrice(sg.getSeckillPrice())
                    .seckillTotal(sg.getSeckillTotal())
                    .seckillStock(sg.getSeckillStock())
                    .activityStatus(calculateActivityStatus(activityDO).getStatus())
                    .beginTime(activityDO.getBeginTime())
                    .endTime(activityDO.getEndTime())
                    .goodsImgs(goodsImgs)
                    .build();

            // 设置商品名称和原价
            if (Objects.nonNull(goodsDO)) {
                detailVO.setGoodsName(goodsDO.getGoodsName());
                detailVO.setGoodsPrice(goodsDO.getGoodsPrice());
            }

            // 设置商品详情 HTML
            if (Objects.nonNull(goodsDetailDO)) {
                detailVO.setGoodsDetail(goodsDetailDO.getDetailContent());
            }

            stringRedisTemplate.opsForValue().set(detailKey, JsonUtils.toJsonString(detailVO),
                    ttlSeconds, TimeUnit.SECONDS);
        }

        log.info("==> 预热活动 {} 的 {} 个商品详情缓存完成", activityId, seckillGoodsDOS.size());

        // 7. 预热秒杀库存
        for (SeckillGoodsDO seckillGoodsDO : seckillGoodsDOS) {
            String stockKey = RedisKeyConstants.buildSeckillStockKey(activityId, seckillGoodsDO.getGoodsId());
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(seckillGoodsDO.getSeckillStock()),
                    ttlSeconds, TimeUnit.SECONDS);

            log.info("==> 预热秒杀库存成功, key: {}, stock: {}, TTL: {}s",
                    stockKey, seckillGoodsDO.getSeckillStock(), ttlSeconds);
        }

        // 8. 预热秒杀活动商品元数据，供下单入口直接从 Redis 读取
        for (SeckillGoodsDO seckillGoodsDO : seckillGoodsDOS) {
            String metaKey = RedisKeyConstants.buildSeckillActivityGoodsMetaKey(activityId, seckillGoodsDO.getGoodsId());

            // 复用前面已批量查询到的商品信息，避免再次查库
            GoodsDO goodsDO = goodsMap.get(seckillGoodsDO.getGoodsId());

            SeckillActivityGoodsMetaDTO metaDTO = SeckillActivityGoodsMetaDTO.builder()
                    .seckillGoodsId(seckillGoodsDO.getId())
                    .activityId(activityId)
                    .goodsId(seckillGoodsDO.getGoodsId())
                    .goodsName(Objects.nonNull(goodsDO) ? goodsDO.getGoodsName() : null)
                    .goodsImg(Objects.nonNull(goodsDO) ? goodsDO.getGoodsImg() : null)
                    .seckillPrice(seckillGoodsDO.getSeckillPrice())
                    .beginTime(activityDO.getBeginTime())
                    .endTime(activityDO.getEndTime())
                    .build();

            stringRedisTemplate.opsForValue().set(metaKey, JsonUtils.toJsonString(metaDTO),
                    ttlSeconds, TimeUnit.SECONDS);

            log.info("==> 预热秒杀活动商品元数据成功, key: {}, TTL: {}s", metaKey, ttlSeconds);
        }

        return Response.success();
    }

    /**
     * 处理缓存命中的商品列表数据: 反序列化 → 补充库存 → 重新计算活动状态
     *
     * @param redisJsonValue
     * @param activityId
     * @return
     */
    private List<FindSeckillGoodsListRspVO> processCachedGoodsList(String redisJsonValue, Long activityId) {
        // 缓存命中
        // 手动将 String 字符串，反序列化为商品列表
        List<FindSeckillGoodsListRspVO> cachedList = JsonUtils
                .parseArray(redisJsonValue, FindSeckillGoodsListRspVO.class);

        // 如果集合为空，直接返回，说明活动下暂无商品
        if (CollUtil.isEmpty(cachedList)) {
            return cachedList;
        }

        // 设置库存字段值（从 Redis 中获取实时库存）
        supplementStock(cachedList, activityId);

        // 实时重新计算活动状态
        FindSeckillGoodsListRspVO first = cachedList.get(0);
        ActivityStatusEnum activityStatusEnum = calculateActivityStatus(first.getBeginTime(), first.getEndTime());
        cachedList.forEach(item ->
                item.setActivityStatus(activityStatusEnum.getStatus()));
        return cachedList;
    }

    /**
     * 处理缓存命中的商品详情数据: 反序列化 → 补充库存 → 重新计算活动状态
     *
     * @param redisJsonValue
     * @param activityId
     * @param goodsId
     * @return
     */
    private FindSeckillGoodsDetailRspVO processCachedGoodsDetail(String redisJsonValue, Long activityId, Long goodsId) {
        // 缓存命中
        // 手动将 String 字符串，反序列化为商品详情对象
        FindSeckillGoodsDetailRspVO cachedDetail = JsonUtils
                .parseObject(redisJsonValue, FindSeckillGoodsDetailRspVO.class);

        // 设置库存字段值（从 Redis 中获取实时库存）
        supplementStock(cachedDetail, activityId, goodsId);

        // 实时重新计算活动状态
        ActivityStatusEnum activityStatusEnum = calculateActivityStatus(
                cachedDetail.getBeginTime(), cachedDetail.getEndTime());
        cachedDetail.setActivityStatus(activityStatusEnum.getStatus());

        return cachedDetail;
    }

    /**
     * 缓存空值，防止缓存穿透
     *
     * @param redisKey
     */
    private void cacheNullValue(String redisKey) {
        // 当数据库中查不到数据时，往 Redis 写入一个空值标记，短时间内不再查 DB
        stringRedisTemplate.opsForValue().set(redisKey, RedisKeyConstants.NULL_CACHE_VALUE,
                RedisKeyConstants.NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("==> 缓存空值，防止穿透, redisKey: {}, TTL: {}min", redisKey, RedisKeyConstants.NULL_CACHE_TTL_MINUTES);
    }

    /**
     * 实时补充库存字段（优先读取 Redis 秒杀库存 Key，避免商品缓存中的库存快照数据不实时）
     *
     * @param goodsList  缓存中的商品列表
     * @param activityId 活动 ID
     */
    private void supplementStock(List<FindSeckillGoodsListRspVO> goodsList, Long activityId) {
        if (CollUtil.isEmpty(goodsList)) {
            return;
        }

        // 批量构建秒杀库存 Key，后面用于 MGET 一次性读取库存数据
        List<String> stockKeys = goodsList.stream()
                .map(item -> RedisKeyConstants.buildSeckillStockKey(activityId, item.getGoodsId()))
                .toList();

        List<String> stockValues = stringRedisTemplate.opsForValue().multiGet(stockKeys);
        for (int i = 0; i < goodsList.size(); i++) {
            FindSeckillGoodsListRspVO rspVO = goodsList.get(i);
            String stockValue = getValue(stockValues, i);
            rspVO.setSeckillStock(SeckillGoodsStockUtils.resolveDisplayStock(stockValue, rspVO.getSeckillStock()));
        }
    }

    /**
     * 实时补充商品详情中的库存字段
     */
    private void supplementStock(FindSeckillGoodsDetailRspVO rspVO, Long activityId, Long goodsId) {
        // 从 Redis 中查询库存
        String stockKey = RedisKeyConstants.buildSeckillStockKey(activityId, goodsId);
        String stockValue = stringRedisTemplate.opsForValue().get(stockKey);

        // 对返参 VO 设置库存值
        rspVO.setSeckillStock(SeckillGoodsStockUtils.resolveDisplayStock(stockValue, rspVO.getSeckillStock()));
    }

    /**
     * 安全获取列表指定下标的值
     *
     * @param values Redis 批量查询结果
     * @param index  下标
     * @return 指定下标对应的值
     */
    private String getValue(List<String> values, int index) {
        if (Objects.isNull(values) || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }

    /**
     * 查询秒杀商品详情
     *
     * @param reqVO
     * @return
     */
    @Override
    public Response<FindSeckillGoodsDetailRspVO> findSeckillGoodsDetail(FindSeckillGoodsDetailReqVO reqVO) {
        // 商品 ID
        Long goodsId = reqVO.getGoodsId();
        // 活动 ID
        Long activityId = reqVO.getActivityId();
        log.info("==> 查询秒杀商品详情, goodsId: {}, activityId: {}", goodsId, activityId);

        // 构建 Redis 缓存 Key
        String redisKey = RedisKeyConstants.GOODS_DETAIL_PREFIX + activityId + ":" + goodsId;

        // L1: 先查 Caffeine 本地缓存（微秒级，无网络开销）
        String localCachedValue = goodsDetailLocalCache.getIfPresent(redisKey);
        if (StrUtil.isNotBlank(localCachedValue)) {
            log.info("==> 命中本地缓存（L1）, key: {}", redisKey);

            // 手动将 String 字符串，反序列化为商品详情对象, 并响应
            return Response.success(processCachedGoodsDetail(localCachedValue, activityId, goodsId));
        }

        // 第一道防线：布隆过滤器校验活动是否存在
        RBloomFilter<Long> activityBloom = redissonClient.getBloomFilter(RedisKeyConstants.SECKILL_ACTIVITY_BLOOM_KEY);

        if (activityBloom.isExists() && !activityBloom.contains(activityId)) {
            log.info("==> 布隆过滤器拦截：活动不存在, activityId: {}", activityId);
            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
        }

        // 第二道防线：布隆过滤器校验活动下的商品是否存在
        RBloomFilter<String> goodsBloom = redissonClient.getBloomFilter(RedisKeyConstants.SECKILL_GOODS_BLOOM_KEY);

        if (goodsBloom.isExists() && !goodsBloom.contains(activityId + ":" + goodsId)) {
            log.info("==> 布隆过滤器拦截：商品不存在, activityId: {}, goodsId: {}", activityId, goodsId);
            throw new BizException(ResponseCodeEnum.SECKILL_GOODS_NOT_EXIST);
        }

        // L2: 查 Redis 缓存
        String redisJsonValue = stringRedisTemplate.opsForValue().get(redisKey);

        // 若缓存不为空
        if (StrUtil.isNotBlank(redisJsonValue)) {

            // 防止缓存穿透，判断缓存是否是 NULL
            if (Objects.equals(RedisKeyConstants.NULL_CACHE_VALUE, redisJsonValue)) {
                log.info("==> 命中空值缓存，商品不存在, redisKey: {}", redisKey);
                throw new BizException(ResponseCodeEnum.SECKILL_GOODS_NOT_EXIST);
            }

            log.info("==> 命中商品详情 Redis 缓存（L2）, redisKey: {}", redisKey);

            // 手动将 String 字符串，反序列化为商品详情对象
            FindSeckillGoodsDetailRspVO rspVO = processCachedGoodsDetail(redisJsonValue, activityId, goodsId);

            // 能走到这里，说明 L1 本地缓存未命中，需要回填，以便后续请求能够命中 L1
            goodsDetailLocalCache.put(redisKey, JsonUtils.toJsonString(rspVO));

            return Response.success(rspVO);
        }

        // --------------- 以下为缓存未命中，查询数据库的逻辑 ---------------
        // 1. 根据活动 ID 查询活动信息，校验活动是否存在
        SeckillActivityDO activityDO = seckillActivityDOMapper.selectByPrimaryKey(activityId);
        if (Objects.isNull(activityDO)) {
            // 缓存空值，防止缓存穿透（攻击者用不存在的 activityId 反复请求）
            cacheNullValue(redisKey);

            throw new BizException(ResponseCodeEnum.SECKILL_ACTIVITY_NOT_EXIST);
        }

        // 2. 根据活动 ID 和商品 ID 查询秒杀商品
        SeckillGoodsDO seckillGoodsDO = seckillGoodsDOMapper.selectByActivityIdAndGoodsId(activityId, goodsId);
        if (Objects.isNull(seckillGoodsDO)) {
            // 缓存空值，防止缓存穿透（攻击者用不存在的 goodsId 反复请求）
            cacheNullValue(redisKey);

            throw new BizException(ResponseCodeEnum.SECKILL_GOODS_NOT_EXIST);
        }

        // 3. 根据 goodsId 查询商品基本信息, 如商品名称、原价
        GoodsDO goodsDO = goodsDOMapper.selectByPrimaryKey(goodsId);

        // 4. 根据 goodsId 查询商品轮播图列表
        List<GoodsImgDO> goodsImgDOS = goodsImgDOMapper.selectByGoodsId(goodsId);

        List<String> goodsImgs = null;
        if (CollUtil.isNotEmpty(goodsImgDOS)) {
            goodsImgs = goodsImgDOS.stream()
                    .map(GoodsImgDO::getImgUrl)
                    .toList();
        }

        // 5. 根据 goodsId 查询商品详情 HTML
        GoodsDetailDO goodsDetailDO = goodsDetailDOMapper.selectByGoodsId(goodsId);

        // 6. 计算活动状态
        ActivityStatusEnum activityStatusEnum = calculateActivityStatus(activityDO);

        // 7. 组装响应数据
        FindSeckillGoodsDetailRspVO rspVO = FindSeckillGoodsDetailRspVO.builder()
                .id(seckillGoodsDO.getId())
                .goodsId(goodsId)
                .activityId(seckillGoodsDO.getActivityId())
                .seckillPrice(seckillGoodsDO.getSeckillPrice())
                .seckillTotal(seckillGoodsDO.getSeckillTotal())
                .seckillStock(seckillGoodsDO.getSeckillStock())
                .activityStatus(activityStatusEnum.getStatus())
                .beginTime(activityDO.getBeginTime())
                .endTime(activityDO.getEndTime())
                .goodsImgs(goodsImgs)
                .build();

        // 设置商品基本信息
        if (Objects.nonNull(goodsDO)) {
            rspVO.setGoodsName(goodsDO.getGoodsName());
            rspVO.setGoodsPrice(goodsDO.getGoodsPrice());
        }

        // 设置商品详情 HTML
        if (Objects.nonNull(goodsDetailDO)) {
            rspVO.setGoodsDetail(goodsDetailDO.getDetailContent());
        }

        // 缓存未命中时，也要优先组合 Redis 实时库存，避免返回 DB 中的库存快照
        supplementStock(rspVO, activityId, goodsId);

        // 将商品详情写入 Redis 缓存和本地缓存
        log.info("==> 商品详情缓存未命中，将数据写入 Redis 和本地缓存, key: {}", redisKey);

        String jsonValue = JsonUtils.toJsonString(rspVO);

        // 写入本地缓存
        goodsDetailLocalCache.put(redisKey, jsonValue);

        // 动态计算缓存过期时间
        Long ttlSeconds = RedisKeyConstants.calculateTtlSeconds(activityDO.getEndTime());

        if (Objects.nonNull(ttlSeconds) && ttlSeconds > 0) {
            // 活动未结束：动态 TTL = (endTime - now) + 30分钟的安全缓冲
            stringRedisTemplate.opsForValue().set(redisKey, jsonValue,
                    ttlSeconds, TimeUnit.SECONDS);
        } else {
            // 活动已结束：设置一个较短的 TTL 过期时间，防止余温流量打穿 DB
            stringRedisTemplate.opsForValue().set(redisKey, jsonValue,
                    RedisKeyConstants.ENDED_ACTIVITY_TTL_MINUTES, TimeUnit.MINUTES);
        }

        return Response.success(rspVO);
    }

    /**
     * 根据当前时间动态计算活动状态
     *
     * @param activityDO
     * @return
     */
    private ActivityStatusEnum calculateActivityStatus(SeckillActivityDO activityDO) {
        return calculateActivityStatus(activityDO.getBeginTime(), activityDO.getEndTime());
    }

    /**
     * 根据当前时间动态计算活动状态 (重载方法)
     *
     * @param beginTime
     * @param endTime
     * @return
     */
    private ActivityStatusEnum calculateActivityStatus(LocalDateTime beginTime, LocalDateTime endTime) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(beginTime)) { // 当前时间早于活动开始时间，则活动未开始
            return ActivityStatusEnum.NOT_STARTED;
        } else if (now.isAfter(endTime)) { // 当前时间晚于活动结束时间，则活动已结束
            return ActivityStatusEnum.ENDED;
        } else { // 活动进行中
            return ActivityStatusEnum.ING;
        }
    }
}
