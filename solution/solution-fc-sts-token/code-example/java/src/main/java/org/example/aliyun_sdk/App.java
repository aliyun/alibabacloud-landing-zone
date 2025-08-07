package org.example.aliyun_sdk;

import java.io.InputStream;
import java.io.OutputStream;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.utils.AuthConstant;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.sts20150401.Client;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.teaopenapi.models.Config;

public class App implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) {
        try {
            com.aliyun.credentials.Client credentialClient = createCredential();
            Config config = new Config().setRegionId("cn-hangzhou").setCredential(credentialClient);

            // 查看当前调用者身份
            Client stsClient = new Client(config);
            GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentity();
            outputStream.write(JSON.toJSONString(getCallerIdentityResponse).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static com.aliyun.credentials.Client createCredential() {
        com.aliyun.credentials.models.Config config = new com.aliyun.credentials.models.Config();

        config.type = AuthConstant.STS;
        // 从系统预留环境变量中获取凭证信息
        config.accessKeyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        config.accessKeySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        config.securityToken = System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN");

        return new com.aliyun.credentials.Client(config);
    }
}