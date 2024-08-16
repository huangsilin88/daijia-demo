package com.atguigu.daijia.dispatch.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.dispatch.mapper.OrderJobMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.dispatch.xxl.client.XxlJobClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.entity.dispatch.OrderJob;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.order.NewOrderDataVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class NewOrderServiceImpl implements NewOrderService {

    @Autowired
    private OrderJobMapper orderJobMapper;
    @Autowired
    private XxlJobClient xxlJobClient;
    @Autowired
    private LocationFeignClient locationFeignClient;
    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Long addAndStartTask(NewOrderTaskVo newOrderTaskVo) {
        //判断当前任务是否启动
        LambdaQueryWrapper<OrderJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderJob::getOrderId, newOrderTaskVo.getOrderId());
        OrderJob orderJob = orderJobMapper.selectOne(wrapper);
        if (orderJob == null){
    //handler:执行任务的方法
            //   cron   表达式
            //    描述信息
            Long jobId = xxlJobClient.addAndStartJob("newOrderTaskHandler",
                    "",
                    "0 0/1 * * * ? ",
                    "新创建订单任务调度" + newOrderTaskVo.getOrderId());
            orderJob = new OrderJob();
            orderJob.setOrderId(newOrderTaskVo.getOrderId());
            orderJob.setJobId(jobId);
            orderJob.setParameter(JSONObject.toJSONString(newOrderTaskVo));
            orderJobMapper.insert(orderJob );
        }
        return orderJob.getJobId();
    }

    //搜索附近司机的功能
    @Override
    public void executeTask(long jobId) {
      //查询数据库表中是否存在
        LambdaQueryWrapper<OrderJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderJob::getJobId,jobId);
        OrderJob orderJob = orderJobMapper.selectOne(wrapper);
        if (orderJob == null){
            return;
        }
        //查询订单状态
        String parameter = orderJob.getParameter();
        NewOrderTaskVo newOrderTaskVo = JSONObject.parseObject(parameter, NewOrderTaskVo.class);
        //获取订单id
        Long orderId = newOrderTaskVo.getOrderId();
        Integer status = orderInfoFeignClient.getOrderStatus(orderId).getData();
        if (status.intValue() != OrderStatus.WAITING_ACCEPT.getStatus().intValue()){
            //不是接单状态   退出任务调度
            xxlJobClient.stopJob(jobId);
            return;
        }
        //开启远程调用寻找司机
        SearchNearByDriverForm searchNearByDriverForm = new SearchNearByDriverForm();
        searchNearByDriverForm.setLongitude(newOrderTaskVo.getStartPointLongitude());
        searchNearByDriverForm.setLatitude(newOrderTaskVo.getStartPointLatitude());
        searchNearByDriverForm.setMileageDistance(newOrderTaskVo.getExpectDistance());
        //满足司机的列表
        List<NearByDriverVo> driverDataList = locationFeignClient.searchNearByDriver(searchNearByDriverForm).getData();
        //将司机列表添加到队列
        driverDataList.forEach(driver->{
            //将订单的信息推送给每一为司机
            //超过15分钟没有司机接单自动取消
            //使用redis中的set集合
            String key = RedisConstant.DRIVER_ORDER_REPEAT_LIST+newOrderTaskVo.getOrderId();
            //记录司机的id防止重复推送
            Boolean member = redisTemplate.opsForSet().isMember(key, driver.getDriverId());
            //有数据为true  表示推送过了
            if (!member){
                redisTemplate.opsForSet().add(key,driver.getDriverId());
                //过期时间
                redisTemplate.expire(key,15, TimeUnit.MINUTES);

                NewOrderDataVo newOrderDataVo = new NewOrderDataVo();
                newOrderDataVo.setOrderId(newOrderTaskVo.getOrderId());
                newOrderDataVo.setStartLocation(newOrderTaskVo.getStartLocation());
                newOrderDataVo.setEndLocation(newOrderTaskVo.getEndLocation());
                newOrderDataVo.setExpectAmount(newOrderTaskVo.getExpectAmount());
                newOrderDataVo.setExpectDistance(newOrderTaskVo.getExpectDistance());
                newOrderDataVo.setExpectTime(newOrderTaskVo.getExpectTime());
                newOrderDataVo.setFavourFee(newOrderTaskVo.getFavourFee());
                newOrderDataVo.setDistance(driver.getDistance());
                newOrderDataVo.setCreateTime(newOrderTaskVo.getCreateTime());
                //将新订单保存到队列当中  list
                String key1 = RedisConstant.DRIVER_ORDER_TEMP_LIST+driver.getDriverId();
                redisTemplate.opsForList().leftPush(key1,JSONObject.toJSONString(newOrderDataVo));
                redisTemplate.expire(key1,15, TimeUnit.MINUTES);
            }

        });

    }
//获取最新订单
    @Override
    public List<NewOrderDataVo> findNewOrderQueueData(Long driverId) {
        List<NewOrderDataVo> list = new ArrayList<>();
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        Long size = redisTemplate.opsForList().size(key);
        if (size > 0){
            for (int i = 0; i < size; i++) {
                String content = (String) redisTemplate.opsForList().leftPop(key);
                NewOrderDataVo newOrderDataVo = JSONObject.parseObject(content, NewOrderDataVo.class);
                list.add(newOrderDataVo);
            }
        }
        return list;
    }

    @Override
    public Boolean clearNewOrderQueueData(Long driverId) {
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        //直接删除，司机开启服务后，有新订单会自动创建容器
        redisTemplate.delete(key);
        return true;
    }
}
