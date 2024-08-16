package com.atguigu.daijia.customer.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.customer.client.CustomerInfoFeignClient;
import com.atguigu.daijia.customer.service.CustomerService;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private CustomerInfoFeignClient client;
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public String login(String code) {
        Result<Long> loginResult = client.login(code);
        //获取验证码
        if (loginResult.getCode() != 200){
            throw new GuiguException(ResultCodeEnum.FAIL);
        }
        //判断是否有用户id
        Long dataId = loginResult.getData();
        if (dataId == null){
            throw new GuiguException(ResultCodeEnum.FAIL);
        }
        //生成token
        String token = UUID.randomUUID().toString().replaceAll("-", "");

        //将用户信息放入redis
        //key:token value:dataId
        redisTemplate.opsForValue().set(RedisConstant.USER_LOGIN_KEY_PREFIX+token, dataId.toString(),
                RedisConstant.USER_LOGIN_KEY_TIMEOUT, TimeUnit.SECONDS);
        //返回token
        return token;
    }

    @Override
    public CustomerLoginVo getInfo(Long userId) {
        Result<CustomerLoginVo> customerLoginInfo = client.getCustomerLoginInfo(userId);
        if ( customerLoginInfo.getCode() != 200) {
            throw new GuiguException(ResultCodeEnum.FAIL);
        }
        CustomerLoginVo data = customerLoginInfo.getData();
        if (data == null){
            throw new GuiguException(ResultCodeEnum.FAIL);
        }
        return data;
    }
}
