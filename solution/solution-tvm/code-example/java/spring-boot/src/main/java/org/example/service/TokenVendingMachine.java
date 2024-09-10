package org.example.service;

import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import org.example.service.policy.PolicyGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenVendingMachine {

    @Value("${role.arn}")
    String roleArn;

    @Value("${oss.bucket}")
    String ossBucket;

    @Value("${sls.project}")
    String slsProject;

    @Autowired
    com.aliyun.sts20150401.Client stsClient;

    public AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials vendToken(String identity) {
        // 根据请求中的身份信息，生成会话权限策略，进一步缩小权限，进行精细化管控
        String sessionPolicy = PolicyGenerator.generator()
            // 通过请求中的身份信息，限制允许操作的OSS文件夹，只允许操作同名的文件夹下面的文件
            .oss(ossBucket, identity)
            // 通过请求中的身份信息，限制允许操作的SLS Logstore，只允许操作同名的Logstore
            .sls(slsProject, identity)
            .generatePolicy();

        // 获取STS Token
        StsTokenVendor stsTokenVendor = StsTokenVendor.builder()
            .stsClient(stsClient)
            // 要扮演的RAM角色ARN，acs:ram::${账号 ID}:role/${角色名称}
            .roleArn(roleArn)
            // 角色会话名称
            .roleSessionName("TVM@" + identity)
            // 会话权限策略
            .sessionPolicy(sessionPolicy)
            // STS Token有效期，单位：秒
            .durationSeconds(3600L)
            .build();

        return stsTokenVendor.vendToken();
    }
}
