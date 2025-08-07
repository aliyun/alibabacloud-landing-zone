package org.example.sls_sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.alibaba.fastjson2.JSON;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.Project;
import com.aliyun.openservices.log.common.auth.Credentials;
import com.aliyun.openservices.log.common.auth.CredentialsProvider;
import com.aliyun.openservices.log.common.auth.DefaultCredentials;
import com.aliyun.openservices.log.common.auth.StaticCredentialsProvider;
import com.aliyun.openservices.log.exception.LogException;

public class App implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {

        // 从系统预留环境变量获取凭证信息，构造SLS的Credentials
        Credentials slsCreds = new DefaultCredentials(
            System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"),
            System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"),
            System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN")
        );

        CredentialsProvider credentialsProvider = new StaticCredentialsProvider(slsCreds);

        // SLS endpoint，以杭州为例
        String endpoint = "https://cn-hangzhou.log.aliyuncs.com";

        Client slsClient = new Client(endpoint, credentialsProvider);

        // 调用OpenAPI
        try {
            List<Project> projects = slsClient.ListProject().getProjects();
            outputStream.write(JSON.toJSONString(projects).getBytes());
        } catch (LogException e) {
            throw new RuntimeException(e);
        }
    }
}