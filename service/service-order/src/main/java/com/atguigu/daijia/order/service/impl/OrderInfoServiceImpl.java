package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.OrderBill;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.entity.order.OrderProfitsharing;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.StartDriveForm;
import com.atguigu.daijia.model.form.order.UpdateOrderBillForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.order.*;
import com.atguigu.daijia.order.mapper.OrderBillMapper;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderProfitsharingMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private OrderBillMapper orderBillMapper;
    @Autowired
    private OrderProfitsharingMapper orderProfitsharingMapper;

    @Override
    public Long saveOrderInfo(OrderInfoForm orderInfoForm) {
        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(orderInfoForm, orderInfo);
        String orderNo = UUID.randomUUID().toString().replaceAll("-", "");
        orderInfo.setOrderNo(orderNo);
        orderInfo.setStatus(OrderStatus.WAITING_ACCEPT.getStatus());
        orderInfoMapper.insert(orderInfo);

        //订单生成之后，发送延迟消息
        this.sendDelaayMessage(orderInfo.getId());


        //向redis存放标识
        redisTemplate.opsForValue().set(RedisConstant.ORDER_ACCEPT_MARK, "0", RedisConstant.ORDER_ACCEPT_MARK_EXPIRES_TIME, TimeUnit.MINUTES);
        return orderInfo.getId();
    }

    private void sendDelaayMessage(Long id) {
        try {
            //创建队列
            RBlockingQueue<Object> blockingQueue = redissonClient.getBlockingQueue("queue_cancel");
            //将队列放入延迟队列当中
            RDelayedQueue<Object> delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
            //发送消息到延迟队列当中
            delayedQueue.offer(id.toString(),15, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
    }

    @Override
    public Integer getOrderStatus(Long orderId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId, orderId);
        wrapper.select(OrderInfo::getStatus);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        if (orderInfo == null) {
            return OrderStatus.NULL_ORDER.getStatus();
        }
        return orderInfo.getStatus();
    }

    //redisson实现司机抢单
    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {
        //判断订单是否存在
        if (!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }
        //司机抢单
        //创建锁
        RLock lock = redissonClient.getLock(RedisConstant.ROB_NEW_ORDER_LOCK + orderId);
        try {
            if (!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
                //防止重复抢单的操作
                throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
            }
            //获取锁
            boolean flag = lock.tryLock(10, 5, TimeUnit.SECONDS);
            if (flag) {
                //获取到锁结构
                LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(OrderInfo::getId, orderId);
                OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
                orderInfo.setDriverId(driverId);
                orderInfo.setStatus(2);
                orderInfo.setAcceptTime(new Date());
                int row = orderInfoMapper.updateById(orderInfo);
                if (row != 1) {
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }
                //删除redis中的标识
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
            }
        } catch (Exception e) {
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        } finally {
            if (lock.isLocked()) {
                //如果锁存在   释放锁
                lock.unlock();
            }
        }

        return true;

    }

    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        //获取乘客订单的信息
        wrapper.eq(OrderInfo::getCustomerId, customerId);
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus(),
                OrderStatus.UNPAID.getStatus()
        };
        //判断当前的接单状态为什么
        wrapper.in(OrderInfo::getStatus, statusArray);
        //获取数据最新的一条   作为降序排列
        wrapper.orderByDesc(OrderInfo::getId);
        wrapper.last("limit 1");
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if (orderInfo != null) {
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    @Override
    public CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId) {
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getDriverId, driverId);
        //司机发送完账单，司机端主要流程就走完（当前这些节点，司机端会调整到相应的页面处理逻辑）
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus()
        };
        queryWrapper.in(OrderInfo::getStatus, statusArray);
        queryWrapper.orderByDesc(OrderInfo::getId);
        queryWrapper.last("limit 1");
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if (null != orderInfo) {
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;

    }

    @Override
    public Boolean driverArriveStartLocation(Long orderId, Long driverId) {
        //更新订单状态和到达时间
        LambdaQueryWrapper<OrderInfo> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(OrderInfo::getId, orderId);
        lambdaQueryWrapper.eq(OrderInfo::getDriverId, driverId);
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setStatus(3);
        orderInfo.setArriveTime(new Date());
        int row = orderInfoMapper.update(orderInfo, lambdaQueryWrapper);
        if (row != 1) {
            throw new GuiguException(ResultCodeEnum.ARGUMENT_VALID_ERROR);
        }
        return true;
    }

    @Override
    public Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm) {
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, updateOrderCartForm.getOrderId());
        queryWrapper.eq(OrderInfo::getDriverId, updateOrderCartForm.getDriverId());

        OrderInfo updateOrderInfo = new OrderInfo();
        BeanUtils.copyProperties(updateOrderCartForm, updateOrderInfo);
        updateOrderInfo.setStatus(OrderStatus.UPDATE_CART_INFO.getStatus());
        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);
        if (row == 1) {
            //记录日志
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    //开始代驾服务
    @Override
    public Boolean startDrive(StartDriveForm startDriveForm) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getDriverId, startDriveForm.getDriverId());
        wrapper.eq(OrderInfo::getId, startDriveForm.getOrderId());
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setStatus(OrderStatus.START_SERVICE.getStatus());
        orderInfo.setStartServiceTime(new Date());
        int row = orderInfoMapper.update(orderInfo, wrapper);
        if (row == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }

    }

    @Override
    public Long getOrderNumByTime(String startTime, String endTime) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(OrderInfo::getStartServiceTime, startTime);
        wrapper.lt(OrderInfo::getStartServiceTime, endTime);
        Long count = orderInfoMapper.selectCount(wrapper);
        return count;
    }

    @Override
    public Boolean endDrive(UpdateOrderBillForm updateOrderBillForm) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId, updateOrderBillForm.getOrderId());
        wrapper.eq(OrderInfo::getDriverId, updateOrderBillForm.getDriverId());
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setStatus(OrderStatus.END_SERVICE.getStatus());
        orderInfo.setRealAmount(updateOrderBillForm.getTotalAmount());
        orderInfo.setFavourFee(updateOrderBillForm.getFavourFee());
        orderInfo.setRealDistance(updateOrderBillForm.getRealDistance());
        orderInfo.setEndServiceTime(new Date());
        int rows = orderInfoMapper.update(orderInfo, wrapper);
        if (rows == 1) {
            //添加账单数据
            OrderBill orderBill = new OrderBill();
            BeanUtils.copyProperties(updateOrderBillForm, orderBill);
            orderBill.setOrderId(updateOrderBillForm.getOrderId());
            orderBill.setPayAmount(updateOrderBillForm.getTotalAmount());
            orderBillMapper.insert(orderBill);
            //添加分账信息
            //插入分账信息数据
            OrderProfitsharing orderProfitsharing = new OrderProfitsharing();
            BeanUtils.copyProperties(updateOrderBillForm, orderProfitsharing);
            orderProfitsharing.setOrderId(updateOrderBillForm.getOrderId());
            orderProfitsharing.setRuleId(updateOrderBillForm.getProfitsharingRuleId());
            orderProfitsharing.setStatus(1);
            orderProfitsharingMapper.insert(orderProfitsharing);
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    @Override
    public PageVo findCustomerOrderPage(Page<OrderInfo> pageParam, Long customerId) {
       IPage<OrderListVo> pageInfo = orderInfoMapper.selectCustomerOrderPage(pageParam,customerId);
        return  new PageVo<>(pageInfo.getRecords(),pageInfo.getPages(),pageInfo.getTotal());
    }

    @Override
    public PageVo findDriverOrderPage(Page<OrderInfo> pageParam, Long driverId) {
        IPage<OrderListVo> pageInfo = orderInfoMapper.selectDriverOrderPage(pageParam, driverId);
        return new PageVo(pageInfo.getRecords(), pageInfo.getPages(), pageInfo.getTotal());
    }

    @Override
    public OrderBillVo getOrderBillInfo(Long orderId) {
        LambdaQueryWrapper<OrderBill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderBill::getOrderId,orderId);
        OrderBill orderBill = orderBillMapper.selectOne(wrapper);
        OrderBillVo orderBillVo = new OrderBillVo();
        BeanUtils.copyProperties(orderBill,orderBillVo);
        return orderBillVo;
    }

    @Override
    public OrderProfitsharingVo getOrderProfitsharing(Long orderId) {
        OrderProfitsharing orderProfitsharing = orderProfitsharingMapper.selectOne(new LambdaQueryWrapper<OrderProfitsharing>().eq(OrderProfitsharing::getOrderId, orderId));
        OrderProfitsharingVo orderProfitsharingVo = new OrderProfitsharingVo();
        BeanUtils.copyProperties(orderProfitsharing, orderProfitsharingVo);
        return orderProfitsharingVo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean sendOrderBillInfo(Long orderId, Long driverId) {
        //更新订单信息
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, orderId);
        queryWrapper.eq(OrderInfo::getDriverId, driverId);
        //更新字段
        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(OrderStatus.UNPAID.getStatus());
        //只能更新自己的订单
        int row = orderInfoMapper.update(updateOrderInfo, queryWrapper);
        if(row == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }

    }

    @Override
    public OrderPayVo getOrderPayVo(String orderNo, Long customerId) {
       OrderPayVo orderPayVo = orderInfoMapper.selectOrderPayVo(orderNo,customerId);
       if (orderPayVo!=null){
           String content = orderPayVo.getStartLocation()+"到"+orderPayVo.getEndLocation();
           orderPayVo.setContent(content);
       }
        return orderPayVo;
    }

    @Override
    public Boolean updateOrderPayStatus(String orderNo) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getOrderNo, orderNo);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        if (orderInfo == null || orderInfo.getStatus() == 8){
            return true;
        }
        LambdaQueryWrapper<OrderInfo> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(OrderInfo::getId, orderInfo.getId());
        OrderInfo updateOrderInfo = new OrderInfo();
        updateOrderInfo.setStatus(8);
        updateOrderInfo.setPayTime(new Date());
        int update = orderInfoMapper.update(updateOrderInfo, updateWrapper);
        if (update == 1){
            return true;
        }else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }

    }

    @Override
    public OrderRewardVo getOrderRewardFee(String orderNo) {
        //查询订单
        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo).select(OrderInfo::getId,OrderInfo::getDriverId));
        //账单
        OrderBill orderBill = orderBillMapper.selectOne(new LambdaQueryWrapper<OrderBill>().eq(OrderBill::getOrderId, orderInfo.getId()).select(OrderBill::getRewardFee));
        OrderRewardVo orderRewardVo = new OrderRewardVo();
        orderRewardVo.setOrderId(orderInfo.getId());
        orderRewardVo.setDriverId(orderInfo.getDriverId());
        orderRewardVo.setRewardFee(orderBill.getRewardFee());
        return orderRewardVo;
    }

    @Override
    public void orderCancel(long orderId) {
        OrderInfo orderInfo = orderInfoMapper.selectById(orderId);
        if (orderInfo.getStatus() == 1){
            orderInfo.setStatus(-1);
            int row = orderInfoMapper.updateById(orderInfo);
            if (row==1){
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
            }
        }
    }

    //乐观锁实现司机抢单
    public Boolean robNewOrder1(Long driverId, Long orderId) {
        //判断订单是否存在
        if (!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //司机抢单
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId, orderId);
        //加入版本号
        wrapper.eq(OrderInfo::getStatus, 1);
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        orderInfo.setDriverId(driverId);
        orderInfo.setStatus(2);
        orderInfo.setAcceptTime(new Date());
        int row = orderInfoMapper.update(orderInfo, wrapper);
        if (row != 1) {
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }
        //删除redis中的标识
        redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);

        return true;

    }
}
