package com.atguigu.daijia.driver.service.impl;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.mapper.DriverAccountMapper;
import com.atguigu.daijia.driver.mapper.DriverFaceRecognitionMapper;
import com.atguigu.daijia.driver.mapper.DriverInfoMapper;
import com.atguigu.daijia.driver.mapper.DriverSetMapper;
import com.atguigu.daijia.driver.service.DriverInfoService;
import com.atguigu.daijia.model.entity.driver.DriverAccount;
import com.atguigu.daijia.model.entity.driver.DriverFaceRecognition;
import com.atguigu.daijia.model.entity.driver.DriverInfo;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.form.driver.DriverFaceModelForm;
import com.atguigu.daijia.model.form.driver.UpdateDriverAuthInfoForm;
import com.atguigu.daijia.model.vo.driver.DriverAuthInfoVo;
import com.atguigu.daijia.model.vo.driver.DriverInfoVo;
import com.atguigu.daijia.model.vo.driver.DriverLoginVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.iai.v20180301.IaiClient;
import com.tencentcloudapi.iai.v20180301.models.DetectLiveFaceRequest;
import com.tencentcloudapi.iai.v20180301.models.DetectLiveFaceResponse;
import com.tencentcloudapi.iai.v20180301.models.VerifyFaceRequest;
import com.tencentcloudapi.iai.v20180301.models.VerifyFaceResponse;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class DriverInfoServiceImpl extends ServiceImpl<DriverInfoMapper, DriverInfo> implements DriverInfoService {

        @Autowired
        private WxMaService wxMaService;
        @Autowired
        private DriverInfoMapper driverInfoMapper;
        @Autowired
        private DriverAccountMapper driverAccountMapper;
        @Autowired
        private DriverSetMapper driverSetMapper;
        @Autowired
        private DriverFaceRecognitionMapper driverFaceRecognitionMapper;
        @Autowired
        private TencentCloudProperties tencentCloudProperties;

    @Override
    public Long login(String code) {
  String openid = null;
        try {
            WxMaJscode2SessionResult sessionInfo = wxMaService.getUserService().getSessionInfo(code);
            openid = sessionInfo.getOpenid();
        } catch (WxErrorException e) {
            throw new RuntimeException(e);
        }
        LambdaQueryWrapper<DriverInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DriverInfo::getWxOpenId,openid);
        DriverInfo driverInfo = driverInfoMapper.selectOne(wrapper);


        if (driverInfo == null){
            driverInfo = new DriverInfo();
            driverInfo.setNickname(String.valueOf(System.currentTimeMillis()));
            driverInfo.setAvatarUrl("https://oss.aliyuncs.com/aliyun_id_photo_bucket/default_handsome.jpg");
            driverInfo.setWxOpenId(openid);
            driverInfoMapper.insert(driverInfo);

            //初始化默认设置
            DriverSet driverSet = new DriverSet();
            driverSet.setDriverId(driverInfo.getId());
            driverSet.setOrderDistance(new BigDecimal(0));//0：无限制
            driverSet.setAcceptDistance(new BigDecimal(SystemConstant.ACCEPT_DISTANCE));//默认接单范围：5公里
            driverSet.setIsAutoAccept(0);//0：否 1：是
            driverSetMapper.insert(driverSet);

            //初始化司机账户
            DriverAccount driverAccount = new DriverAccount();
            driverAccount.setDriverId(driverInfo.getId());
            driverAccountMapper.insert(driverAccount);
            return driverInfo.getId();
        }
        return driverInfo.getId();
    }

    @Override
    public DriverLoginVo getDriverLoginInfo(Long driverId) {
        DriverInfo driverInfo = driverInfoMapper.selectById(driverId);
        DriverLoginVo driverLoginVo = new DriverLoginVo();
        BeanUtils.copyProperties(driverInfo,driverLoginVo);

        String faceModelId = driverInfo.getFaceModelId();
        boolean is_HasText = StringUtils.hasText(faceModelId);
        driverLoginVo.setIsArchiveFace(is_HasText);
        return driverLoginVo;
    }

    @Override
    public DriverAuthInfoVo getDriverAuthInfo(Long driverId) {
        DriverInfo driverInfo = driverInfoMapper.selectById(driverId);
        DriverAuthInfoVo driverAuthInfoVo = new DriverAuthInfoVo();
        BeanUtils.copyProperties(driverInfo,driverAuthInfoVo);
        return driverAuthInfoVo;
    }

    @Override
    public Boolean updateDriverAuthInfo(UpdateDriverAuthInfoForm updateDriverAuthInfoForm) {
        DriverInfo driverInfo = new DriverInfo();
        driverInfo.setId(updateDriverAuthInfoForm.getDriverId());
        BeanUtils.copyProperties(updateDriverAuthInfoForm, driverInfo);
        return this.updateById(driverInfo);
    }

    @Override
    public Boolean creatDriverFaceModel(DriverFaceModelForm driverFaceModelForm) {
        DriverInfo driverInfo = this.getById(driverFaceModelForm.getDriverId());
//        try {
//            // 实例化一个认证对象，入参需要传入腾讯云账户 SecretId 和 SecretKey，此处还需注意密钥对的保密
//            // 代码泄露可能会导致 SecretId 和 SecretKey 泄露，并威胁账号下所有资源的安全性。以下代码示例仅供参考，建议采用更安全的方式来使用密钥，请参见：https://cloud.tencent.com/document/product/1278/85305
//            // 密钥可前往官网控制台 https://console.cloud.tencent.com/cam/capi 进行获取
//            Credential cred = new Credential(tencentCloudProperties.getSecretId(), tencentCloudProperties.getSecretKey());
//            // 实例化一个http选项，可选的，没有特殊需求可以跳过
//            HttpProfile httpProfile = new HttpProfile();
//            httpProfile.setEndpoint("iai.tencentcloudapi.com");
//            // 实例化一个client选项，可选的，没有特殊需求可以跳过
//            ClientProfile clientProfile = new ClientProfile();
//            clientProfile.setHttpProfile(httpProfile);
//            // 实例化要请求产品的client对象,clientProfile是可选的
//            IaiClient client = new IaiClient(cred, tencentCloudProperties.getRegion(), clientProfile);
//            // 实例化一个请求对象,每个接口都会对应一个request对象
//            CreatePersonRequest req = new CreatePersonRequest();
//            req.setGroupId(tencentCloudProperties.getPersionGroupId());
//            //基本信息
//            req.setPersonId(String.valueOf(driverInfo.getId()));
//            req.setGender(Long.parseLong(driverInfo.getGender()));
//            req.setQualityControl(4L);
//            req.setUniquePersonControl(4L);
//            req.setPersonName(driverInfo.getName());
//            req.setImage(driverFaceModelForm.getImageBase64());
//
//            // 返回的resp是一个CreatePersonResponse的实例，与请求对象对应
//            CreatePersonResponse resp = client.CreatePerson(req);
//            // 输出json格式的字符串回包
//            System.out.println(CreatePersonResponse.toJsonString(resp));
//            if (StringUtils.hasText(resp.getFaceId())) {
//                //人脸校验必要参数，保存到数据库表
//                driverInfo.setFaceModelId(resp.getFaceId());
//                this.updateById(driverInfo);
//            }
//        } catch (TencentCloudSDKException e) {
//            System.out.println(e.toString());
//            return false;
//        }
        return true;
    }

    @Override
    public DriverSet getDriverSet(Long driverId) {
        LambdaQueryWrapper<DriverSet> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DriverSet::getDriverId,driverId);
        DriverSet driverSet = driverSetMapper.selectOne(wrapper);
        return driverSet;
    }

    @Override
    public Boolean isFaceRecognition(Long driverId) {
        if (driverId!=0)
        {
            return true;
        }
        //查询司机是否人脸识别
        LambdaQueryWrapper<DriverFaceRecognition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DriverFaceRecognition::getDriverId,driverId);
        wrapper.eq(DriverFaceRecognition::getFaceDate,new DateTime().toString("yyyy-MM-dd"));
        Long count = driverFaceRecognitionMapper.selectCount(wrapper);
        return count!=0;
    }

    @Override
    public Boolean verifyDriverFace(DriverFaceModelForm driverFaceModelForm) {
        try {
            // 实例化一个认证对象，入参需要传入腾讯云账户 SecretId 和 SecretKey，此处还需注意密钥对的保密
            // 代码泄露可能会导致 SecretId 和 SecretKey 泄露，并威胁账号下所有资源的安全性。以下代码示例仅供参考，建议采用更安全的方式来使用密钥，请参见：https://cloud.tencent.com/document/product/1278/85305
            // 密钥可前往官网控制台 https://console.cloud.tencent.com/cam/capi 进行获取
            Credential cred = new Credential(tencentCloudProperties.getSecretId(), tencentCloudProperties.getSecretKey());
            // 实例化一个http选项，可选的，没有特殊需求可以跳过
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("iai.tencentcloudapi.com");
            // 实例化一个client选项，可选的，没有特殊需求可以跳过
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            // 实例化要请求产品的client对象,clientProfile是可选的
            IaiClient client = new IaiClient(cred, tencentCloudProperties.getRegion(), clientProfile);
            // 实例化一个请求对象,每个接口都会对应一个request对象
            VerifyFaceRequest req = new VerifyFaceRequest();
            req.setImage(driverFaceModelForm.getImageBase64());
            req.setPersonId(String.valueOf(driverFaceModelForm.getDriverId()));
            // 返回的resp是一个VerifyFaceResponse的实例，与请求对象对应
            VerifyFaceResponse resp = client.VerifyFace(req);
            // 输出json格式的字符串回包
            System.out.println(VerifyFaceResponse.toJsonString(resp));
            //照片比对是否成功
            //todo
            return true;
//            if (resp.getIsMatch()) {
//                //活体检查
//                if(this.detectLiveFace(driverFaceModelForm.getImageBase64())) {
//                    DriverFaceRecognition driverFaceRecognition = new DriverFaceRecognition();
//                    driverFaceRecognition.setDriverId(driverFaceModelForm.getDriverId());
//                    driverFaceRecognition.setFaceDate(new Date());
//                    driverFaceRecognitionMapper.insert(driverFaceRecognition);
//                    return true;
//                };
//            }
        } catch (TencentCloudSDKException e) {
            System.out.println(e.toString());
        }
        throw new GuiguException(ResultCodeEnum.DATA_ERROR);
    }

    @Override
    public Boolean updateServiceStatus(Long driverId, Integer status) {
        //更新接单状态
        LambdaQueryWrapper<DriverSet> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DriverSet::getDriverId,driverId);
        DriverSet driverSet = new DriverSet();
        driverSet.setServiceStatus(status);
        driverSetMapper.update(driverSet,wrapper);
        return true;
    }

    @Override
    public DriverInfoVo getDriverInfo(Long driverId) {
        DriverInfo driverInfo = driverInfoMapper.selectById(driverId);
        DriverInfoVo driverInfoVo = new DriverInfoVo();
        //计算驾驶年龄
        int year = new DateTime().getYear();
        int year1 = new DateTime(driverInfo.getDriverLicenseIssueDate()).getYear();
        driverInfoVo.setDriverLicenseAge(year-year1);
        BeanUtils.copyProperties(driverInfo,driverInfoVo);
        return driverInfoVo;
    }

    @Override
    public String getDriverOpenId(Long driverId) {
        DriverInfo driverInfo = this.getOne(new LambdaQueryWrapper<DriverInfo>().eq(DriverInfo::getId, driverId).select(DriverInfo::getWxOpenId));
        return driverInfo.getWxOpenId();
    }

    private Boolean detectLiveFace(String imageBase64) {
        try{
            // 实例化一个认证对象，入参需要传入腾讯云账户 SecretId 和 SecretKey，此处还需注意密钥对的保密
            // 代码泄露可能会导致 SecretId 和 SecretKey 泄露，并威胁账号下所有资源的安全性。以下代码示例仅供参考，建议采用更安全的方式来使用密钥，请参见：https://cloud.tencent.com/document/product/1278/85305
            // 密钥可前往官网控制台 https://console.cloud.tencent.com/cam/capi 进行获取
            Credential cred = new Credential(tencentCloudProperties.getSecretId(), tencentCloudProperties.getSecretKey());
            // 实例化一个http选项，可选的，没有特殊需求可以跳过
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("iai.tencentcloudapi.com");
            // 实例化一个client选项，可选的，没有特殊需求可以跳过
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            // 实例化要请求产品的client对象,clientProfile是可选的
            IaiClient client = new IaiClient(cred, tencentCloudProperties.getRegion(), clientProfile);
            // 实例化一个请求对象,每个接口都会对应一个request对象
            DetectLiveFaceRequest req = new DetectLiveFaceRequest();
            req.setImage(imageBase64);
            // 返回的resp是一个DetectLiveFaceResponse的实例，与请求对象对应
            DetectLiveFaceResponse resp = client.DetectLiveFace(req);
            // 输出json格式的字符串回包
            System.out.println(DetectLiveFaceResponse.toJsonString(resp));
            if(resp.getIsLiveness()) {
                return true;
            }
        } catch (TencentCloudSDKException e) {
            System.out.println(e.toString());
        }
        return false;
    }
}