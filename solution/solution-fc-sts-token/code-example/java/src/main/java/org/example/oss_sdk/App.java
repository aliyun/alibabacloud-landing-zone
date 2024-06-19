package org.example.oss_sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.model.Bucket;

public class App implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        // 从上下文获取凭证信息
        com.aliyun.fc.runtime.Credentials creds = context.getExecutionCredentials();

        // 转化为OSS的Credentials
        Credentials ossCreds = new DefaultCredentials(creds.getAccessKeyId(), creds.getAccessKeySecret(), creds.getSecurityToken());

        CredentialsProvider credentialsProvider = new DefaultCredentialProvider(ossCreds);

        // OSS endpoint，以杭州为例
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";

        OSS ossClient = new OSSClientBuilder().build(endpoint, credentialsProvider);

        // 调用OpenAPI
        List<Bucket> buckets = ossClient.listBuckets();
        outputStream.write(JSON.toJSONString(buckets).getBytes());
    }
}