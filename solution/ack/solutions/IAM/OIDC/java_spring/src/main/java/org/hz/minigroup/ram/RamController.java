package org.hz.minigroup.ram;

import com.aliyuncs.IAcsClient;
import org.apache.commons.lang3.StringUtils;
import org.hz.minigroup.common.model.StsToken;
import org.hz.minigroup.common.model.WebResult;
import org.hz.minigroup.common.utils.BaseController;
import org.hz.minigroup.network.controller.NetworkController;
import org.hz.minigroup.ram.service.RamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.hz.minigroup.common.OidcStsUtils;

@Component
@RequestMapping("/ram")
public class RamController extends BaseController {
    private Logger logger = LoggerFactory.getLogger(NetworkController.class);

    @Autowired
    private RamService ramService;

    @Autowired
    private OidcStsUtils oidcStsUtils;


    @RequestMapping("/test.do")
    public void test(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebResult result = new WebResult();
        try {
            StsToken stsToken = oidcStsUtils.getStsObject();
            // 注意！当OIDC的Token失效的时候，有可能会被命中，需要让客户端发起重试
            if (StringUtils.isBlank(stsToken.getStsToken())) {
                logger.info("OIDC Token失效，请客户端再次发起重试");
                result.setCode("501");
                result.setErrorMsg("请发起重试！");
                result.setSuccessResponse(false);
            } else {
                IAcsClient client = ramService.createClient(stsToken);
                ramService.listRamUserList(client);
                result.setData("success");
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setSuccessResponse(false);
            result.setErrorMsg(e.getMessage());
        }
        outputToJSON(response, result);
    }

}
