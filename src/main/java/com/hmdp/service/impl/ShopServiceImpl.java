package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,CACHE_SHOP_TTL,TimeUnit.MINUTES,this::getById);

        //缓存击穿(互斥锁解决)
//        Shop shop1 = queryWithMutex(id);
//        if(shop1 == null){
//            return Result.fail("店铺不存在");
//        }

        //缓存击穿(逻辑过期解决)
        Shop shop = cacheClient
                .queryWithLogicalExpire(LOCK_SHOP_KEY,CACHE_SHOP_KEY,id,Shop.class,CACHE_SHOP_TTL,TimeUnit.MINUTES,this::getById);

        return Result.ok(shop);
    }


    /**
     * 缓存击穿(互斥锁)
     * @param id
     * @return
     */
    /*public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //还需要判断命中的是否是空值null
        if(shopJson != null){
            //返回一个错误信息
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                //4.2判断是否获取锁成功
                //4.3如果失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //判断缓存是否存在
            if(StrUtil.isNotBlank(shopJson)){
                //3.存在，返回商铺信息
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }

            //4.4如果成功，根据id查询数据库
            Shop shop = getById(id);
            if (shop == null) {
                //(1).不存在，将null值写入redis，返回错误信息
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //(2).存在，写入redis，返回商铺信息
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

            //6.返回
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.释放互斥锁
            unLock(lockKey);
        }
    }*/



    /**
     * 缓存击穿(逻辑过期)
     * @param id
     * @return
     */
    /*public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否命中
        if(StrUtil.isBlank(shopJson)){
            //3.未命中
            return null;
        }

        //4.命中，需要判断是否过期(需要先把JSON反序列化)，再取出逻辑过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1未过期，直接返回shop信息
            return shop;
        }

        //4.2已过期，需要缓存重建
        //5.缓存重建
        //5.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2判断是否获取锁成功
        if (isLock){
            //5.3如果成功，开启线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    savaShopRedis(id,1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        return shop;

    }
     */


    /**
     * 线程池
     */
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);*/


    /**
     * 加锁
     * @param key
     * @return
     */
    /*private boolean tryLock(String key){
        Boolean flg = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flg);
    }*/

    /**
     * 释放锁
     * @param key
     */
    /*private void unLock(String key){
        stringRedisTemplate.delete(key);
    }*/




    /**
     * 缓存逻辑过期
     * @param id
     * @param expireSeconds
     */
    public void savaShopRedis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);

        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }



    /**
     * 缓存穿透
     * @param id
     * @return
     */
    /*public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //还需要判断命中的是否是空值null
        if(shopJson != null){
            //返回一个错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            //(1).不存在，将null值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //(2).存在，写入redis，返回商铺信息
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }*/





    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
