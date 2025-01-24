package org.example.config;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.provider.EcsRamRoleCredentialProvider;
//import com.aliyun.credentials.provider.EnvironmentVariableCredentialsProvider;
//import com.aliyun.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.credentials.provider.RamRoleArnCredentialProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialConfig {

    @Value("${ecs.instance.role.name}")
    String ecsInstanceRoleName;

    @Value("${role.arn}")
    String roleArn;

    @Bean(name = "ramRoleCredentialClient")
    Client getRamRoleCredentialClient() {
        // 初始化凭据Provider，代表应用程序当前的角色身份，保证您的应用程序本身是无AK的
        // 这里您需要根据具体场景，通过对应的Provider类进行初始化。常见的有以下几种：
        // 1. 使用ECS实例RAM角色
        EcsRamRoleCredentialProvider originalProvider = EcsRamRoleCredentialProvider.builder()
            // 请替换为绑定到ECS实例上的RAM角色名称
            .roleName(ecsInstanceRoleName)
            .build();
        // 2. 使用环境变量
        //EnvironmentVariableCredentialsProvider originalProvider = new EnvironmentVariableCredentialsProvider();
        // 3. 使用OIDC RAM角色
        //OIDCRoleArnCredentialProvider originalProvider = OIDCRoleArnCredentialProvider.builder()
        //    .roleArn(System.getenv("ALIBABA_CLOUD_ROLE_ARN"))
        //    .oidcProviderArn(System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN"))
        //    .oidcTokenFilePath(System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE"))
        //    // 角色会话名称，如果配置了ALIBABA_CLOUD_ROLE_SESSION_NAME这个环境变量，则无需设置
        //    .roleSessionName("<RoleSessionName>")
        //    // 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
        //    .policy("<Policy>")
        //    .roleSessionName("3600")
        //    .build();

        // 使用当前角色作为入参，初始化角色扮演的Provider，实现角色链式扮演，同时支持跨账号扮演角色
        RamRoleArnCredentialProvider provider = RamRoleArnCredentialProvider.builder()
            .credentialsProvider(originalProvider)
            .durationSeconds(3600)
            // 请替换为您实际要扮演的RAM角色ARN
            // 格式为 acs:ram::${账号 ID}:role/${角色名称}
            .roleArn(roleArn)
            .build();

        // 通过Provider初始化凭据客户端，通过客户端获取角色扮演后最终的STS Token
        // 研发无需关心STS Token有效期和重新获取的逻辑，此方式支持自动刷新STS Token
        return new Client(provider);
    }
}
