package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合则返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合则生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session-->redis
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码,这里只是模拟并没有真的发送验证码
        log.debug("验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合则返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.校验验证码-->从redis中获取
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //4.验证码不一致
            return Result.fail("验证码错误");
        }
        //5.一致,根据手机号查询用户select * from tb_user where phone=?
        //因为我们当前类继承了mybatisplus可以通过指定Mapper和表直接使用query进行操作
        User user = query().eq("phone",phone).one();
        //6.如果用户不存在则进行注册
        if(user==null){
            user=createUserWithPhone(phone);
        }
        //7.保存用户信息到session中-->redis
        //7.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString();
        //7.2将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        HashMap<String, String> userMap = new HashMap<>();
        userMap.put("icon",userDTO.getIcon());
        userMap.put("id",String.valueOf(userDTO.getId()));
        userMap.put("nickName",userDTO.getNickName());
        //7.3存储
        String tokenKey = LOGIN_CODE_KEY+ token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4设置token的有效期为30分钟
        stringRedisTemplate.expire(tokenKey,30,TimeUnit.MINUTES);
        //7.5登录成功删除验证码信息
        stringRedisTemplate.delete(LOGIN_CODE_KEY+phone);
//        session.setAttribute("user", userDTO);
        //8.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
                        //通过自定义常量代替"user_",随机生成10个字符作为用户名
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }
}
