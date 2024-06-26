package org.hz.minigroup.network.controller;


import org.hz.minigroup.common.CommonConstants;
import org.hz.minigroup.common.model.WebResult;
import org.hz.minigroup.common.utils.BaseController;
import org.hz.minigroup.network.model.VpcRequest;
import org.hz.minigroup.network.service.NetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


@Controller
@RequestMapping("/network")
public class NetworkController extends BaseController implements InitializingBean {

    private Logger logger = LoggerFactory.getLogger(NetworkController.class);

    @Autowired
    private NetworkService networkService;

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @RequestMapping("/test.do")
    public void test(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebResult result = new WebResult();
        try {
            VpcRequest vpcRequest = new VpcRequest();
            String cidrBlock = request.getParameter("cidrBlock");
            String vpcName = request.getParameter("vpcName");
            vpcRequest.setCidrBlock(cidrBlock);
            vpcRequest.setEnableIpv6(false);
            vpcRequest.setIpv6CidrBlock("");

            vpcRequest.setVpcName(vpcName);
            networkService.createVpc(vpcRequest);
            result.setData("success");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setSuccessResponse(false);
            result.setErrorMsg(e.getMessage());
        }
        outputToJSON(response, result);
    }
}
