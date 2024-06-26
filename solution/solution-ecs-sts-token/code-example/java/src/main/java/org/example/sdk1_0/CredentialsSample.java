package org.example.sdk1_0;

import com.alibaba.fastjson.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsRequest;
import com.aliyuncs.vpc.model.v20160428.DescribeVpcsResponse;

/**
 * 通过Credentials工具初始化
 */
public class CredentialsSample {
    public static void main(String[] args) throws ClientException {
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou");
        // 初始化凭据客户端
        Client credentialClient = new Client();
        // 用凭据客户端初始化SDK1.0客户端
        IAcsClient client = createAcsClientByCredentials(profile, credentialClient);

        // 调用API，以VPC为例
        DescribeVpcsRequest describeVpcsRequest = new DescribeVpcsRequest();
        describeVpcsRequest.setRegionId("cn-hangzhou");
        DescribeVpcsResponse describeVpcsResponse = client.getAcsResponse(describeVpcsRequest);
        System.out.println(JSON.toJSONString(describeVpcsResponse, true));
    }

    public static IAcsClient createAcsClientByCredentials(DefaultProfile profile, Client credentialClient) {
        return new DefaultAcsClient(profile, () -> {
            // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
            CredentialModel credentialModel = credentialClient.getCredential();
            String ak = credentialModel.getAccessKeyId();
            String sk = credentialModel.getAccessKeySecret();
            String token = credentialModel.getSecurityToken();
            return new BasicSessionCredentials(ak, sk, token);
        });
    }
}
