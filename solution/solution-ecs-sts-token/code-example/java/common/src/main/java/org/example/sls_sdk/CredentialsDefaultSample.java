package org.example.sls_sdk;

import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.auth.DefaultCredentials;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.response.ListProjectResponse;

/**
 * 通过Credentials工具初始化
 */
public class CredentialsDefaultSample {
    public static void main(String[] args) throws LogException {

        // 日志服务的服务接入点。以杭州为例，
        String endpoint = "cn-hangzhou.log.aliyuncs.com";

        // 初始化凭据客户端
        Config credentialConfig = new Config();
        credentialConfig.setType("ecs_ram_role");
        // 选填，该ECS角色的角色名称，不填会自动获取，建议加上以减少请求次数
        credentialConfig.setRoleName("<your-ecs-instance-role-name>");
        // 在加固模式下获取STS Token，强烈建议开启
        credentialConfig.setEnableIMDSv2(true);
        com.aliyun.credentials.Client credentialClient = new com.aliyun.credentials.Client(credentialConfig);

        // 用凭据客户端初始化SLS客户端
        Client slsClient = createSlsClientByCredentials(endpoint, credentialClient);

        // 调用SLS API
        ListProjectResponse listProjectResponse = slsClient.ListProject();
        System.out.println(listProjectResponse);

        //关闭SLS客户端
        slsClient.shutdown();
    }

    public static Client createSlsClientByCredentials(String endpoint, com.aliyun.credentials.Client credentialClient) {
        return new Client(endpoint, () -> {
            // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
            CredentialModel credentialModel = credentialClient.getCredential();
            String ak = credentialModel.getAccessKeyId();
            String sk = credentialModel.getAccessKeySecret();
            String token = credentialModel.getSecurityToken();
            return new DefaultCredentials(ak, sk, token);
        });
    }
}
