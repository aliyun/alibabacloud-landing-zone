package org.example.controller;

import org.example.service.SdkV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "sdkV2")
public class SdkV2Controller {

    @Autowired
    SdkV2Service sdkV2Service;

    @GetMapping("/getCallerIdentity")
    public String getCallerIdentity() {
        return sdkV2Service.getCallerIdentity();
    }
}
