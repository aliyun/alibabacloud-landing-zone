package com.aliyun.autowonder.controller;

import com.aliyun.autowonder.util.ApplicationUtil;
import org.apache.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class HealthCheckController {

    @RequestMapping("/checkpreload.htm")
    public String getStatus(HttpServletResponse response) {
        response.setStatus(HttpStatus.SC_OK);
        return "success";
    }

    @RequestMapping({"/status.taobao"})
    public ResponseEntity<String> statusTaobao() throws IOException {
        if(ApplicationUtil.isShuttingDown()) {
            return ResponseEntity.status(HttpStatus.SC_NOT_FOUND).body("HealthCheckController can not found META-INF/resources/status.taobao, please check app status.; server maybe in rebooting...");
        }
        else {
            return ResponseEntity.status(HttpStatus.SC_OK).body("success");
        }
    }
}
