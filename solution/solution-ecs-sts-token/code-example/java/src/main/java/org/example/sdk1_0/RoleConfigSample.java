package org.example.sdk1_0;

import com.alibaba.fastjson2.JSON;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.auth.InstanceProfileCredentialsProvider;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;

/**
 * 通过实例角色初始化
 */
public class RoleConfigSample {
    public static void main(String[] args) throws ClientException {
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou");
        InstanceProfileCredentialsProvider provider = new InstanceProfileCredentialsProvider(
            "my-ecs-role" // ecs实例角色名
        );
        DefaultAcsClient client = new DefaultAcsClient(profile, provider);

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        GetCallerIdentityResponse getCallerIdentityResponse = client.getAcsResponse(getCallerIdentityRequest);
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
