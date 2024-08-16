package com.atguigu.daijia.order.handle;

import com.atguigu.daijia.order.service.OrderInfoService;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

//监听延时队列
@Component
public class RedisDelayHandle {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private OrderInfoService orderInfoService;
    //在bean初始化之后执行的方法
    @PostConstruct
    public void listener(){
        new Thread(()->{
            while (true){
                //获取到延迟队列
                RBlockingDeque<String> blockingDeque = redissonClient.getBlockingDeque("queue_cancel");

                //从队列中获取到信息
                try {
                    String orderId = blockingDeque.take();

                    //取消订单
                    if (StringUtils.hasText(orderId)){
                        orderInfoService.orderCancel(Long.parseLong(orderId));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
    }
}
