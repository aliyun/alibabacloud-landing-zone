package org.example.sdk2_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.provider.EcsRamRoleCredentialProvider;
//import com.aliyun.credentials.provider.EnvironmentVariableCredentialsProvider;
//import com.aliyun.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.credentials.provider.RamRoleArnCredentialProvider;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;

public class RamRoleCredentialSample {

    public static void main(String[] args) throws Exception {
        // 初始化凭据Provider，代表应用程序当前的角色身份，保证您的应用程序本身是无AK的
        // 这里您需要根据具体场景，通过对应的Provider类进行初始化。常见的有以下几种：
        // 1. 使用ECS实例RAM角色
        EcsRamRoleCredentialProvider originalProvider = EcsRamRoleCredentialProvider.builder()
            // 请替换为绑定到ECS实例上的RAM角色名称
            .roleName("<EcsInsatnceRoleName>")
            .build();
        // 2. 使用环境变量
        //EnvironmentVariableCredentialsProvider originalProvider = new EnvironmentVariableCredentialsProvider();
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

        // 使用当前角色作为入参，初始化角色扮演的Provider，实现角色链式扮演，同时支持跨账号扮演角色
        RamRoleArnCredentialProvider provider = RamRoleArnCredentialProvider.builder()
            .credentialsProvider(originalProvider)
            .durationSeconds(3600)
            // 请替换为您实际要扮演的RAM角色ARN
            // 格式为 acs:ram::${账号 ID}:role/${角色名称}
            .roleArn("<RoleArn>")
            .build();

        // 通过Provider初始化凭据客户端，通过客户端获取角色扮演后最终的STS Token
        // 研发无需关心STS Token有效期和重新获取的逻辑，此方式支持自动刷新STS Token
        Client credentialClient = new Client(provider);

        // 调用API，跨账号进行资源操作
        // 以调用GetCallerIdentity获取当前调用者身份信息为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(credentialClient)
            // 地域，以华东1（杭州）为例
            .setRegionId("cn-hangzhou");
        com.aliyun.sts20150401.Client stsClient = new com.aliyun.sts20150401.Client(config);
        RuntimeOptions runtimeOptions = new RuntimeOptions()
            // 开启自动重试机制，只会对超时等网络异常进行重试
            .setAutoretry(true)
            // 设置自动重试次数，默认3次
            .setMaxAttempts(3);

        try {
            GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentityWithOptions(runtimeOptions);
            System.out.println(JSON.toJSONString(getCallerIdentityResponse));
        } catch (TeaException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            // 打印错误码
            System.out.println(e.getCode());
            // 打印错误信息，错误信息中包含 RequestId
            System.out.println(e.getMessage());
            // 打印服务端返回的具体错误内容
            System.out.println(e.getData());
        }
    }
}
