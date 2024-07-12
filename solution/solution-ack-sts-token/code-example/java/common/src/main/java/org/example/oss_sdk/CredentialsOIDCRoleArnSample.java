package org.example.oss_sdk;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.Bucket;

import java.util.List;

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
        Client credentialClient = new Client(credentialConfig);

        // Bucket所在地域对应的Endpoint。以华东1（杭州）为例。
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // Endpoint对应的Region信息，例如cn-hangzhou。
        String region = "cn-hangzhou";
        // 建议使用更安全的V4签名算法，则初始化时需要加入endpoint对应的region信息，同时声明SignVersion.V4
        // OSS Java SDK 3.17.4及以上版本支持V4签名。
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);

        // 用凭据客户端初始化OSS客户端
        OSS ossClient = OSSClientBuilder.create()
            .endpoint(endpoint)
            .credentialsProvider(new CredentialsProvider() {
                @Override
                public void setCredentials(Credentials credentials) {
                }

                @Override
                public Credentials getCredentials() {
                    // 保证线程安全，从 CredentialModel 中获取 ak/sk/security token
                    CredentialModel credentialModel = credentialClient.getCredential();
                    String ak = credentialModel.getAccessKeyId();
                    String sk = credentialModel.getAccessKeySecret();
                    String token = credentialModel.getSecurityToken();
                    return new DefaultCredentials(ak, sk, token);
                }
            })
            .clientConfiguration(configuration)
            .region(region)
            .build();

        // 调用OpenAPI
        List<Bucket> bucketList = ossClient.listBuckets();
        System.out.println(JSON.toJSONString(bucketList));

        // 关闭OSSClient
        ossClient.shutdown();
    }
}
