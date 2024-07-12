package org.example.sdk1_0;

import com.alibaba.fastjson2.JSON;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityRequest;
import com.aliyuncs.sts.model.v20150401.GetCallerIdentityResponse;

/**
 * 通过固定AK初始化
 * 作为反面使用示例，不建议使用，推荐通过Credentials SDK使用临时凭证STS Token
 */
public class LongTermAkSample {
    public static void main(String[] args) throws ClientException {
        // 使用固定AK创建DefaultAcsClient实例并初始化
        DefaultProfile profile = DefaultProfile.getProfile(
            // 地域ID
            "cn-hangzhou",
            // 您的 AccessKey ID，这里从环境变量获取
            System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"),
            // 您的 AccessKey Secret，从环境变量获取
            System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET")
        );
        IAcsClient client = new DefaultAcsClient(profile);

        // 调用API，以GetCallerIdentity获取当前调用者身份信息为例
        GetCallerIdentityRequest getCallerIdentityRequest = new GetCallerIdentityRequest();
        GetCallerIdentityResponse getCallerIdentityResponse = client.getAcsResponse(getCallerIdentityRequest);
        System.out.println(JSON.toJSONString(getCallerIdentityResponse));
    }
}
