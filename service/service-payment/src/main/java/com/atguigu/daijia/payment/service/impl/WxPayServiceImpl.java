package com.atguigu.daijia.payment.service.impl;

import com.alibaba.fastjson.JSON;
import com.atguigu.daijia.common.constant.MqConst;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.service.RabbitService;
import com.atguigu.daijia.driver.client.DriverAccountFeignClient;
import com.atguigu.daijia.model.entity.payment.PaymentInfo;
import com.atguigu.daijia.model.enums.TradeType;
import com.atguigu.daijia.model.form.driver.TransferForm;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.vo.order.OrderRewardVo;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.payment.config.WxPayV3Properties;
import com.atguigu.daijia.payment.mapper.PaymentInfoMapper;
import com.atguigu.daijia.payment.service.WxPayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.server.Jsp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;

@Service
@Slf4j
public class WxPayServiceImpl implements WxPayService {

    @Autowired
    private PaymentInfoMapper paymentInfoMapper;
    @Autowired
    private RSAAutoCertificateConfig rsaAutoCertificateConfig;
    @Autowired
    private WxPayV3Properties wxPayV3Properties;
    @Autowired
    private RabbitService rabbitService;
    @Override
    public WxPrepayVo createWxPayment(PaymentInfoForm paymentInfoForm) {
        try {
            LambdaQueryWrapper<PaymentInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PaymentInfo::getOrderNo,paymentInfoForm.getOrderNo());
            PaymentInfo paymentInfo = paymentInfoMapper.selectOne(wrapper);
            if (paymentInfo == null){
                paymentInfo = new PaymentInfo();
                BeanUtils.copyProperties(paymentInfoForm,paymentInfo);
                paymentInfo.setPaymentStatus(0);
                paymentInfoMapper.insert(paymentInfo);
            }

            //创建微信支付对象
            JsapiServiceExtension service = new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();

            //创建request对象
            PrepayRequest request = new PrepayRequest();
            Amount amount = new Amount();
            amount.setTotal(paymentInfoForm.getAmount().multiply(new BigDecimal(100)).intValue());
            request.setAmount(amount);
            request.setAppid(wxPayV3Properties.getAppid());
            request.setMchid(wxPayV3Properties.getMerchantId());
            //string[1,127]
            String description = paymentInfo.getContent();
            if(description.length() > 127) {
                description = description.substring(0, 127);
            }
            request.setDescription(description);
            request.setNotifyUrl(wxPayV3Properties.getNotifyUrl());
            request.setOutTradeNo(paymentInfo.getOrderNo());
            //获取用户信息
            Payer payer = new Payer();
            payer.setOpenid(paymentInfoForm.getCustomerOpenId());
            request.setPayer(payer);

            //是否指定分账，不指定不能分账
            SettleInfo settleInfo = new SettleInfo();
            settleInfo.setProfitSharing(true);
            request.setSettleInfo(settleInfo);
            //调用微信支付使用方法
            PrepayWithRequestPaymentResponse response = service.prepayWithRequestPayment(request);
            WxPrepayVo wxPrepayVo = new WxPrepayVo();
            BeanUtils.copyProperties(response,wxPrepayVo);
            wxPrepayVo.setTimeStamp(response.getTimeStamp());
            return wxPrepayVo;
        } catch (Exception e) {
            throw new GuiguException(20001,"微信支付失败");
        }
    }

    @Override
    public boolean queryPayStatus(String orderNo) {
        ///创建微信操作对象
        JsapiServiceExtension service = new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();
        //封装查询支付状态需要的参数
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(wxPayV3Properties.getMerchantId());
        request.setOutTradeNo(orderNo);

        //实现查询操作
        Transaction transaction = service.queryOrderByOutTradeNo(request);

        if (transaction!=null && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS){
            this.handlePayment(transaction);
            return true;
        }else {
            return false;
        }
    }
