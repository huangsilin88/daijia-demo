package com.atguigu.daijia.rules.service.impl;

import com.atguigu.daijia.model.form.rules.RewardRuleRequest;
import com.atguigu.daijia.model.form.rules.RewardRuleRequestForm;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponse;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponseVo;
import com.atguigu.daijia.rules.mapper.RewardRuleMapper;
import com.atguigu.daijia.rules.service.RewardRuleService;
import com.atguigu.daijia.rules.utils.DroolsHelper;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class RewardRuleServiceImpl implements RewardRuleService {

    private static final String RULES_CUSTOMER_RULES_DRL = "rules/RewardRule.drl";

    @Override
    public RewardRuleResponseVo calculateOrderRewardFee(RewardRuleRequestForm rewardRuleRequestForm) {
        RewardRuleRequest rewardRuleRequest = new RewardRuleRequest();
        rewardRuleRequest.setOrderNum(rewardRuleRequestForm.getOrderNum());
        rewardRuleRequest.setStartTime(rewardRuleRequest.getStartTime());
        //创建规则引擎
        KieSession kieSession = DroolsHelper.loadForRule(RULES_CUSTOMER_RULES_DRL);
        RewardRuleResponse rewardRuleResponse = new RewardRuleResponse();
        kieSession.setGlobal("rewardRuleResponse",rewardRuleResponse);
        //设置对象   触发规则
        kieSession.insert(rewardRuleRequest);
        kieSession.fireAllRules();
        kieSession.dispose();

        RewardRuleResponseVo rewardRuleResponseVo = new RewardRuleResponseVo();
        rewardRuleResponseVo.setRewardAmount(rewardRuleResponse.getRewardAmount());
//        rewardRuleResponseVo.setRewardRuleId(rewardRule.getId());
        return rewardRuleResponseVo;
    }
}
