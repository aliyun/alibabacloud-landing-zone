package org.example.config;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.profile.DefaultProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SdkV1ClientConfig {

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    @Bean(name = "sdkV1Client")
    com.aliyuncs.IAcsClient getSdkV1Client() {
        // 以华东1（杭州）为例
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou");
        return new DefaultAcsClient(profile, () -> {
            // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
            CredentialModel credentialModel = credentialClient.getCredential();
            String ak = credentialModel.getAccessKeyId();
            String sk = credentialModel.getAccessKeySecret();
            String token = credentialModel.getSecurityToken();
            return new BasicSessionCredentials(ak, sk, token);
        });
    }
}
