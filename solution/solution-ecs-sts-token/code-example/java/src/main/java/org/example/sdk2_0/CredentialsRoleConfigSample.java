package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;

/**
 * 显示配置实例RAM角色
 */
public class CredentialsRoleConfigSample {
    public static void main(String[] args) throws Exception {
        Config credentialConfig = new Config();
        credentialConfig.setType("ecs_ram_role");
        // 选填，该ECS角色的角色名称，不填会自动获取，建议加上以减少请求次数
        credentialConfig.setRoleName("my-ecs-role");
        // 在加固模式下获取STS Token，强烈建议开启
        credentialConfig.setEnableIMDSv2(true);
        Client credentialsClient = new Client(credentialConfig);

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.setCredential(credentialsClient);
        config.setEndpoint("sts.cn-hangzhou.aliyuncs.com");
        com.aliyun.sts20150401.Client stsClient = new com.aliyun.sts20150401.Client(config);

        GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentity();
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
