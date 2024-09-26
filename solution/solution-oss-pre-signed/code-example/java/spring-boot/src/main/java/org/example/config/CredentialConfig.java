package org.example.config;

import com.aliyun.credentials.Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialConfig {

    // 初始化凭据客户端，Credential SDK Client 应该是单例，不要每次请求都重新 new 一个，避免内存泄露
    // 借助Credentials工具的默认凭据链，您可以用同一套代码，通过程序之外的配置来控制不同环境下的凭据获取方式
    // 当您在初始化凭据客户端不传入任何参数时，Credentials工具将会尝试按照如下顺序查找相关凭据信息（优先级由高到低）：
    // 1. 使用系统属性
    // 2. 使用环境变量
    // 3. 使用OIDC RAM角色
    // 4. 使用配置文件
    // 5. 使用ECS实例RAM角色（需要通过环境变量 ALIBABA_CLOUD_ECS_METADATA 指定 ECS 实例角色名称；通过环境变量 ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true 开启在加固模式下获取STS Token）
    // https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials#3ca299f04bw3c
    // 要使用默认凭据链，初始化 Client 时，必须使用空的构造函数，不能配置 Config 入参
    @Bean
    Client getCredentialClient() {
        return new Client();
    }

    // 除了使用上面的默认凭据链，您也可以在代码中显式配置，来初始化凭据客户端
    // 如下所示，可以进行显式配置，以ECS实例角色为例
    //@Bean
    //Client getCredentialClient() {
    //    Config config = new Config()
    //        .setType("ecs_ram_role")
    //        // 选填，该ECS实例角色的角色名称，不填会自动获取，建议加上以减少请求次数
    //        .setRoleName("<请填写ECS实例角色的角色名称>")
    //        // 在加固模式下获取STS Token，强烈建议开启
    //        .setEnableIMDSv2(true);
    //    return new Client(config);
    //}
}
