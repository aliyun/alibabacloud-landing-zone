package org.example.config;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialConfig {

    // 初始化凭据客户端
    // 您可以在代码中显式配置，来初始化凭据客户端
    // 荐您使用该方式，在代码中明确指定实例角色，避免运行环境中的环境变量、配置文件等带来非预期的结果。
    @Bean(name = "credentialClient")
    Client getCredentialClient() {
       Config config = new Config()
           .setType("ecs_ram_role")
           // 选填，该ECS实例角色的角色名称，不填会自动获取，建议加上以减少请求次数
           .setRoleName("<请填写ECS实例角色的角色名称>")
           // 在加固模式下获取STS Token，强烈建议开启
           .setEnableIMDSv2(true);
       return new Client(config);
    }

    // 您也可以使用Credentials工具的默认凭据链方式初始化凭据客户端
    // 借助Credentials工具的默认凭据链，您可以用同一套代码，通过程序之外的配置来控制不同环境下的凭据获取方式
    // 除非您清楚的知道默认凭据链中凭据信息查询优先级以及您的程序运行的各个环境中凭据信息配置方式，否则不建议您使用默认凭据链
    // 当您在初始化凭据客户端不传入任何参数时，Credentials工具将会尝试按照如下顺序查找相关凭据信息（优先级由高到低）：
    // 1. 使用系统属性
    // 2. 使用环境变量
    // 3. 使用OIDC RAM角色
    // 4. 使用配置文件
    // 5. 使用ECS实例RAM角色（需要通过环境变量 ALIBABA_CLOUD_ECS_METADATA 指定 ECS 实例角色名称；通过环境变量 ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true 开启在加固模式下获取STS Token）
    // https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials#3ca299f04bw3c
    // 要使用默认凭据链，初始化 Client 时，必须使用空的构造函数，不能配置 Config 入参
    // @Bean(name = "credentialClient")
    // Client getCredentialClient() {
    //     return new Client();
    // }
}
