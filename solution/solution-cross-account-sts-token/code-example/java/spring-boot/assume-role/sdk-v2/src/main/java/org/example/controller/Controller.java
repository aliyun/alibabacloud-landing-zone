package org.example.controller;

import com.alibaba.fastjson2.JSON;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaUnretryableException;
import com.aliyun.teautil.models.RuntimeOptions;
import org.example.service.AssumeRoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @Value("${region.id}")
    String regionId;

    @Value("${role.arn}")
    String roleArn;

    @Autowired
    AssumeRoleService assumeRoleService;

    /**
     * 调用API，跨账号进行资源操作
     * 以调用GetCallerIdentity获取当前调用者身份信息为例
     */
    @GetMapping("/getCallerIdentity")
    public String getCallerIdentity() {
        try {
            // 跨账号获取临时凭证
            CredentialModel credentialModel = assumeRoleService.createAssumeRoleCredential(roleArn);

            // 调用API，跨账号进行资源操作
            // 请根据您的业务场景，修改为对应的资源操作API
            com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
                .setAccessKeyId(credentialModel.getAccessKeyId())
                .setAccessKeySecret(credentialModel.getAccessKeySecret())
                .setSecurityToken(credentialModel.getSecurityToken())
                .setRegionId(regionId);
            com.aliyun.sts20150401.Client stsClient = new com.aliyun.sts20150401.Client(config);
            RuntimeOptions runtimeOptions = new RuntimeOptions()
                // 开启自动重试机制，只会对超时等网络异常进行重试
                .setAutoretry(true)
                // 设置自动重试次数，默认3次
                .setMaxAttempts(3);

            GetCallerIdentityResponse getCallerIdentityResponse = stsClient.getCallerIdentityWithOptions(runtimeOptions);
            return JSON.toJSONString(getCallerIdentityResponse);
        } catch(TeaUnretryableException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            // 打印错误信息
            System.out.println(e.getMessage());
            // 打印请求记录
            System.out.println(e.getLastRequest());
        } catch (TeaException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            // 打印错误码
            System.out.println(e.getCode());
            // 打印错误信息，错误信息中包含 RequestId
            System.out.println(e.getMessage());
            // 打印服务端返回的具体错误内容
            System.out.println(e.getData());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
