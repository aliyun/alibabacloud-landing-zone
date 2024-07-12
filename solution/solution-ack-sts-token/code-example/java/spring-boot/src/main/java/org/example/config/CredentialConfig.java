package org.example.config;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialConfig {

    // 初始化凭据客户端
    // 借助Credentials工具的默认凭据链，您可以用同一套代码，通过程序之外的配置来控制不同环境下的凭据获取方式
    // 当您在初始化凭据客户端不传入任何参数时，Credentials工具将会尝试按照如下顺序查找相关凭据信息（优先级由高到低）：
    // 1. 使用系统属性
    // 2. 使用环境变量
    // 3. 使用OIDC RAM角色
    // 4. 使用配置文件
    // 5. 使用ECS实例RAM角色（需要通过环境变量 ALIBABA_CLOUD_ECS_METADATA 指定 ECS 实例角色名称；通过环境变量 ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true 开启在加固模式下获取STS Token）
    // https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials#3ca299f04bw3c
    // 要使用默认凭据链，初始化 Client 时，必须使用空的构造函数，不能配置 Config 入参
    @Bean(name = "credentialClient")
    Client getCredentialClient() {
        return new Client();
    }

    // 除了使用上面的默认凭据链，您也可以在代码中显式配置，来初始化凭据客户端
    //@Bean(name = "credentialClient")
    //Client getCredentialClient() {
    //    Config config = new Config();
    //    config.setType("oidc_role_arn");
    //    config.setRoleArn(System.getenv("ALIBABA_CLOUD_ROLE_ARN"));
    //    config.setOidcProviderArn(System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN"));
    //    config.setOidcTokenFilePath(System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE"));
    //    // 角色会话名称，如果配置了ALIBABA_CLOUD_ROLE_SESSION_NAME这个环境变量，则无需设置
    //    config.setRoleSessionName("<RoleSessionName>");
    //    // 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
    //    config.setPolicy("<Policy>");
    //    // 设置session过期时间
    //    config.setRoleSessionExpiration(3600);
    //
    //    return new Client(config);
    //}
}
