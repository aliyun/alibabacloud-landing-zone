package org.example.controller;

import org.example.model.PostCallbackResp;
import org.example.model.PostSignatureResp;
import org.example.service.UploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping(value = "upload")
public class UploadController {

    @Autowired
    UploadService uploadService;

    // 测试示例使用，配置了 CORS 跨域
    @CrossOrigin(origins = "*", allowedHeaders = "*", maxAge = 86400)
    @GetMapping("/getPostSignature")
    public PostSignatureResp getPostSignature() {
        // 请根据自身应用需要，进行请求的合法性校验
        // 本示例只是演示使用，不会对请求身份等进行合法性校验
        return uploadService.generatePostSignature();
    }

    // 回调中返回的响应结果，OSS 会将此响应内容返回给客户端
    // 请根据实际业务场景，自行修改返回内容
    @PostMapping(
        path = "/callback",
        consumes = { MediaType.APPLICATION_FORM_URLENCODED_VALUE }
    )
    public ResponseEntity<PostCallbackResp> callback(@RequestBody String callbackBody, HttpServletRequest request) {
        return uploadService.callback(callbackBody, request);
    }
}
