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

    /**
     * 初始化STS客户端Client，作为单例使用
     * 需要跨账号操作时，使用该STS Client，调用Assume Role接口跨账号角色扮演到其他账号（跨账号获取STS Token），实现跨账号操作
     */
    @Bean(name = "stsClient")
    com.aliyun.sts20150401.Client getStsClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(credentialClient)
            .setRegionId(regionId);
        return new com.aliyun.sts20150401.Client(config);
    }
}
