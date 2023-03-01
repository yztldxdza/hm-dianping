package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * token拦截器:根据用户做出的操作刷新过期时间
 */

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session-->token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
//        获取session中的用户
//        Object user = session.getAttribute("user");
        //2.如果token为空，直接放行让LoginInterceptor来处理
        if (token==null){
            return true;
        }
        String key = RedisConstants.LOGIN_CODE_KEY+token;
        //3.基于token获取Redis中的用户数据
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //4.判断用户是否存在,不存在直接放行让LoginInterceptor来处理
        if(userMap.isEmpty()){
            return true;
        }
        //5.将查询到的Hash数据转化为UserDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //6.将用户信息保存到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.刷新tokenTTL
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        存在，保存用户信息到ThreadLocal
//        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
