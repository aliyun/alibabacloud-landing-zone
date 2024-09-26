package org.example.config;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class OssConfig {

    @Value("${region.id}")
    String regionId;

    @Value("${oss.bucket}")
    String bucket;

    @Value("${service.address}")
    String serviceAddress;

    String dir = "example/";

    String endpoint;

    String host;

    // 上传文件时，post policy 的过期时间，单位为毫秒
    long uploadExpireTime = 3600;

    // 下载文件时，签名 url 的过期时间，单位为毫秒
    long downloadExpireTime = 300;

    String postCallbackUrl;

    @Autowired
    com.aliyun.credentials.Client credentialClient;

    @PostConstruct
    public void init() {
        this.endpoint = "oss-" + regionId + ".aliyuncs.com";
        this.host = "https://" + bucket + "." + endpoint;
        this.postCallbackUrl = serviceAddress + "/upload/callback";
    }

    // 自定义 OSS Credentails Provider，通过该 Provder 初始化下方 OSS SDK Client
    // 该 Provdier 会从 Credentail SDK Client 中获取最新的 STS Token
    // Credentail SDK Client 会自动更新 STS Token，您无需关心 STS Token 到期如何更换的问题
    @Bean
    CredentialsProvider getOssCredentialsProvider() {
        return new CredentialsProvider() {
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
        };
    }

    // 初始化阿里云 OSS SDK 客户端
    // OSS SDK Client 建议是单例模式，不要每次请求都重新 New 一个，避免出现内存泄露的问题
    @Bean
    OSS getOssClient(CredentialsProvider credentialsProvider) {
        // 建议使用更安全的V4签名算法，则初始化时需要加入endpoint对应的region信息，同时声明SignVersion.V4
        // OSS Java SDK 3.17.4及以上版本支持V4签名。
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);

        return OSSClientBuilder.create()
            .endpoint(endpoint)
            .credentialsProvider(credentialsProvider)
            .clientConfiguration(configuration)
            .region(regionId)
            .build();
    }

    public String getBucket() {
        return bucket;
    }

    public String getHost() {
        return host;
    }

    public long getUploadExpireTime() {
        return uploadExpireTime;
    }

    public String getPostCallbackUrl() {
        return postCallbackUrl;
    }

    public String getDir() {
        return dir;
    }

    public long getDownloadExpireTime() {
        return downloadExpireTime;
    }
}
