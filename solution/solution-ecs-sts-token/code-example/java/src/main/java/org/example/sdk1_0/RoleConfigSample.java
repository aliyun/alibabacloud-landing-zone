package org.example.sdk1_0;

import com.alibaba.fastjson.JSON;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.auth.InstanceProfileCredentialsProvider;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsRequest;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsResponse;

/**
 * 通过实例角色初始化
 */
public class RoleConfigSample {
    public static void main(String[] args) throws ClientException {
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou");
        InstanceProfileCredentialsProvider provider = new InstanceProfileCredentialsProvider(
            "my-ecs-role" // ecs实例角色名
        );
        DefaultAcsClient client = new DefaultAcsClient(profile, provider);

        // 调用API，以VPC为例
        DescribeVpcsRequest describeVpcsRequest = new DescribeVpcsRequest();
        describeVpcsRequest.setRegionId("cn-hangzhou");
        DescribeVpcsResponse describeVpcsResponse = client.getAcsResponse(describeVpcsRequest);
        System.out.println(JSON.toJSONString(describeVpcsResponse, true));
    }
}
