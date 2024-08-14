package org.example.controller;

import com.alibaba.fastjson2.JSON;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @Autowired
    com.aliyuncs.IAcsClient crossAccountSdkClient;

    /**
     * 调用API，跨账号进行资源操作
     * 以调用GetCallerIdentity获取当前调用者身份信息为例
     */
    @GetMapping("/getCallerIdentity")
    public String getCallerIdentity() {
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        try {
            GetCallerIdentityResponse getCallerIdentityResponse = crossAccountSdkClient.getAcsResponse(getCallerIdentityRequest);
            return JSON.toJSONString(getCallerIdentityResponse);
        } catch (ClientException e) {
            e.printStackTrace();
            return null;
        }
    }
}
