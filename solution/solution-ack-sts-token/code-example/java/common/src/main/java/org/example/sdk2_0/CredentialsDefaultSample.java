package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;

/**
 * 通过Credentials工具初始化，使用默认凭据链
 */
public class CredentialsDefaultSample {
    public static void main(String[] args) throws Exception {
        // 初始化凭据客户端
        Client credentialsClient = new Client();

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.setCredential(credentialsClient);
        config.setEndpoint("sts.cn-hangzhou.aliyuncs.com");
        com.aliyun.sts20150401.Client stsClient = new com.aliyun.sts20150401.Client(config);
        GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentity();
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
