package org.example.sdk1_0;

import com.alibaba.fastjson2.JSON;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;

/**
 * 通过环境变量初始化
 */
public class EnvConfigSample {
    public static void main(String[] args) throws ClientException {
        IAcsClient client = new DefaultAcsClient("cn-hangzhou");

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        GetCallerIdentityResponse getCallerIdentityResponse = client.getAcsResponse(getCallerIdentityRequest);
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
