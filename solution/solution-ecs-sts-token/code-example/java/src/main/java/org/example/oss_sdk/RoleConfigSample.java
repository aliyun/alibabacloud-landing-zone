package org.example.oss_sdk;

import com.alibaba.fastjson2.JSON;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.Bucket;

import java.util.List;

/**
 * 通过实例角色初始化
 */
public class RoleConfigSample {
    public static void main(String[] args) {
        InstanceProfileCredentialsProvider credentialsProvider = new InstanceProfileCredentialsProvider(
            "my-ecs-role" // ecs实例角色名
        );

        // Bucket所在地域对应的Endpoint。以华东1（杭州）为例。
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // Endpoint对应的Region信息，例如cn-hangzhou。
        String region = "cn-hangzhou";

        // 建议使用更安全的V4签名算法，则初始化时需要加入endpoint对应的region信息，同时声明SignVersion.V4
        // OSS Java SDK 3.17.4及以上版本支持V4签名。
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);

        // 创建OSSClient实例。
        OSS ossClient = OSSClientBuilder.create()
            .endpoint(endpoint)
            .credentialsProvider(credentialsProvider)
            .clientConfiguration(clientBuilderConfiguration)
            .region(region)
            .build();

        // 调用OSS API
        List<Bucket> buckets = ossClient.listBuckets();
        System.out.println(JSON.toJSONString(buckets));

        // 关闭OSSClient。
        ossClient.shutdown();
    }
}
