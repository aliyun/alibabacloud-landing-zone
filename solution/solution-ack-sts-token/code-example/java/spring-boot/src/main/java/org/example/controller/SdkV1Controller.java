package org.example.controller;

import org.example.service.SdkV1Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "sdkV1")
public class SdkV1Controller {

    @Autowired
    SdkV1Service sdkV1Service;

    @GetMapping("/getCallerIdentity")
    public String getCallerIdentity() {
        return sdkV1Service.getCallerIdentity();
    }
}
