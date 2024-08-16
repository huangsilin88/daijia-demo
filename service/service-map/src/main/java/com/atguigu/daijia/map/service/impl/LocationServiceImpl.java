package com.atguigu.daijia.map.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.util.LocationUtil;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.repository.OrderServiceLocationRepository;
import com.atguigu.daijia.map.service.LocationService;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.entity.map.OrderServiceLocation;
import com.atguigu.daijia.model.form.map.OrderServiceLocationForm;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.form.map.UpdateOrderLocationForm;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationServiceImpl implements LocationService {

    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;
    @Autowired
    private OrderServiceLocationRepository orderServiceLocationRepository;

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;
    @Override
    public Boolean updateDriverLocation(UpdateDriverLocationForm updateDriverLocationForm) {
        Point point = new Point(updateDriverLocationForm.getLongitude().doubleValue(), updateDriverLocationForm.getLatitude().doubleValue());
        redisTemplate.opsForGeo().add(RedisConstant.DRIVER_GEO_LOCATION, point, updateDriverLocationForm.getDriverId().toString());
        return true;
    }

    @Override
    public Boolean removeDriverLocation(Long driverId) {
        Long remove = redisTemplate.opsForGeo().remove(RedisConstant.DRIVER_GEO_LOCATION, driverId.toString());
        return remove > 0;
    }

    @Override
    public List<NearByDriverVo> searchNearByDriver(SearchNearByDriverForm searchNearByDriverForm) {
        //搜索5公里内的司机
        //定义point 放入经纬度
        Point point = new Point(searchNearByDriverForm.getLongitude().doubleValue(), searchNearByDriverForm.getLatitude().doubleValue());
        //定义距离
        Distance distance = new Distance(SystemConstant.ACCEPT_DISTANCE, RedisGeoCommands.DistanceUnit.KILOMETERS);
        Circle circle = new Circle(point, distance);
//定义geo的参数
        //包含返回结果的值
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()//包含距离
                .includeCoordinates()//包含坐标
                .sortAscending();//升序排列


        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().radius(RedisConstant.DRIVER_GEO_LOCATION, circle, args);
        //查询redis中并且返回list集合数据
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        //将集合进行处理
        List<NearByDriverVo> arrayList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(content)) {
            Iterator<GeoResult<RedisGeoCommands.GeoLocation<String>>> iterator = content.iterator();
            while (iterator.hasNext()) {
                GeoResult<RedisGeoCommands.GeoLocation<String>> item = iterator.next();
                //获取司机id
                Long driverId = Long.valueOf(item.getContent().getName());
                //获取司机里面设置的距离
                Result<DriverSet> driverSet = driverInfoFeignClient.getDriverSet(driverId);
                DriverSet driverSetData = driverSet.getData();
                if (driverSetData == null) {
                    continue;
                }
                BigDecimal orderDistance = driverSetData.getOrderDistance();
                //不符合判断
                if (orderDistance.doubleValue() != 0 && orderDistance.subtract(searchNearByDriverForm.getMileageDistance()).doubleValue() < 0) {
                    continue;
                }
                BigDecimal acceptDistance = driverSetData.getAcceptDistance();
                //接单的距离
                BigDecimal bigDecimal = new BigDecimal(item.getDistance().getValue()).setScale(2, RoundingMode.HALF_EVEN);
                if (acceptDistance.doubleValue() != 0 && acceptDistance.subtract(bigDecimal).doubleValue() < 0) {
                    continue;
                }
                NearByDriverVo nearByDriverVo = new NearByDriverVo();
                nearByDriverVo.setDriverId(driverId);
                nearByDriverVo.setDistance(bigDecimal);
                arrayList.add(nearByDriverVo);
            }
        }
        return arrayList;
    }

    @Override
    public Boolean updateOrderLocationToCache(UpdateOrderLocationForm updateOrderLocationForm) {
        //实时更新位置信息
        OrderLocationVo orderLocationVo = new OrderLocationVo();
        orderLocationVo.setLongitude(updateOrderLocationForm.getLongitude());
        orderLocationVo.setLatitude(updateOrderLocationForm.getLatitude());
        redisTemplate.opsForValue().set(RedisConstant.UPDATE_ORDER_LOCATION + updateOrderLocationForm.getOrderId(), orderLocationVo);
        return true;
    }

    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {
        OrderLocationVo orderLocationVo = (OrderLocationVo) redisTemplate.opsForValue().get(RedisConstant.UPDATE_ORDER_LOCATION + orderId);
        if (orderLocationVo != null) {
            return orderLocationVo;
        } else {
            throw new GuiguException(500, "获取司机位置失败请联系司机进行位置的确认");
        }
    }

    @Override
    public Boolean saveOrderServiceLocation(List<OrderServiceLocationForm> orderLocationServiceFormList) {
        List<OrderServiceLocation> list = new ArrayList<>();
        orderLocationServiceFormList.forEach(orderServiceLocationForm -> {
            OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
            BeanUtils.copyProperties(orderServiceLocationForm, orderServiceLocation);
            orderServiceLocation.setId(ObjectId.get().toString());
            orderServiceLocation.setCreateTime(new Date());
            list.add(orderServiceLocation);
        });
        orderServiceLocationRepository.saveAll(list);
        return true;
    }

    @Override
    public OrderServiceLastLocationVo getOrderServiceLastLocation(Long orderId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("orderId").is(orderId));
        query.with(Sort.by(Sort.Order.desc("createTime")));
        query.limit(1);

        OrderServiceLocation one = mongoTemplate.findOne(query, OrderServiceLocation.class);
        OrderServiceLastLocationVo orderServiceLastLocationVo = new OrderServiceLastLocationVo();
        if (one != null) {
            BeanUtils.copyProperties(one, orderServiceLastLocationVo);
        }else {
            throw new GuiguException(500,"查询实时位置失败请联系管理员");
        }
        return orderServiceLastLocationVo;
    }

    @Override
    public BigDecimal calculateOrderRealDistance(Long orderId) {
        //根据订单id查询信息
        //查询MongoDB

//        OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
//        orderServiceLocation.setOrderId(orderId);
//        Example<OrderServiceLocation> orderServiceLocationExample = Example.of(orderServiceLocation);
//        Sort createTime = Sort.by(Sort.Direction.ASC, "createTime");
//        List<OrderServiceLocation> all = orderServiceLocationRepository.findAll(orderServiceLocationExample, createTime);
        List<OrderServiceLocation> list =  orderServiceLocationRepository.findByOrderIdOrderByCreateTimeAsc(orderId);
        double resDistance = 0;

        if (!CollectionUtils.isEmpty(list)){
            for (int i = 0 ,size = list.size() - 1; i < size; i++) {
                OrderServiceLocation location1 = list.get(i);
                OrderServiceLocation location2 = list.get(i + 1);
               double distance =  LocationUtil.getDistance(location1.getLatitude().doubleValue(),
                                          location1.getLongitude().doubleValue(),
                        location2.getLatitude().doubleValue(),
                        location2.getLongitude().doubleValue());
               resDistance += distance;
            }
        }
        if(resDistance == 0) {
            return orderInfoFeignClient.getOrderInfo(orderId).getData().getExpectDistance().add(new BigDecimal("5"));
        }
        return new BigDecimal(resDistance);
    }


}
