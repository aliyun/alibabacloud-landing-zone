package org.example.config;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.auth.DefaultCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlsClientConfig {

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    @Bean(name = "slsClient")
    com.aliyun.openservices.log.Client getSlsClient() {
        // 日志服务的服务接入点，以华东1（杭州）为例
        String endpoint = "cn-hangzhou.log.aliyuncs.com";
        return new Client(endpoint, () -> {
            // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
            CredentialModel credentialModel = credentialClient.getCredential();
            String ak = credentialModel.getAccessKeyId();
            String sk = credentialModel.getAccessKeySecret();
            String token = credentialModel.getSecurityToken();
            return new DefaultCredentials(ak, sk, token);
        });
    }
}
