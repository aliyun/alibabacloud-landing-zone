package org.example.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SdkV2ClientConfig {

    @Value("${region.id}")
    String regionId;

    @Autowired
    com.aliyun.credentials.Client ramRoleCredentialClient;

    @Bean(name = "sdkV2StsClient")
    com.aliyun.sts20150401.Client getSdkV2StsClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(ramRoleCredentialClient)
            // 以华东1（杭州）为例
            .setRegionId(regionId);
        return new com.aliyun.sts20150401.Client(config);
    }
}
