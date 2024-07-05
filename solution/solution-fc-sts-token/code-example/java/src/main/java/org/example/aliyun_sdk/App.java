package org.example.aliyun_sdk;

import java.io.InputStream;
import java.io.OutputStream;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.utils.AuthConstant;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.Credentials;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.sts20150401.Client;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.teaopenapi.models.Config;

public class App implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) {
        // 从上下文获取凭证信息
        Credentials creds = context.getExecutionCredentials();

        try {
            Config config = new Config().setRegionId("cn-hangzhou").setCredential(createCredential(creds));

            // 查看当前调用者身份
            Client stsClient = new Client(config);
            GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentity();
            outputStream.write(JSON.toJSONString(getCallerIdentityResponse).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static com.aliyun.credentials.Client createCredential(Credentials creds) {
        com.aliyun.credentials.models.Config config = new com.aliyun.credentials.models.Config();

        config.type = AuthConstant.STS;
        config.accessKeyId = creds.getAccessKeyId();
        config.accessKeySecret = creds.getAccessKeySecret();
        config.securityToken = creds.getSecurityToken();

        return new com.aliyun.credentials.Client(config);
    }
}