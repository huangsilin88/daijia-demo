package com.atguigu.daijia.dispatch.xxl.job;

import com.atguigu.daijia.dispatch.mapper.XxlJobLogMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.model.entity.dispatch.XxlJobLog;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JobHandler {

    @Autowired
    private XxlJobLogMapper xxlJobLogMapper;

    @Autowired
    private NewOrderService newOrderService;

    @XxlJob("newOrderTaskHandler")
    public void newOrderTaskHandler(){

        XxlJobLog xxlJobLog = new XxlJobLog();
        xxlJobLog.setJobId(XxlJobHelper.getJobId());
        long startTime = System.currentTimeMillis();
        try {
            newOrderService.executeTask(XxlJobHelper.getJobId());
            //成功状态
            xxlJobLog.setStatus(1);
        }catch (Exception e){
            //失败状态
            xxlJobLog.setStatus(0);
            xxlJobLog.setError(e.toString());
            e.printStackTrace();
        }finally {
            long endTime = System.currentTimeMillis();
            xxlJobLog.setTimes((int) (endTime - startTime));
            xxlJobLogMapper.insert(xxlJobLog);
        }
    }
}
