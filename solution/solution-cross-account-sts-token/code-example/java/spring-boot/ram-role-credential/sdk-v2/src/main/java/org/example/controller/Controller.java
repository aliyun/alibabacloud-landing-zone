package org.example.controller;

import com.alibaba.fastjson2.JSON;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @Autowired
    com.aliyun.sts20150401.Client sdkV2StsClient;

    /**
     * 调用API，跨账号进行资源操作
     * 以调用GetCallerIdentity获取当前调用者身份信息为例
     */
    @GetMapping("/getCallerIdentity")
    public String getCallerIdentity() {
        try {
            GetCallerIdentityResponse getCallerIdentityResponse = sdkV2StsClient.getCallerIdentity();
            return JSON.toJSONString(getCallerIdentityResponse);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
