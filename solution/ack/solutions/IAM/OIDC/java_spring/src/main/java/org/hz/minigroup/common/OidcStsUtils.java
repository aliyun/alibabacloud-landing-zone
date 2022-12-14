package org.hz.minigroup.common;

import com.aliyun.tea.TeaException;
import com.aliyun.tea.TeaModel;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.exceptions.ServerException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleWithOIDCRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleWithOIDCResponse;
import org.hz.minigroup.common.model.StsToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

@Service
public class OidcStsUtils implements InitializingBean {
    private Logger logger = LoggerFactory.getLogger(OidcStsUtils.class);
    private IAcsClient client;

    public StsToken getStsObject() throws Exception {
        StsToken stsToken = new StsToken();
        AssumeRoleWithOIDCRequest request = new AssumeRoleWithOIDCRequest();
        request.setOIDCProviderArn(CommonConstants.OIDC_PRODIVER_ARN);
        request.setRoleArn(CommonConstants.ROLE_ARN);
        request.setRoleSessionName(CommonConstants.ROLE_SESSION_NAME);
        request.setOIDCToken(CommonConstants.OIDC_TOKEN);

        try {
            AssumeRoleWithOIDCResponse response = client.getAcsResponse(request);
            stsToken.setStsAk(response.getCredentials().getAccessKeyId());
            stsToken.setStsSk(response.getCredentials().getAccessKeySecret());
            stsToken.setStsToken(response.getCredentials().getSecurityToken());

        } catch (ServerException e) {
            logger.error(e.toString());
        } catch (ClientException e) {
            logger.error(e.getErrCode());
        }
        return stsToken;
    }

    @Override
    public void afterPropertiesSet() {
        DefaultProfile profile = DefaultProfile.getProfile(CommonConstants.STS_REGION, CommonConstants.STS_AK, CommonConstants.STS_SK);
        client = new DefaultAcsClient(profile);
        logger.info("######加载临时sts客户端####");
    }
}
