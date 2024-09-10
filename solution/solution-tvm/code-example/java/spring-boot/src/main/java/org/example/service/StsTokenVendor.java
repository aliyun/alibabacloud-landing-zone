package org.example.service;

import com.aliyun.sts20150401.models.AssumeRoleRequest;
import com.aliyun.sts20150401.models.AssumeRoleResponse;
import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaUnretryableException;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.Builder;

@Builder
public class StsTokenVendor {

    private final com.aliyun.sts20150401.Client stsClient;

    /**
     * 要扮演的RAM角色ARN，acs:ram::${账号 ID}:role/${角色名称}
     */
    private final String roleArn;

    /**
     * 角色会话名称
     */
    private final String roleSessionName;

    /**
     *  会话权限策略，可以进一步缩小权限，进行精细化管控
     */
    private final String sessionPolicy;

    /**
     * STS Token有效期，单位：秒
     */
    private final Long durationSeconds;

    public AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials vendToken() {
        RuntimeOptions runtimeOptions = new RuntimeOptions()
            // 开启自动重试机制
            .setAutoretry(true)
            // 设置自动重试次数，默认3次
            .setMaxAttempts(3);
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
            .setRoleArn(roleArn)
            .setRoleSessionName(roleSessionName)
            .setPolicy(sessionPolicy)
            .setDurationSeconds(durationSeconds);
        try {
            AssumeRoleResponse assumeRoleResponse = stsClient.assumeRoleWithOptions(assumeRoleRequest, runtimeOptions);
            return assumeRoleResponse.getBody().getCredentials();
        } catch (TeaUnretryableException e) {
            // 该异常主要是因为网络问题造成，一般是网络问题达到最大重试次数后抛出，可以通过exception.getLastRequest来查询错误发生时的请求信息。
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            // 打印错误信息
            System.out.println(e.getMessage());
            // 打印请求记录
            System.out.println(e.getLastRequest());
            throw e;
        } catch (TeaException e) {
            // 在SDK的请求中主要以业务报错为主的异常
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            // 打印错误码
            System.out.println(e.getCode());
            // 打印错误信息，错误信息中包含 RequestId
            System.out.println(e.getMessage());
            // 打印服务端返回的具体错误内容
            System.out.println(e.getData());
            throw e;
        } catch (Exception e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
