package org.example.service;

import com.alibaba.fastjson2.JSON;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SdkV1Service {

    @Autowired
    com.aliyuncs.IAcsClient sdkV1Client;

    /**
     * 调用API，以调用GetCallerIdentity获取当前调用者身份信息为例
     */
    public String getCallerIdentity() {
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        try {
            GetCallerIdentityResponse getCallerIdentityResponse = sdkV1Client.getAcsResponse(getCallerIdentityRequest);
            return JSON.toJSONString(getCallerIdentityResponse);
        } catch (ClientException e) {
            e.printStackTrace();
            return null;
        }
    }
}
