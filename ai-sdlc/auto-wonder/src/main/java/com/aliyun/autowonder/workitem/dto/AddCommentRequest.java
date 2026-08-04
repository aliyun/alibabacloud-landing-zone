package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AddCommentRequest {
    private String contentMd;
    private List<Long> targetAgentIds;
    private List<Long> targetHumanIds;
}
