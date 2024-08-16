package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.AuthContextHolder;
import com.atguigu.daijia.dispatch.client.NewOrderFeignClient;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.driver.service.DriverService;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.form.driver.DriverFaceModelForm;
import com.atguigu.daijia.model.form.driver.UpdateDriverAuthInfoForm;
import com.atguigu.daijia.model.vo.driver.DriverAuthInfoVo;
import com.atguigu.daijia.model.vo.driver.DriverLoginVo;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class DriverServiceImpl implements DriverService {

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private LocationFeignClient locationFeignClient;
    @Autowired
    private NewOrderFeignClient newOrderFeignClient;

    @Override
    public String login(String code) {
        Result<Long> longResult = driverInfoFeignClient.login(code);
        if (longResult.getCode()!=200){
            throw new
                    GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        Long driverId = longResult.getData();
        String token = UUID.randomUUID().toString().replaceAll("-", "");
        redisTemplate.opsForValue().set(RedisConstant.USER_LOGIN_KEY_PREFIX+token,driverId,RedisConstant.USER_LOGIN_KEY_TIMEOUT, TimeUnit.SECONDS);
        return token;
    }

    @Override
    public DriverLoginVo getDriverLoginInfo(Long driverId) {
        Long userId = AuthContextHolder.getUserId();
        Result<DriverLoginVo> driverLoginInfo = driverInfoFeignClient.getDriverLoginInfo(userId);
        return driverLoginInfo.getData();
    }

    @Override
    public DriverAuthInfoVo getDriverAuthInfo(Long driverId) {
        return driverInfoFeignClient.getDriverAuthInfo(driverId).getData();
    }

    @Override
    public Boolean updateDriverAuthInfo(UpdateDriverAuthInfoForm updateDriverAuthInfoForm) {
        return driverInfoFeignClient.UpdateDriverAuthInfo(updateDriverAuthInfoForm).getData();
    }

    @Override
    public Boolean creatDriverFaceModel(DriverFaceModelForm driverFaceModelForm) {
        return true;
    }

    @Override
    public Boolean isFaceRecognition(Long driverId) {
        Result<Boolean> faceRecognition = driverInfoFeignClient.isFaceRecognition(driverId);
        Boolean data = faceRecognition.getData();
        return data;
    }

    @Override
    public Boolean verifyDriverFace(DriverFaceModelForm driverFaceModelForm) {
        return driverInfoFeignClient.verifyDriverFace(driverFaceModelForm).getData();
    }

    @Override
    public Boolean startService(Long driverId) {
        //开启接单功能
        DriverLoginVo data = driverInfoFeignClient.getDriverLoginInfo(driverId).getData();
        if (data.getAuthStatus() != 2){
            throw new GuiguException(ResultCodeEnum.AUTH_ERROR);
        }
        Boolean data1 = driverInfoFeignClient.isFaceRecognition(driverId).getData();
        if (!data1){
            throw new GuiguException(ResultCodeEnum.FACE_ERROR);
        }
        //更新接单信息
        driverInfoFeignClient.updateServiceStatus(driverId,1);
            //删除司机的信息
        locationFeignClient.removeDriverLocation(driverId);
        //清除订单的数据
        newOrderFeignClient.clearNewOrderQueueData(driverId);
        return true;
    }

    @Override
    public Boolean stopService(Long driverId) {
         //更新司机接单信息
        driverInfoFeignClient.updateServiceStatus(driverId,0);
        //删除司机位置信息
        locationFeignClient.removeDriverLocation(driverId);
        //清空队列
        newOrderFeignClient.clearNewOrderQueueData(driverId);
        return true;
    }

}
