package org.example.controller;

import org.example.service.DownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "download")
public class DownloadController {

    @Autowired
    DownloadService downloadService;

    // 测试示例使用，配置了 CORS 跨域
    @CrossOrigin(origins = "*", allowedHeaders = "*", maxAge = 86400)
    @GetMapping("/getSignedUrl")
    public String getSignedUrl(@RequestParam("fileName") String fileName) {
        // 请根据自身应用需要，进行请求的合法性校验
        // 本示例只是演示使用，不会对请求身份等进行合法性校验
        return downloadService.generateSignedUrl(fileName);
    }
}
