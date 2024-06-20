package org.example.oss_sdk;

import com.alibaba.fastjson.JSON;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.Bucket;

import java.util.List;

/**
 * 通过Credentials工具初始化，使用默认凭据链
 */
public class CredentialsDefaultSample {
    public static void main(String[] args) throws Exception {
        // 初始化凭据客户端
        Client credentialClient = new Client();

        // Bucket所在地域对应的Endpoint。以华东1（杭州）为例。
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // 建议使用更安全的V4签名算法，则初始化时需要加入endpoint对应的region信息，同时声明SignVersion.V4
        // OSS Java SDK 3.17.4及以上版本支持V4签名。
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);

        // 用凭据客户端初始化OSS客户端
        OSSClient ossClient = createOssClientByCredentials(endpoint, configuration, credentialClient);

        // 调用OpenAPI
        List<Bucket> bucketList = ossClient.listBuckets();
        System.out.println(JSON.toJSONString(bucketList));

        // 关闭OSSClient
        ossClient.shutdown();
    }

    public static OSSClient createOssClientByCredentials(String endpoint, ClientConfiguration configuration, Client credentialClient) {
        return new OSSClient(endpoint, new CredentialsProvider() {
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
        }, configuration);
    }
}
