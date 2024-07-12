package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;

/**
 * 通过Credentials工具初始化，使用OIDCRoleArn
 */
public class CredentialsOIDCRoleArnSample {
    public static void main(String[] args) throws Exception {
        // 初始化凭据客户端
        Config credentialConfig = new Config();
        credentialConfig.setType("oidc_role_arn");
        credentialConfig.setRoleArn(System.getenv("ALIBABA_CLOUD_ROLE_ARN"));
        credentialConfig.setOidcProviderArn(System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN"));
        credentialConfig.setOidcTokenFilePath(System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE"));
        // 角色会话名称，如果配置了ALIBABA_CLOUD_ROLE_SESSION_NAME这个环境变量，则无需设置
        credentialConfig.setRoleSessionName("<RoleSessionName>");
        // 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
        credentialConfig.setPolicy("<Policy>");
        // Not required, the external ID of the RAM role
        // This parameter is provided by an external party and is used to prevent the confused deputy problem.
        credentialConfig.setExternalId("<ExternalId>");
        // 设置session过期时间
        credentialConfig.setRoleSessionExpiration(3600);
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
