package org.example.sdk1_0;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.auth.STSAssumeRoleSessionCredentialsProvider;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.exceptions.ServerException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;

public class AssumeRoleSample {
    public static void main(String[] args) {
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

        // 以华东1（杭州）为例
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou");
        // 用凭据客户端初始化角色扮演的CredentialsProvider：STSAssumeRoleSessionCredentialsProvider，实现跨账号角色扮演
        // 该CredentialsProvider支持自动刷新STS Token
        STSAssumeRoleSessionCredentialsProvider provider = new STSAssumeRoleSessionCredentialsProvider(
            () -> {
                // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
                CredentialModel credentialModel = credentialClient.getCredential();
                String ak = credentialModel.getAccessKeyId();
                String sk = credentialModel.getAccessKeySecret();
                String token = credentialModel.getSecurityToken();
                return new BasicSessionCredentials(ak, sk, token);
            },
            // 请替换为您实际要扮演的RAM角色ARN
            // 格式为 acs:ram::${账号 ID}:role/${角色名称}
            "<role-arn>",
            profile
        )
        // 角色会话名称
        .withRoleSessionName("WellArchitectedSolutionDemo")
        // STS Token 有效期，单位：秒
        .withRoleSessionDurationSeconds(3600L);
        
        // 初始化SDK 1.0客户端
        IAcsClient iAcsClient =  new DefaultAcsClient(profile, provider);

        // 调用API，跨账号进行资源操作
        // 以调用GetCallerIdentity获取当前调用者身份信息为例
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        try {
            GetCallerIdentityResponse getCallerIdentityResponse = iAcsClient.getAcsResponse(getCallerIdentityRequest);
            System.out.println(JSON.toJSONString(getCallerIdentityResponse));
        } catch (ServerException e) {
            // 示例仅做打印展示。请重视异常处理，在工程项目中切勿直接忽略异常。
            // 打印整体的错误输出
            e.printStackTrace();
            // 打印错误码
            System.out.println(e.getErrCode());
            // 打印 RequestId
            System.out.println(e.getRequestId());
            // 打印错误信息
            System.out.println(e.getErrMsg());
        } catch (ClientException e) {
            // 示例仅做打印展示。请重视异常处理，在工程项目中切勿直接忽略异常。
            // 打印整体的错误输出
            e.printStackTrace();
            // 打印错误码
            System.out.println(e.getMessage());
        }
    }
}
