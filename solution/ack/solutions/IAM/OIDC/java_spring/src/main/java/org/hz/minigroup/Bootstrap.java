package org.hz.minigroup;


import org.hz.minigroup.common.OidcStsUtils;
import org.hz.minigroup.common.model.StsToken;
import org.hz.minigroup.common.model.WebResult;
import org.hz.minigroup.common.utils.BaseController;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
@RequestMapping("/test")
public class Bootstrap extends BaseController implements InitializingBean {

    @Autowired
    private OidcStsUtils oidcStsUtils;


    @RequestMapping("/t1.do")
    public void t1(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebResult result = new WebResult();
        try {
            StsToken  stsToken = oidcStsUtils.getStsObject();
            System.out.printf(stsToken.getStsAk());
            result.setData("success");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setSuccessResponse(false);
            result.setErrorMsg(e.getMessage());
        }
        outputToJSON(response, result);
    }

    @RequestMapping("/test.do")
    public void test(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebResult result = new WebResult();
        try {
            result.setData("success");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setSuccessResponse(false);
            result.setErrorMsg(e.getMessage());
        }
        outputToJSON(response, result);
    }

    @Override
    public void afterPropertiesSet() throws Exception {

    }
}