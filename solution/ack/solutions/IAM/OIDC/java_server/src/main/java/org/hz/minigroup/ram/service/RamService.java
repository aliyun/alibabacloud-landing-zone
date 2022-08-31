package org.hz.minigroup.ram.service;


import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.exceptions.ServerException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.ram.model.v20150501.ListUsersRequest;
import com.aliyuncs.ram.model.v20150501.ListUsersResponse;
import org.hz.minigroup.common.CommonConstants;
import org.hz.minigroup.common.model.StsToken;
import org.hz.minigroup.network.service.NetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
@Component
public class RamService {
    private Logger logger = LoggerFactory.getLogger(NetworkService.class);

    public IAcsClient createClient(StsToken stsToken) {


        DefaultProfile profile = DefaultProfile.getProfile(CommonConstants.STS_REGION, stsToken.getStsAk(), stsToken.getStsSk(),stsToken.getStsToken());
        /** use STS Token
         DefaultProfile profile = DefaultProfile.getProfile(
         "<your-region-id>",           // The region ID
         "<your-access-key-id>",       // The AccessKey ID of the RAM account
         "<your-access-key-secret>",   // The AccessKey Secret of the RAM account
         "<your-sts-token>");          // STS Token
         **/

        IAcsClient client = new DefaultAcsClient(profile);
        return client;

    }

    public void listRamUserList(IAcsClient client) throws Exception {
        ListUsersRequest request = new ListUsersRequest();

        try {
            ListUsersResponse response = client.getAcsResponse(request);
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (ClientException e) {
            System.out.println("ErrCode:" + e.getErrCode());
            System.out.println("ErrMsg:" + e.getErrMsg());
            System.out.println("RequestId:" + e.getRequestId());
        }

    }
}
