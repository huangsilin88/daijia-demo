package com.atguigu.daijia.customer.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.customer.client.CustomerInfoFeignClient;
import com.atguigu.daijia.customer.service.OrderService;
import com.atguigu.daijia.dispatch.client.NewOrderFeignClient;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.map.client.MapFeignClient;
import com.atguigu.daijia.map.client.WxPayFeignClient;
import com.atguigu.daijia.model.entity.customer.CustomerInfo;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.customer.ExpectOrderForm;
import com.atguigu.daijia.model.form.customer.SubmitOrderForm;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.payment.CreateWxPaymentForm;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.form.rules.FeeRuleRequestForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.customer.ExpectOrderVo;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.driver.DriverInfoVo;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import com.atguigu.daijia.model.vo.order.*;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponseVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.rules.client.FeeRuleFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderServiceImpl implements OrderService {


    @Autowired
    private MapFeignClient mapFeignClient;
    @Autowired
    private FeeRuleFeignClient feeRuleFeignClient;
    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;
    @Autowired
    private NewOrderFeignClient newOrderFeignClient;
    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;
    @Autowired
    private LocationFeignClient locationFeignClient;
    @Autowired
    private CustomerInfoFeignClient customerInfoFeignClient;
    @Autowired
    private WxPayFeignClient wxPayFeignClient;



    @Override
    public ExpectOrderVo expectOrder(ExpectOrderForm expectOrderForm) {

        //获取驾驶路线
        CalculateDrivingLineForm calculateDrivingLineForm = new CalculateDrivingLineForm();
        BeanUtils.copyProperties(expectOrderForm, calculateDrivingLineForm);
        Result<DrivingLineVo> drivingLineVoResult = mapFeignClient.calculateDrivingLine(calculateDrivingLineForm);
        DrivingLineVo dataVo = drivingLineVoResult.getData();
        //获取订单费用
        FeeRuleRequestForm feeRuleRequestForm = new FeeRuleRequestForm();
        feeRuleRequestForm.setDistance(dataVo.getDistance());
        feeRuleRequestForm.setStartTime(new Date());
        feeRuleRequestForm.setWaitMinute(0);
        Result<FeeRuleResponseVo> feeRuleResponseVoResult = feeRuleFeignClient.calculateOrderFee(feeRuleRequestForm);
        FeeRuleResponseVo data = feeRuleResponseVoResult.getData();
        ExpectOrderVo expectOrderVo = new ExpectOrderVo();
        expectOrderVo.setDrivingLineVo(dataVo);
        expectOrderVo.setFeeRuleResponseVo(data);
        return expectOrderVo;
    }

    @Override
    public Long submitOrder(SubmitOrderForm submitOrderForm) {

        //重新获取路线和
        CalculateDrivingLineForm calculateDrivingLineForm = new CalculateDrivingLineForm();
        BeanUtils.copyProperties(submitOrderForm, calculateDrivingLineForm);
        Result<DrivingLineVo> drivingLineVoResult = mapFeignClient.calculateDrivingLine(calculateDrivingLineForm);
        DrivingLineVo dataVo = drivingLineVoResult.getData();
        //订单费用
        FeeRuleRequestForm feeRuleRequestForm = new FeeRuleRequestForm();
        feeRuleRequestForm.setDistance(dataVo.getDistance());
        feeRuleRequestForm.setStartTime(new Date());
        feeRuleRequestForm.setWaitMinute(0);
        Result<FeeRuleResponseVo> feeRuleResponseVoResult = feeRuleFeignClient.calculateOrderFee(feeRuleRequestForm);
        FeeRuleResponseVo dataVo1 = feeRuleResponseVoResult.getData();

        //将数据进行封装
        OrderInfoForm orderInfoForm = new OrderInfoForm();
        BeanUtils.copyProperties(submitOrderForm, orderInfoForm);
        orderInfoForm.setExpectDistance(dataVo.getDistance());
        orderInfoForm.setExpectAmount(dataVo1.getTotalAmount());
        Result<Long> longResult = orderInfoFeignClient.saveOrderInfo(orderInfoForm);
        //获取订单id
        Long orderId = longResult.getData();
 //进行任务调度 呼叫附近的司机

        NewOrderTaskVo newOrderDispatchVo = new NewOrderTaskVo();
        newOrderDispatchVo.setOrderId(orderId);
        newOrderDispatchVo.setStartLocation(orderInfoForm.getStartLocation());
        newOrderDispatchVo.setStartPointLongitude(orderInfoForm.getStartPointLongitude());
        newOrderDispatchVo.setStartPointLatitude(orderInfoForm.getStartPointLatitude());
        newOrderDispatchVo.setEndLocation(orderInfoForm.getEndLocation());
        newOrderDispatchVo.setEndPointLongitude(orderInfoForm.getEndPointLongitude());
        newOrderDispatchVo.setEndPointLatitude(orderInfoForm.getEndPointLatitude());
        newOrderDispatchVo.setExpectAmount(orderInfoForm.getExpectAmount());
        newOrderDispatchVo.setExpectDistance(orderInfoForm.getExpectDistance());
        newOrderDispatchVo.setExpectTime(dataVo.getDuration());
        newOrderDispatchVo.setFavourFee(orderInfoForm.getFavourFee());
        newOrderDispatchVo.setCreateTime(new Date());
        Long jobId = newOrderFeignClient.addAndStartTask(newOrderDispatchVo).getData();
        log.info("任务调度成功，任务id为：{}", jobId);

        return orderId;
    }

    @Override
    public Integer getOrderStatus(Long orderId) {
        Result<Integer> orderStatus = orderInfoFeignClient.getOrderStatus(orderId);
        Integer data = orderStatus.getData();
        return data;
    }

    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
       return orderInfoFeignClient.searchCustomerCurrentOrder(customerId).getData();
    }

    @Override
    public OrderInfoVo getOrderInfo(Long orderId, Long customerId) {
        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();
       if (customerId != orderInfo.getCustomerId()){
           throw new GuiguException(ResultCodeEnum.DATA_ERROR);
       }
       DriverInfoVo driverInfoVo = null;
        if (orderInfo.getDriverId()!=null){
           driverInfoVo = driverInfoFeignClient.getDriverInfo(orderInfo.getDriverId()).getData();
        }
        OrderBillVo orderBillVo = null;
        if (orderInfo.getStatus() >= OrderStatus.UNPAID.getStatus()){
            orderBillVo = orderInfoFeignClient.getOrderBillInfo(orderInfo.getId()).getData();
        }
        OrderInfoVo orderInfoVo = new OrderInfoVo();
       orderInfoVo.setOrderId(orderInfo.getId());
       BeanUtils.copyProperties(orderInfo,orderInfoVo);
       orderInfoVo.setOrderBillVo(orderBillVo);
       orderInfoVo.setDriverInfoVo(driverInfoVo);
       return orderInfoVo;
    }

    @Override
    public DriverInfoVo getDriverInfo(Long orderId, Long customerId) {
        OrderInfo orderInfo = orderInfoFeignClient.getOrderInfo(orderId).getData();
        if (orderInfo.getCustomerId()!= customerId){
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

       return driverInfoFeignClient.getDriverInfo(orderInfo.getDriverId()).getData();
    }

    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {
       return  locationFeignClient.getCacheOrderLocation(orderId).getData();
    }

    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {
       return mapFeignClient.calculateDrivingLine(calculateDrivingLineForm).getData();
    }

    @Override
    public OrderServiceLastLocationVo getOrderServiceLastLocation(Long orderId) {
       return locationFeignClient.getOrderServiceLastLocation(orderId).getData();
    }

    @Override
    public PageVo findCustomerOrderPage(Long customerId, Long page, Long limit) {
        return orderInfoFeignClient.findCustomerOrderPage(customerId, page, limit).getData();
    }

    @Override
    public WxPrepayVo createWxPayment(CreateWxPaymentForm createWxPaymentForm) {
        //获取订单信息
        OrderPayVo orderPayVo = orderInfoFeignClient.getOrderPayVo(createWxPaymentForm.getOrderNo(), createWxPaymentForm.getCustomerId()).getData();
        if (orderPayVo.getStatus() != OrderStatus.UNPAID.getStatus()){
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
        //获取乘客和司机的openid
        String customerId = customerInfoFeignClient.getCustomerOpenId(orderPayVo.getCustomerId()).getData();
        String driverId = driverInfoFeignClient.getDriverOpenId(orderPayVo.getDriverId()).getData();
        PaymentInfoForm paymentInfoForm = new PaymentInfoForm();
        paymentInfoForm.setCustomerOpenId(customerId);
        paymentInfoForm.setDriverOpenId(driverId);
        paymentInfoForm.setOrderNo(orderPayVo.getOrderNo());
        paymentInfoForm.setAmount(orderPayVo.getPayAmount());
        paymentInfoForm.setContent(orderPayVo.getContent());
        paymentInfoForm.setPayWay(1);
        WxPrepayVo wxPrepayVo = wxPayFeignClient.createWxPayment(paymentInfoForm).getData();
        return wxPrepayVo;
    }

    @Override
    public Boolean queryPayStatus(String orderNo) {
        return wxPayFeignClient.queryPayStatus(orderNo).getData();
    }

//    @Override
//    public Boolean sendOrderBillInfo(Long orderId, Long driverId) {
//        return orderInfoFeignClient.sendOrderBillInfo(orderId, driverId).getData();
//    }
}
