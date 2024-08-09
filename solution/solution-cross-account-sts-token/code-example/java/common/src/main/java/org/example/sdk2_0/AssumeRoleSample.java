package org.example.sdk2_0;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.credentials.utils.ParameterHelper;
import com.aliyun.sts20150401.models.AssumeRoleRequest;
import com.aliyun.sts20150401.models.AssumeRoleResponse;
import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import com.alibaba.fastjson2.JSON;

public class AssumeRoleSample {

    public static void main(String[] args) throws Exception {
        // 初始化凭据客户端，使用Credentials工具，保证您的应用程序本身是无AK的
        // 借助Credentials工具的默认凭据链，您可以用同一套代码，通过程序之外的配置来控制不同环境下的凭据获取方式
        // 当您在初始化凭据客户端不传入任何参数时，Credentials工具将会尝试按照如下顺序查找相关凭据信息（优先级由高到低）：
        // 1. 使用系统属性
        // 2. 使用环境变量
        // 3. 使用OIDC RAM角色
        // 4. 使用配置文件
        // 5. 使用ECS实例RAM角色（需要通过环境变量 ALIBABA_CLOUD_ECS_METADATA 指定 ECS 实例角色名称；通过环境变量 ALIBABA_CLOUD_ECS_IMDSV2_ENABLE=true 开启在加固模式下获取STS Token）
        // 详情请参考：https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials#3ca299f04bw3c
        // 要使用默认凭据链，初始化 Client 时，必须使用空的构造函数，不能配置 Config 入参
        // 除了使用默认凭据链，您也可以在代码中显式配置，来初始化凭据客户端
        // 详情请参考：https://help.aliyun.com/zh/sdk/developer-reference/v2-manage-access-credentials#a9e9aa404bzfy
        com.aliyun.credentials.Client credentialClient = new com.aliyun.credentials.Client();

        // 跨账号角色扮演获取STS Token
        // 如果您缓存了该STS Token，需要特别注意STS Toke的到期时间，避免缓存时间过长而STS Token过期导致程序错误
        CredentialModel assumeRoleCredentialModel = createAssumeRoleCredential(credentialClient);

        // 调用API，跨账号进行资源操作
        // 以调用GetCallerIdentity获取当前调用者身份信息为例
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setAccessKeyId(assumeRoleCredentialModel.getAccessKeyId())
            .setAccessKeySecret(assumeRoleCredentialModel.getAccessKeySecret())
            .setSecurityToken(assumeRoleCredentialModel.getSecurityToken())
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

    public static CredentialModel createAssumeRoleCredential(com.aliyun.credentials.Client credentialClient) throws Exception {
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
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
            // 请替换为您实际要扮演的RAM角色ARN
            // 格式为 acs:ram::${账号 ID}:role/${角色名称}
            .setRoleArn("<role-arn>")
            // 角色会话名称
            .setRoleSessionName("WellArchitectedSolutionDemo")
            // 设置会话权限策略，进一步限制STS Token 的权限，如果指定该权限策略，则 STS Token 最终的权限策略取 RAM 角色权限策略与该权限策略的交集
            // 非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
            .setPolicy("{\"Statement\": [{\"Action\": [\"*\"],\"Effect\": \"Allow\",\"Resource\": [\"*\"]}],"
                + "\"Version\":\"1\"}")
            // STS Token 有效期，单位：秒
            .setDurationSeconds(3600L);
        AssumeRoleResponse assumeRoleResponse = stsClient.assumeRoleWithOptions(assumeRoleRequest, runtimeOptions);
        AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials credentials = assumeRoleResponse.getBody().getCredentials();

        // 返回角色扮演获取到的STS Token
        return CredentialModel.builder()
            .accessKeyId(credentials.getAccessKeyId())
            .accessKeySecret(credentials.getAccessKeySecret())
            .securityToken(credentials.getSecurityToken())
            .expiration(ParameterHelper.getUTCDate(credentials.getExpiration()).getTime())
            .build();
    }
}