//微信支付程序回调
    @Override
    public void wxnotify(HttpServletRequest request) {
        //1.回调通知的验签与解密
        //从request头信息获取参数
        //HTTP 头 Wechatpay-Signature
        // HTTP 头 Wechatpay-Nonce
        //HTTP 头 Wechatpay-Timestamp
        //HTTP 头 Wechatpay-Serial
        //HTTP 头 Wechatpay-Signature-Type
        //HTTP 请求体 body。切记使用原始报文，不要用 JSON 对象序列化后的字符串，避免验签的 body 和原文不一致。
//        String wechatPaySerial = request.getHeader("Wechatpay-Serial");
//        String nonce = request.getHeader("Wechatpay-Nonce");
//        String timestamp = request.getHeader("Wechatpay-Timestamp");
//        String signature = request.getHeader("Wechatpay-Signature");
//        String requestBody = RequestUtils.readData(request); todo 待实现
//        log.info("wechatPaySerial：{}", wechatPaySerial);
//        log.info("nonce：{}", nonce);
//        log.info("timestamp：{}", timestamp);
//        log.info("signature：{}", signature);
//        log.info("requestBody：{}", requestBody);
//
//        //2.构造 RequestParam
//        RequestParam requestParam = new RequestParam.Builder()
//                .serialNumber(wechatPaySerial)
//                .nonce(nonce)
//                .signature(signature)
//                .timestamp(timestamp)
//                .body(requestBody)
//                .build();
//
//
//        //3.初始化 NotificationParser
//        NotificationParser parser = new NotificationParser(rsaAutoCertificateConfig);
//        //4.以支付通知回调为例，验签、解密并转换成 Transaction
//        Transaction transaction = parser.parse(requestParam, Transaction.class);
//        log.info("成功解析：{}", JSON.toJSONString(transaction));
//        if(null != transaction && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {
//            //5.处理支付业务
//            this.handlePayment(transaction);
//        }
    }
@Autowired
private OrderInfoFeignClient orderInfoFeignClient;
    @Autowired
    private DriverAccountFeignClient driverAccountFeignClient;
    @GlobalTransactional
    @Override
    public void handleOrder(String orderNo) {
        //支付成功后的业务处理
        //1.更改订单支付状态
        orderInfoFeignClient.updateOrderPayStatus(orderNo);

        //2.处理系统奖励，打入司机账户
        OrderRewardVo orderRewardVo = orderInfoFeignClient.getOrderRewardFee(orderNo).getData();
        if(null != orderRewardVo.getRewardFee() && orderRewardVo.getRewardFee().doubleValue() > 0) {
            TransferForm transferForm = new TransferForm();
            transferForm.setTradeNo(orderNo);
            transferForm.setTradeType(TradeType.REWARD.getType());
            transferForm.setContent(TradeType.REWARD.getContent());
            transferForm.setAmount(orderRewardVo.getRewardFee());
            transferForm.setDriverId(orderRewardVo.getDriverId());
            driverAccountFeignClient.transfer(transferForm);
        }
    }

    /**
 *@Date：2024/8/15
 *@Author：silin
 *@content:发送端
     *
 */

    public void handlePayment(Transaction transaction) {
        //获取订单编号
        String orderNo = transaction.getOutTradeNo();
        //根据订单编号查询支付记录
        LambdaQueryWrapper<PaymentInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentInfo::getOrderNo,orderNo);
        PaymentInfo paymentInfo = paymentInfoMapper.selectOne(wrapper);
        if (paymentInfo.getPaymentStatus() == 1){
            return;
        }
        paymentInfo.setPaymentStatus(1);
        paymentInfo.setOrderNo(transaction.getOutTradeNo());
        paymentInfo.setTransactionId(transaction.getTransactionId());
        paymentInfo.setCallbackTime(new Date());
        paymentInfo.setCallbackContent(JSON.toJSONString(transaction));
        paymentInfoMapper.updateById(paymentInfo);
        //发送信息
        rabbitService.sendMessage(MqConst.EXCHANGE_ORDER,MqConst.ROUTING_PAY_SUCCESS,orderNo);
    }
}
