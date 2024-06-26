package org.example;

import com.aliyun.kms.secretsmanager.plugin.sdkcore.ProxyAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsRequest;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsResponse;
import com.alibaba.fastjson.JSON;

import java.util.ResourceBundle;

public class Main {
    public static void main(String[] args) {
        // 1. 获取ACSClient by aliyun-java-sdk-managed-credentials-provider
        IAcsClient client = null;
        try {
            // 通过 AKExpireHandler 自定义错误重试判断逻辑，对因凭据手动轮转极端场景下 AK 失效的错误进行重试
            client = new ProxyAcsClient("cn-hangzhou", "acs/ram/user/workshop-kms-ram-secret", new AliyunSdkAKExpireHandler());
        } catch (ClientException e) {
            e.printStackTrace();
        }

        // 2. 调用OpenAPI实现业务功能
        ResourceBundle resource = ResourceBundle.getBundle("application");
        String vpcEndpoint = resource.getString("endpoint.vpc");
        DescribeVpcsRequest request = new DescribeVpcsRequest();
        request.setSysEndpoint(vpcEndpoint);
        DescribeVpcsResponse response;
        try {
            response = client.getAcsResponse(request);
            System.out.println(JSON.toJSONString(response));
        } catch (ClientException e) {
            e.printStackTrace();
        }

        // 3. 通过下面方法关闭客户端来释放插件关联的资源
        client.shutdown();
    }
}