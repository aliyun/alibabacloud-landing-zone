package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class AoneTestConnectionResult {
    private boolean success;
    private String message;
    private List<String> checks = new ArrayList<>();
}
