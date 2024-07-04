package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.sts20150401.Client;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.teaopenapi.models.Config;

/**
 * 通过固定AK初始化
 * 作为反面使用示例，不建议使用，推荐通过Credentials SDK使用临时凭证STS Token
 */
public class LongTermAkSample {
    public static void main(String[] args) throws Exception {
        Config config = new Config()
            // 您的 AccessKey ID，这里从环境变量获取
            .setAccessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
            // 您的 AccessKey Secret，这里从环境变量获取
            .setAccessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
            .setEndpoint("sts.cn-hangzhou.aliyuncs.com");

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        Client stsClient = new Client(config);
        GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentity();
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
