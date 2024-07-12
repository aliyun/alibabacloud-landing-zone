package org.example.controller;

import org.example.service.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "oss")
public class OssController {

    @Autowired
    OssService ossService;

    @GetMapping("/listBuckets")
    public String listBuckets() {
        return ossService.listBuckets();
    }
}
