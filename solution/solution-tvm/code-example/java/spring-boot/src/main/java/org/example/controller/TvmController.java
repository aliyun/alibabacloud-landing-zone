package org.example.controller;

import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import org.example.service.TokenVendingMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TvmController {

    @Autowired
    TokenVendingMachine tokenVendingMachine;

    // 测试示例使用，配置了 CORS 跨域
    @CrossOrigin(origins = "*", allowedHeaders = "*", maxAge = 86400)
    @GetMapping("/vendToken")
    public AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials vendToken(@RequestHeader("Custom-Identity") String identity) {
        // 根据自身应用需要，进行请求的合法性校验
        // 本示例只是演示使用，不会对请求身份等进行合法性校验
        return tokenVendingMachine.vendToken(identity);
    }
}
