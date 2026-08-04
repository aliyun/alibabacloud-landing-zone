package com.aliyun.autowonder.controller;

import com.aliyun.autowonder.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloWorldController {

    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.ok("Hello from AutoWonder!");
    }
}
