package org.example.sdk2_0;

import com.alibaba.fastjson.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.vpc20160428.models.DescribeVpcsRequest;
import com.aliyun.vpc20160428.models.DescribeVpcsResponse;

/**
 * 默认凭据链
 */
public class CredentialsDefaultSample {
    public static void main(String[] args) throws Exception {
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
        Client credentialsClient = new Client();

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
