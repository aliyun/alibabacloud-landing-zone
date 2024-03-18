package org.hz.minigroup.network.service;

import com.aliyun.vpc20160428.models.*;
import org.hz.minigroup.common.model.StsToken;
import org.hz.minigroup.network.model.VpcRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.aliyun.vpc20160428.Client;


import java.util.Random;

@Component
public class NetworkService {
    private Logger logger = LoggerFactory.getLogger(NetworkService.class);
    private com.aliyun.vpc20160428.Client vpcClient;

    public Client getVpcClient(StsToken stsToken) {
        return null;
    }


    public void createVpc(VpcRequest vpcRequest) {
        Random random = new Random();
        CreateVpcRequest createVpcRequest = new CreateVpcRequest()
                .setVpcName(vpcRequest.getVpcName())
                .setCidrBlock(vpcRequest.getCidrBlock())
                .setClientToken(String.valueOf(random.nextLong()))
                .setEnableIpv6(vpcRequest.isEnableIpv6())
                .setIpv6CidrBlock(vpcRequest.getIpv6CidrBlock())
                .setRegionId(vpcRequest.getRegionId())
                .setUserCidr(vpcRequest.getUserCidr());

        try {
            CreateVpcResponse createVpcResponse = vpcClient.createVpc(createVpcRequest);
            logger.info("新创建的VPC ID：{}", createVpcResponse.getBody().getVpcId());
        } catch (Exception e) {
            logger.error("call add createVpc", e);
        }
    }


}
