package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 设置起始时间1640995200L是秒数-->2022.1.1
     */
    private static final Long BEGIN_TIMESTAMP=1640995200L;;
    //序列号长度
    public static final Long COUNT_BIT=32L;

    /**
     * 全局ID生成器
     * @param keyPrefix 业务前缀ID
     * @return
     */
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentSecond- BEGIN_TIMESTAMP;
        //2.生成序列号，以天为单位
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:mm:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix+":"+date);
        //3.接收并返回，位运算向左移动32位后面会补0，然后与上序列号就等于把序列号放在了最后的32位上
        return timeStamp<<COUNT_BIT|count;
    }
}
