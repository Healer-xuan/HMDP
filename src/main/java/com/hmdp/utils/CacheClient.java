package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param time
     * @param unit
     * @param dbFallback
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit,
                                         Function<ID,R> dbFallback){
        String key = keyPrefix + id;

        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(Json)){
            //3.存在，返回商铺信息
            return JSONUtil.toBean(Json, type);
        }

        //还需要判断命中的是否是空值null
        if(Json != null){
            //返回一个错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            //(1).不存在，将null值写入redis，返回错误信息
            stringRedisTemplate.opsForValue().set(key,"",time, unit);
            return null;
        }
        //(2).存在，写入redis，返回商铺信息
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit);

        return r;
    }


    /**
     * 缓存击穿(逻辑过期解决)
     * @param keyPrefix
     * @param lockKeyPrefix
     * @param id
     * @param type
     * @param time
     * @param unit
     * @param dbFallback
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,String lockKeyPrefix,ID id, Class<R> type,Long time,
                                           TimeUnit unit,Function<ID,R> dbFallback){
        String key = keyPrefix + id;

        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否命中
        if(StrUtil.isBlank(Json)){
            //3.未命中
            return null;
        }

        //4.命中，需要判断是否过期(需要先把JSON反序列化)，再取出逻辑过期时间
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1未过期，直接返回shop信息
            return r;
        }

        //4.2已过期，需要缓存重建
        //5.缓存重建
        //5.1获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        //5.2判断是否获取锁成功
        if (isLock){
            //5.3如果成功，开启线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    //先查数据库，再写入redis
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        return r;

    }


    /**
     * 缓存击穿(互斥锁解决)
     * @param keyPrefix
     * @param lockKeyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithMutex(String keyPrefix,String lockKeyPrefix,ID id, Class<R> type,
                                   Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id;

        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(Json)){
            //3.存在，返回商铺信息
            R r = JSONUtil.toBean(Json, type);
            return r;
        }

        //还需要判断命中的是否是空值null
        if(Json != null){
            //返回一个错误信息
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = lockKeyPrefix + id;
        //R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                //4.2判断是否获取锁成功
                //4.3如果失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(key, lockKey, id, type, dbFallback, time, unit);
            }

            //4.4如果成功，根据id查询数据库
            R r = dbFallback.apply(id);
            if (r == null) {
                //(1).不存在，将null值写入redis，返回错误信息
                stringRedisTemplate.opsForValue().set(key,"",time, unit);
                return null;
            }
            //(2).存在，写入redis，返回商铺信息
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time, unit);

            //6.返回
            return r;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.释放互斥锁
            unLock(lockKey);
        }
    }



    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 加锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flg = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flg);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
