package org.example.service;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.credentials.utils.ParameterHelper;
import com.aliyun.sts20150401.models.AssumeRoleRequest;
import com.aliyun.sts20150401.models.AssumeRoleResponse;
import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class AssumeRoleService {

    @Autowired
    com.aliyun.sts20150401.Client stsClient;

    /**
     * 通过角色扮演跨账号获取STS Token
     * 注意：缓存时间一定要小于STS Token有效期，避免缓存时间过长而STS Token过期导致程序错误
     */
    @Cacheable(value = "credential", key = "#roleArn")
    public CredentialModel createAssumeRoleCredential(String roleArn) throws Exception {
        RuntimeOptions runtimeOptions = new RuntimeOptions()
            // 开启自动重试机制，只会对超时等网络异常进行重试
            .setAutoretry(true)
            // 设置自动重试次数，默认3次
            .setMaxAttempts(3);
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
            // 要扮演的RAM角色ARN
            .setRoleArn(roleArn)
            // 角色会话名称
            .setRoleSessionName("WellArchitectedSolutionDemo")
            // 设置会话权限策略，进一步限制STS Token 的权限，如果指定该权限策略，则 STS Token 最终的权限策略取 RAM 角色权限策略与该权限策略的交集
            // 非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
            .setPolicy("{\"Statement\": [{\"Action\": [\"*\"],\"Effect\": \"Allow\",\"Resource\": [\"*\"]}],"
                + "\"Version\":\"1\"}")
            // STS Token 有效期，单位：秒
            // 以一小时为例
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
