package org.example.sls_sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.ClientBuilder;
import com.aliyun.openservices.log.common.Project;
import com.aliyun.openservices.log.common.auth.Credentials;
import com.aliyun.openservices.log.common.auth.CredentialsProvider;
import com.aliyun.openservices.log.common.auth.DefaultCredentials;
import com.aliyun.openservices.log.common.auth.StaticCredentialsProvider;
import com.aliyun.openservices.log.exception.LogException;

public class App implements StreamRequestHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        // 从上下文获取凭证信息
        com.aliyun.fc.runtime.Credentials creds = context.getExecutionCredentials();

        // 转化为SLS的Credentials
        Credentials slsCreds = new DefaultCredentials(creds.getAccessKeyId(), creds.getAccessKeySecret(), creds.getSecurityToken());

        CredentialsProvider credentialsProvider = new StaticCredentialsProvider(slsCreds);

        // SLS endpoint，以杭州为例
        String endpoint = "https://cn-hangzhou.log.aliyuncs.com";

        Client slsClient = new ClientBuilder(endpoint, credentialsProvider).build();

        // 调用OpenAPI
        try {
            List<Project> projects = slsClient.ListProject().getProjects();
            outputStream.write(JSON.toJSONString(projects).getBytes());
        } catch (LogException e) {
            throw new RuntimeException(e);
        }
    }
}