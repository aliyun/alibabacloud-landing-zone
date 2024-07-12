package org.example.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SdkV2ClientConfig {

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    @Bean(name = "sdkV2StsClient")
    com.aliyun.sts20150401.Client getSdkV2StsClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(credentialClient)
            // 以华东1（杭州）为例
            .setEndpoint("sts.cn-hangzhou.aliyuncs.com");
        return new com.aliyun.sts20150401.Client(config);
    }
}
