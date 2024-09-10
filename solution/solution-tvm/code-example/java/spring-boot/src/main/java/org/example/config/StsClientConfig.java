package org.example.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StsClientConfig {

    @Value("${region.id}")
    String regionId;

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    // 初始化阿里云 V2 版本 STS 的 SDK 客户端
    // SDK Client 应该是单例，不要每次请求都重新 New 一个，避免内存泄露
    @Bean(name = "stsClient")
    com.aliyun.sts20150401.Client getStsClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(credentialClient)
            .setRegionId(regionId);
        return new com.aliyun.sts20150401.Client(config);
    }
}
