package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 基于StringRedisTemplate封装一个缓存工具类
 * @author Lenovo
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //声明一个线程池用于完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 方法1：将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }

    /**
     * 方法2：将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
     */
    public void setWithLogicExpire(String key,Object value,Long time,TimeUnit timeUnit){
        //由于需要设置逻辑过期时间，所以我们需要用到RedisData
        RedisData redisData = new RedisData();
        //redisData的data就是传进来的value对象
        redisData.setData(value);
        //逻辑过期时间就是当前时间加上传进来的参数时间，用TimeUnit可以将时间转为秒，随后与当前时间相加
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //由于是逻辑过期，所以这里不需要设置过期时间，只存一下key和value就好了，同时注意value是ridisData类型
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 方法3：根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * key前缀
     * id（类型泛型）
     * 返回值类型（泛型）
     * 查询的函数
     * TTL需要的两个参数
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id,
                                         Class<R> type, Function<ID,R> dbFallback,
                                         Long time,TimeUnit timeUnit){
        //1.先从Redis中查，常量值：固定前缀+店铺ID
        String key = keyPrefix+ id;
        String json=stringRedisTemplate.opsForValue().get(key);
        //2.如果查到了则转为R类型直接返回
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //经过上面的isNotBlank方法后这里只剩3种情况““  null  特殊字符
        //如果查询到的是空字符串，则说明是我们缓存的空数据,这里!=null说明要么是“”要么是特殊字符
        if (json!=null){
            return null;
        }
        //3.没找到则去数据库中查,查询逻辑用我们参数中注入的函数
        R r = dbFallback.apply(id);
        //查不到，则将空字符串写入Redis并设置过期时间
        if (r==null){
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.查到了则转为json字符串存入Redis并设置TTL
        String jsonStr = JSONUtil.toJsonStr(r);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time, timeUnit);
        return r;
    }


    /**
     * 方法4：根据指定的Key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id,
                                            Class<R> type, Function<ID, R> dbFallback,
                                            Long time, TimeUnit timeUnit) {
        //1.先从Redis中查，常量值：固定前缀+店铺ID
        String key = keyPrefix+ id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.如果未命中，则返回空
        if (StrUtil.isBlank(json)){
            return null;
        }
        //3.命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //3.1 将data转为指定对象
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //3.2获取过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期返回商铺信息
            return r;
        }
        //5.过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if (flag) {
            //6. 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R tmp = dbFallback.apply(id);
                    this.setWithLogicExpire(key, tmp, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
            //7. 直接返回商铺信息
            return r;
        }
        //8. 未获取到锁，直接返回商铺信息
        return r;
    }


    /**
     * 方法5：根据指定的Key查询缓存，并反序列化为指定类型，需要利用互斥锁解决缓存击穿问题
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //先从Redis中查，这里的常量值是固定的前缀 + 店铺id
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为Shop类型直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        R r = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            //否则去数据库中查
            boolean flag = tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            r = dbFallback.apply(id);
            //查不到，则将空值写入Redis
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //并存入redis，设置TTL
            this.set(key, r, time, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return r;
    }


    /**
     * 尝试获取锁
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 尝试解锁
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
