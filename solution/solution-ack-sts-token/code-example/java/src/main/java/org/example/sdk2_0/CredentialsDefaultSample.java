package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.vpc20160428.models.DescribeVpcsRequest;
import com.aliyun.vpc20160428.models.DescribeVpcsResponse;

/**
 * 通过Credentials工具初始化，使用默认凭据链
 */
public class CredentialsDefaultSample {
    public static void main(String[] args) throws Exception {
        // 初始化凭据客户端
        Client credentialClient = new Client();

        // 调用API，以VPC为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.setCredential(credentialClient);
        config.setEndpoint("vpc.aliyuncs.com");
        com.aliyun.vpc20160428.Client vpcClient = new com.aliyun.vpc20160428.Client(config);

        DescribeVpcsRequest describeVpcsRequest = new DescribeVpcsRequest().setRegionId("cn-hangzhou");
        DescribeVpcsResponse describeVpcsResponse = vpcClient.describeVpcs(describeVpcsRequest);
        System.out.println(JSON.toJSONString(describeVpcsResponse));
    }
}
