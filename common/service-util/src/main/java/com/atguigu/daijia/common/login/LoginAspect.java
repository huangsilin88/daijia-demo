package com.atguigu.daijia.common.login;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.AuthContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Aspect//切面类
public class LoginAspect {

    @Autowired
    private RedisTemplate redisTemplate;

    //环绕通知。登录判断
    @Around("execution(* com.atguigu.daijia.*.controller.*.*(..)) && @annotation(login)")
    public Object login(ProceedingJoinPoint proceedingJoinPoint, Login login) throws Throwable {
        //使用spring工具类获取request对象
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) attributes;
        HttpServletRequest request = servletRequestAttributes.getRequest();
        String token = request.getHeader("token");
        //token为空
        if (!StringUtils.hasText(token)){
            throw new GuiguException(ResultCodeEnum.LOGIN_AUTH);
        }
        String userId = redisTemplate.opsForValue().get(RedisConstant.USER_LOGIN_KEY_PREFIX+token).toString();
        if (StringUtils.hasText(userId)){
            AuthContextHolder.setUserId(Long.valueOf(userId));
        }

        return proceedingJoinPoint.proceed();

    }
}
