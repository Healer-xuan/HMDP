package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 发送手机验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid){ //无效手机号
            //2.判断是否符合，如果不符合返回错误信息
            Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到 redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        return Result.ok();
    }


    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号和验证码
        String phone = loginForm.getPhone();
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid){ //无效手机号
            //2.判断是否符合，如果不符合返回错误信息
            Result.fail("手机号格式错误！");
        }

        // 从redis获取验证码并校验
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String loginFormCode = loginForm.getCode();
        if(code == null || !code.equals(loginFormCode)){
            //2.不一致，报错
            return Result.fail("验证码错误！");
        }

        //3.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //4.判断用户是否存在
        if(user == null){
            //5.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        //6.存在，保存用户信息到 redis中
        //(1).随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //(2).将user对象转成Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        //(3).保存
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        //(4).设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //(5).返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }
}
