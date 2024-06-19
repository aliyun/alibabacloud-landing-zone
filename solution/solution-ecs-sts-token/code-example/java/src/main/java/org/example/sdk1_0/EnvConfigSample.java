package org.example.sdk1_0;

import com.alibaba.fastjson.JSON;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsRequest;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsResponse;

/**
 * 通过环境变量初始化
 */
public class EnvConfigSample {
    public static void main(String[] args) throws ClientException {
        IAcsClient client = new DefaultAcsClient("cn-hangzhou");

        // 调用API，以VPC为例
        DescribeVpcsRequest describeVpcsRequest = new DescribeVpcsRequest();
        describeVpcsRequest.setRegionId("cn-hangzhou");
        DescribeVpcsResponse describeVpcsResponse = client.getAcsResponse(describeVpcsRequest);
        System.out.println(JSON.toJSONString(describeVpcsResponse, true));
    }
}
