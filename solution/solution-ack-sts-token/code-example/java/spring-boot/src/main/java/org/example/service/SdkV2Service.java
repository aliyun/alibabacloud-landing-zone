package org.example.service;

import com.alibaba.fastjson2.JSON;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SdkV2Service {

    @Autowired
    com.aliyun.sts20150401.Client sdkV2StsClient;

    /**
     * 调用API，以调用GetCallerIdentity获取当前调用者身份信息为例
     */
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
