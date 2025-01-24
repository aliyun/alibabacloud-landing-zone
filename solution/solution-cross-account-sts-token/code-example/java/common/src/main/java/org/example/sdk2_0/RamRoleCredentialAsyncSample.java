package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.auth.credentials.provider.EcsRamRoleCredentialProvider;
import com.aliyun.auth.credentials.provider.EnvironmentVariableCredentialProvider;
import com.aliyun.auth.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.auth.credentials.provider.RamRoleArnCredentialProvider;
import com.aliyun.sdk.service.sts20150401.AsyncClient;
import com.aliyun.sdk.service.sts20150401.models.GetCallerIdentityRequest;
import com.aliyun.sdk.service.sts20150401.models.GetCallerIdentityResponse;
import darabonba.core.client.ClientOverrideConfiguration;

import java.util.concurrent.CompletableFuture;

/**
 * JAVA SDK异步模式
 */
public class RamRoleCredentialAsyncSample {

    public static void main(String[] args) throws Exception {
        // 初始化凭据Provider，代表应用程序当前的角色身份，保证您的应用程序本身是无AK的
        // 这里您需要根据具体场景，通过对应的Provider类进行初始化。常见的有以下几种：
        // 1. 使用ECS实例RAM角色
        EcsRamRoleCredentialProvider originalProvider = EcsRamRoleCredentialProvider.builder()
            // 请替换为绑定到ECS实例上的RAM角色名称
            .roleName("<EcsInsatnceRoleName>")
            .build();
        // 2. 使用环境变量
        //EnvironmentVariableCredentialProvider originalProvider = new EnvironmentVariableCredentialProvider();
        // 3. 使用OIDC RAM角色
        //OIDCRoleArnCredentialProvider originalProvider = OIDCRoleArnCredentialProvider.builder()
        //    .roleArn(System.getenv("ALIBABA_CLOUD_ROLE_ARN"))
        //    .oidcProviderArn(System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN"))
        //    .oidcTokenFilePath(System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE"))
        //    // 角色会话名称，如果配置了ALIBABA_CLOUD_ROLE_SESSION_NAME这个环境变量，则无需设置
        //    .roleSessionName("<RoleSessionName>")
        //    // 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
        //    .policy("<Policy>")
        //    .roleSessionName("3600")
        //    .build();

        RamRoleArnCredentialProvider provider = RamRoleArnCredentialProvider.builder()
            .credentialsProvider(originalProvider)
            .durationSeconds(3600)
            // 请替换为您实际要扮演的RAM角色ARN
            // 格式为 acs:ram::${账号 ID}:role/${角色名称}
            .roleArn("<RoleArn>")
            .build();

        AsyncClient client = AsyncClient.builder()
            // 地域，以华东1（杭州）为例
            .region("cn-hangzhou")
            //// Use the configured HttpClient, otherwise use the default HttpClient (Apache HttpClient)
            //.httpClient(httpClient)
            .credentialsProvider(provider)
            //// Service-level configuration
            //.serviceConfiguration(Configuration.create())
            // Client-level configuration rewrite, can set Endpoint, Http request parameters, etc.
            .overrideConfiguration(
                ClientOverrideConfiguration.create()
                    // Endpoint 请参考 https://api.aliyun.com/product/Sts
                    .setEndpointOverride("sts.cn-hangzhou.aliyuncs.com")
                //.setConnectTimeout(Duration.ofSeconds(30))
            )
            .build();

        CompletableFuture<GetCallerIdentityResponse> response = client.getCallerIdentity(
            GetCallerIdentityRequest.create());
        // Synchronously get the return value of the API request
        GetCallerIdentityResponse resp = response.get();

        System.out.println(JSON.toJSONString(resp));

        // Finally, close the client
        client.close();
    }
}
