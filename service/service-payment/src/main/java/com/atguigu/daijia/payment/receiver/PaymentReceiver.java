package com.atguigu.daijia.payment.receiver;

import com.atguigu.daijia.common.constant.MqConst;
import com.atguigu.daijia.payment.service.WxPayService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.impl.AMQChannel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentReceiver {

    @Autowired
    private WxPayService wxPayService;
/**
 *@Date：2024/8/15
 *@Author：silin
 *@content:接受端
 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_PAY_SUCCESS,durable = "true"),
            exchange = @Exchange(value = MqConst.EXCHANGE_ORDER),
            key = {
                    MqConst.ROUTING_PAY_SUCCESS
            }
    ))
    public void paySuccess(String orderNo, Message message, Channel channel){
        wxPayService.handleOrder(orderNo);
    }
}
