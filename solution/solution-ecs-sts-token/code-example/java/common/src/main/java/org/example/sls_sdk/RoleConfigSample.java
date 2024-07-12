package org.example.sls_sdk;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.response.ListProjectResponse;

/**
 * 通过实例角色初始化
 */
public class RoleConfigSample {
    public static void main(String[] args) throws LogException {

        // 日志服务的服务接入点。以杭州为例
        String endpoint = "cn-hangzhou.log.aliyuncs.com";

        // ECS实例角色名称
        String ecsRoleName = "my-ecs-role";

        // 初始化 SLS Client
        Client slsClient = new Client(endpoint, ecsRoleName);

        // 调用SLS API
        ListProjectResponse listProjectResponse = slsClient.ListProject();
        System.out.println(listProjectResponse);

        //关闭SLS客户端
        slsClient.shutdown();
    }
}
