package com.atguigu.daijia.map.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.map.service.MapService;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class MapServiceImpl implements MapService {


    @Value("${tencent.map.key}")
    private String KEY;

    @Autowired
private RestTemplate restTemplate;

    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {

        String url = "https://apis.map.qq.com/ws/direction/v1/driving/?from={from}&to={to}&key={key}";

        Map<String, String> map = new HashMap<>();
        map.put("from", calculateDrivingLineForm.getStartPointLatitude() + "," + calculateDrivingLineForm.getStartPointLongitude());
        map.put("to", calculateDrivingLineForm.getEndPointLatitude() + "," + calculateDrivingLineForm.getEndPointLongitude());
        map.put("key", KEY);

        JSONObject result = restTemplate.getForObject(url, JSONObject.class, map);
        if(result.getIntValue("status") != 0) {
            throw new GuiguException(ResultCodeEnum.MAP_FAIL);
        }

        //获取返回对象
        JSONObject router = result.getJSONObject("result").getJSONArray("routes").getJSONObject(0);
        DrivingLineVo drivingLineVo = new DrivingLineVo();
        drivingLineVo.setDistance(router.getBigDecimal("distance")
                .divideToIntegralValue(new BigDecimal(1000))
                .setScale(2, RoundingMode.HALF_EVEN));
        drivingLineVo.setDuration(router.getBigDecimal("duration"));
        drivingLineVo.setPolyline(router.getJSONArray("polyline"));
    return drivingLineVo;
    }
}
