package com.atguigu.daijia.dispatch.xxl.job;


import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class DispatchJobHandler {

    @XxlJob("test")
public void test()
{
    System.out.println("项目测试进行中");
}

}
