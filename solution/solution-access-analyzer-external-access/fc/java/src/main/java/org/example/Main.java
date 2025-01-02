package org.example;

import com.alibaba.fastjson2.JSON;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.FunctionComputeLogger;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.PutBucketPublicAccessBlockRequest;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import lombok.Data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class Main implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        FunctionComputeLogger logger = context.getLogger();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int length; (length = inputStream.read(buffer)) != -1; ) {
            result.write(buffer, 0, length);
        }
        String event = result.toString(StandardCharsets.UTF_8.name());
        logger.error(event);
        MessageData messageData = JSON.parseObject(event, MessageData.class);

        // get credentials from context
        com.aliyun.fc.runtime.Credentials creds = context.getExecutionCredentials();

        // cross account
        DefaultProfile profile = DefaultProfile.getProfile(System.getenv("REGION"), creds.getAccessKeyId(),
            creds.getAccessKeySecret(), creds.getSecurityToken());
        IAcsClient client = new DefaultAcsClient(profile);
        AssumeRoleRequest request = new AssumeRoleRequest();
        request.setRoleArn(String.format("acs:ram::%s:role/%s", messageData.getResourceOwnerAccountId(), System.getenv("ASSUME_ROLE_NAME")));
        request.setRoleSessionName("AccessAnalyzer");

        try {
            AssumeRoleResponse response = client.getAcsResponse(request);

            ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
            configuration.setSignatureVersion(SignVersion.V4);

            String[] resourceArns = messageData.getResourceArn().split(":");
            String ossRegion = resourceArns[2];
            String ossBucket = resourceArns[4];

            OSS ossClient = OSSClientBuilder.create()
                .endpoint(String.format("https://%s.aliyuncs.com", ossRegion))
                .credentialsProvider(new DefaultCredentialProvider(response.getCredentials().getAccessKeyId(), response.getCredentials().getAccessKeySecret(), response.getCredentials().getSecurityToken()))
                .clientConfiguration(configuration)
                .region(ossRegion.replace("oss-", ""))
                .build();
            ossClient.putBucketPublicAccessBlock(new PutBucketPublicAccessBlockRequest(ossBucket, true));
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    @Data
    class MessageData {

        String resourceOwnerAccountId;

        String resourceType;

        String isPublic;

        String status;

        Boolean isDeleted;

        String resourceArn;
    }
}