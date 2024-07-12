package org.example.controller;

import org.example.service.SlsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "sls")
public class SlsController {

    @Autowired
    SlsService slsService;

    @GetMapping("/listProjects")
    public String listProjects() {
        return slsService.listProjects();
    }
}
