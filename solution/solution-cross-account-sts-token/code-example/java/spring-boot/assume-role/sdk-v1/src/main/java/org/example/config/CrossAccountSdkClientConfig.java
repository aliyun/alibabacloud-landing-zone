package org.example.config;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.auth.STSAssumeRoleSessionCredentialsProvider;
import com.aliyuncs.profile.DefaultProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CrossAccountSdkClientConfig {

    @Value("${region.id}")
    String regionId;

    @Value("${role.arn}")
    String roleArn;

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    /**
     * 使用跨账号的角色身份，初始化SDK 1.0客户端Client，作为单例使用
     * 如果您有多个账号都需要跨账号操作，需要为每个账号创建一个Client
     */
    @Bean(name = "crossAccountSdkClient")
    com.aliyuncs.IAcsClient getCrossAccountSdkClient() {
        DefaultProfile profile = DefaultProfile.getProfile(regionId);
        // 用凭据客户端初始化角色扮演的CredentialsProvider：STSAssumeRoleSessionCredentialsProvider，实现跨账号角色扮演
        // 该CredentialsProvider支持自动刷新STS Token
        STSAssumeRoleSessionCredentialsProvider provider = new STSAssumeRoleSessionCredentialsProvider(
            () -> {
                // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
                CredentialModel credentialModel = credentialClient.getCredential();
                String ak = credentialModel.getAccessKeyId();
                String sk = credentialModel.getAccessKeySecret();
                String token = credentialModel.getSecurityToken();
                return new BasicSessionCredentials(ak, sk, token);
            },
            roleArn,
            profile
        )
        // 角色会话名称
        .withRoleSessionName("WellArchitectedSolutionDemo")
        // STS Token 有效期，单位：秒
        .withRoleSessionDurationSeconds(3600L);

        // 初始化SDK 1.0客户端
        return  new DefaultAcsClient(profile, provider);
    }
}
