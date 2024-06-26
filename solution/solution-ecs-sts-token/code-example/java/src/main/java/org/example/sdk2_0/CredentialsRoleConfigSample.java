package org.example.sdk2_0;

import com.alibaba.fastjson.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.vpc20160428.models.DescribeVpcsRequest;
import com.aliyun.vpc20160428.models.DescribeVpcsResponse;

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

        // 调用API，以VPC为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.setCredential(credentialsClient);
        config.setEndpoint("vpc.aliyuncs.com");
        com.aliyun.vpc20160428.Client vpcClient = new com.aliyun.vpc20160428.Client(config);

        DescribeVpcsRequest describeVpcsRequest = new DescribeVpcsRequest().setRegionId("cn-hangzhou");
        DescribeVpcsResponse describeVpcsResponse = vpcClient.describeVpcs(describeVpcsRequest);
        System.out.println(JSON.toJSONString(describeVpcsResponse, true));
    }
}
